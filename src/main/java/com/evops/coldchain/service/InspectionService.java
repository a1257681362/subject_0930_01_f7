package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.InspectionConcludeRequest;
import com.evops.coldchain.dto.InspectionCreateRequest;
import com.evops.coldchain.dto.InspectionVoidRequest;
import com.evops.coldchain.dto.InspectorRegionRequest;
import com.evops.coldchain.dto.SampleSealRequest;
import com.evops.coldchain.dto.TransferConfirmRequest;
import com.evops.coldchain.dto.TransferStartRequest;
import com.evops.coldchain.entity.InspectionSample;
import com.evops.coldchain.entity.InspectionTask;
import com.evops.coldchain.entity.InspectorRegion;
import com.evops.coldchain.entity.SampleTransfer;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.entity.Vehicle;
import com.evops.coldchain.mapper.InspectionSampleMapper;
import com.evops.coldchain.mapper.InspectionTaskMapper;
import com.evops.coldchain.mapper.InspectorRegionMapper;
import com.evops.coldchain.mapper.SampleTransferMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.mapper.VehicleMapper;
import com.evops.common.BusinessException;
import com.evops.common.UserContext;
import com.evops.common.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品运输抽检服务（V7）。
 *
 * 业务规则：
 * - 监管人员按运输批次发起抽检任务；任务归属创建人绑定的“监管区域”，查询/操作按区域隔离。
 * - 按批次抽样并现场封存：系统生成库内唯一封存码（FC+8位序列+1位校验位），记录采样人、
 *   实际采样位置、抽样数量与封存照片（照片内容落库即算 SHA-256 摘要）。
 * - 样本行只追加、状态 SEALED -> IN_TRANSFER -> RECEIVED 单向流转；已封存样本不可删除，
 *   也不能在已封存后作废任务（仅 PENDING 无样本任务可作废）。
 * - 样本转移必须双人确认：交出方（第一人）发起后停留 PENDING/IN_TRANSFER 待处理；
 *   必须由指定的、与交出方不同的接收方（第二人）确认才完成。半程期间不可再发起转移。
 * - 抽检结论只回写批次 inspection_* 专用列，绝不覆盖批次状态/签收/责任主体等原始运输记录。
 * - 任务/样本/转移/结论全部写 t_cc_op_log 并带 batch_id，批次台账可完整追溯抽检全链路。
 *
 * 本服务只依赖 Mapper 与留痕/区域服务，不反向依赖 TransportBatchService，保持单向依赖。
 */
@Service
public class InspectionService {

    @Resource
    private InspectionTaskMapper taskMapper;
    @Resource
    private InspectionSampleMapper sampleMapper;
    @Resource
    private SampleTransferMapper transferMapper;
    @Resource
    private InspectorRegionMapper regionMapper;
    @Resource
    private TransportBatchMapper batchMapper;
    @Resource
    private VehicleMapper vehicleMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    // ---------------- 监管区域绑定 ----------------

    /** 绑定监管人员与监管区域（一个账号一个区域；重复绑定以新区域覆盖并留痕） */
    @Transactional
    public InspectorRegion bindRegion(InspectorRegionRequest req) {
        InspectorRegion region = regionMapper.selectOne(new QueryWrapper<InspectorRegion>()
                .eq("inspector_id", req.getInspectorId()).last("LIMIT 1"));
        boolean created = region == null;
        if (created) {
            region = new InspectorRegion();
            region.setInspectorId(req.getInspectorId());
            region.setBindTime(LocalDateTime.now());
        }
        region.setRegionCode(req.getRegionCode().trim().toUpperCase());
        region.setRegionName(req.getRegionName().trim());
        if (created) {
            regionMapper.insert(region);
        } else {
            regionMapper.updateById(region);
        }
        opLogService.log(CcConstants.ENT_INSPECTION, null, null, CcConstants.ACT_INSPECTION_CREATE,
                String.format("监管人员账号ID=%d 绑定监管区域 %s（%s）",
                        req.getInspectorId(), region.getRegionCode(), region.getRegionName()));
        return region;
    }

    /** 当前登录监管人员的区域；未绑定区域直接给出停点（不能发起/查看抽检） */
    public InspectorRegion currentRegion() {
        UserPrincipal principal = UserContext.current();
        InspectorRegion region = regionMapper.selectOne(new QueryWrapper<InspectorRegion>()
                .eq("inspector_id", principal.getId()).last("LIMIT 1"));
        if (region == null) {
            throw new BusinessException(String.format(
                    "监管人员[%s]尚未绑定监管区域，不能发起/查看药品运输抽检任务（请先绑定监管区域）",
                    principal.getUsername()));
        }
        return region;
    }

    // ---------------- 发起抽检任务 ----------------

    @Transactional
    public InspectionTask createTask(InspectionCreateRequest req) {
        InspectorRegion region = currentRegion();
        TransportBatch batch = batchMapper.selectById(req.getBatchId());
        if (batch == null) {
            throw new BusinessException("运输批次不存在: " + req.getBatchId());
        }
        if (CcConstants.BATCH_REVOKED.equals(batch.getStatus())) {
            throw new BusinessException("批次已撤回作废，不能发起抽检: " + batch.getBatchNo());
        }
        Vehicle vehicle = vehicleMapper.selectById(batch.getVehicleId());

        InspectionTask task = new InspectionTask();
        task.setInspectionNo(generateInspectionNo());
        task.setBatchId(batch.getId());
        task.setBatchNoSnap(batch.getBatchNo());
        if (vehicle != null) {
            task.setCarrierNameSnap(vehicle.getCarrierName());
            task.setDriverNameSnap(vehicle.getDriverName());
            task.setDriverPhoneSnap(vehicle.getDriverPhone());
            task.setPlateNoSnap(vehicle.getPlateNo());
        }
        task.setRegionCode(region.getRegionCode());
        task.setRegionName(region.getRegionName());
        task.setInspectorId(region.getInspectorId());
        task.setInspectorName(UserContext.currentName());
        task.setSamplingLocation(trimToNull(req.getSamplingLocation()));
        task.setSamplingBasis(trimToNull(req.getSamplingBasis()));
        task.setStatus(CcConstants.INSP_PENDING);
        task.setSampleTotal(0);
        task.setTransferTotal(0);
        taskMapper.insert(task);

        opLogService.log(CcConstants.ENT_INSPECTION, task.getId(), batch.getId(),
                CcConstants.ACT_INSPECTION_CREATE,
                String.format("监管人员 %s 按批次 %s 发起抽检任务 %s（监管区域=%s/%s，承运商=%s，承运人=%s）；"
                                + "抽检依据=%s，计划采样位置=%s。任务待抽样",
                        task.getInspectorName(), batch.getBatchNo(), task.getInspectionNo(),
                        region.getRegionCode(), region.getRegionName(),
                        nz(task.getCarrierNameSnap()), nz(task.getDriverNameSnap()),
                        nz(task.getSamplingBasis()), nz(task.getSamplingLocation())));
        return task;
    }

    // ---------------- 抽样封存 ----------------

    /**
     * 按批次抽取样本并现场封存：生成唯一封存码，固化采样人/位置/数量/封存照片（含 SHA-256）。
     * 已作废/已出结论任务不能再封存。
     */
    @Transactional
    public InspectionSample sealSample(SampleSealRequest req) {
        InspectionTask task = requireTaskInRegion(req.getInspectionId());
        if (CcConstants.INSP_VOIDED.equals(task.getStatus())) {
            throw new BusinessException(String.format("抽检任务 %s 已作废，不能再封存样本", task.getInspectionNo()));
        }
        if (CcConstants.INSP_CONCLUDED.equals(task.getStatus())) {
            throw new BusinessException(String.format("抽检任务 %s 已出结论，不能再追加样本", task.getInspectionNo()));
        }

        byte[] photoBytes = req.getPhotoFileContent().getBytes(StandardCharsets.UTF_8);
        int seq = (task.getSampleTotal() == null ? 0 : task.getSampleTotal()) + 1;

        InspectionSample sample = new InspectionSample();
        sample.setInspectionId(task.getId());
        sample.setBatchId(task.getBatchId());
        sample.setSealCode(generateSealCode());
        sample.setSampleSeq(seq);
        sample.setSampleQty(req.getSampleQty());
        sample.setSamplingLocation(req.getSamplingLocation().trim());
        sample.setSamplerName(req.getSamplerName().trim());
        UserPrincipal principal = UserContext.current();
        sample.setSamplerId(principal.getId());
        sample.setSamplingTime(req.getSamplingTime() == null ? LocalDateTime.now() : req.getSamplingTime());
        sample.setPhotoFileName(req.getPhotoFileName().trim());
        sample.setPhotoFileContent(req.getPhotoFileContent());
        sample.setPhotoFileSize((long) photoBytes.length);
        sample.setPhotoSha256(sha256Hex(photoBytes));
        sample.setStatus(CcConstants.SAMPLE_SEALED);
        // 初始持有方为封存操作的监管人员（采样封存地）
        sample.setHolderId(principal.getId());
        sample.setHolderName(UserContext.currentName());
        sampleMapper.insert(sample);

        // 任务首次封存即由 PENDING 进入 SEALED；已封存后不可作废
        task.setSampleTotal(seq);
        if (CcConstants.INSP_PENDING.equals(task.getStatus())) {
            task.setStatus(CcConstants.INSP_SEALED);
        }
        taskMapper.updateById(task);

        opLogService.log(CcConstants.ENT_INSPECTION_SAMPLE, sample.getId(), task.getBatchId(),
                CcConstants.ACT_SAMPLE_SEAL,
                String.format("任务 %s 第%d个样本按批次 %s 抽样封存：封存码=%s（库内唯一），采样人=%s，采样位置=%s，"
                                + "数量=%d，封存照片=%s（%d字节，SHA-256=%s），采样时间=%s；样本状态=SEALED，持有方=%s",
                        task.getInspectionNo(), seq, task.getBatchNoSnap(), sample.getSealCode(),
                        sample.getSamplerName(), sample.getSamplingLocation(), sample.getSampleQty(),
                        sample.getPhotoFileName(), sample.getPhotoFileSize(), sample.getPhotoSha256(),
                        sample.getSamplingTime(), sample.getHolderName()));
        return sample;
    }

    // ---------------- 样本转移：双人确认 ----------------

    /** 双人确认第一步：交出方（当前持有方）发起转移，样本进入 IN_TRANSFER，停留等待第二人确认 */
    @Transactional
    public SampleTransfer startTransfer(TransferStartRequest req) {
        InspectionSample sample = requireSampleInRegion(req.getSampleId());
        InspectionTask task = requireTaskInRegion(sample.getInspectionId());
        if (CcConstants.INSP_VOIDED.equals(task.getStatus()) || CcConstants.INSP_CONCLUDED.equals(task.getStatus())) {
            throw new BusinessException(String.format(
                    "抽检任务 %s 已%s，不能再转移样本", task.getInspectionNo(),
                    CcConstants.INSP_VOIDED.equals(task.getStatus()) ? "作废" : "出结论"));
        }
        UserPrincipal principal = UserContext.current();
        if (sample.getHolderId() == null || !sample.getHolderId().equals(principal.getId())) {
            throw new BusinessException(String.format(
                    "只有样本当前持有方才能发起转移：样本 %s 当前持有方=%s，当前操作人=%s",
                    sample.getSealCode(), nz(sample.getHolderName()), principal.getUsername()));
        }
        // 双人确认：接收方必须与交出方为不同账号
        if (req.getToHolderId().equals(principal.getId())) {
            throw new BusinessException("样本转移必须双人确认：接收方（第二确认人）不能与交出方为同一人");
        }
        if (CcConstants.SAMPLE_IN_TRANSFER.equals(sample.getStatus()) || sample.getPendingTransferId() != null) {
            SampleTransfer pending = sample.getPendingTransferId() == null
                    ? null : transferMapper.selectById(sample.getPendingTransferId());
            throw new BusinessException(String.format(
                    "样本 %s 的转移已由 %s 发起、正在等待 %s 二次确认（转移单#%s），审批只完成一半，"
                            + "样本停留待处理，不能重复发起转移",
                    sample.getSealCode(),
                    pending == null ? "" : pending.getFromHolderName(),
                    pending == null ? "" : pending.getToHolderName(),
                    pending == null ? sample.getPendingTransferId() : pending.getId()));
        }

        int transferSeq = countTransfers(sample.getId()) + 1;
        SampleTransfer transfer = new SampleTransfer();
        transfer.setSampleId(sample.getId());
        transfer.setInspectionId(task.getId());
        transfer.setBatchId(task.getBatchId());
        transfer.setTransferSeq(transferSeq);
        transfer.setFromHolderId(principal.getId());
        transfer.setFromHolderName(UserContext.currentName());
        transfer.setToHolderId(req.getToHolderId());
        transfer.setToHolderName(req.getToHolderName().trim());
        transfer.setFromLocation(trimToNull(req.getFromLocation()));
        transfer.setToLocation(trimToNull(req.getToLocation()));
        transfer.setTransferNote(trimToNull(req.getTransferNote()));
        transfer.setConfirmStatus(CcConstants.TRANSFER_PENDING);
        transfer.setHandoverTime(LocalDateTime.now());
        transferMapper.insert(transfer);

        // 样本进入“转移中·待第二人确认”半程状态
        sample.setStatus(CcConstants.SAMPLE_IN_TRANSFER);
        sample.setPendingTransferId(transfer.getId());
        sampleMapper.updateById(sample);

        task.setTransferTotal((task.getTransferTotal() == null ? 0 : task.getTransferTotal()) + 1);
        taskMapper.updateById(task);

        opLogService.log(CcConstants.ENT_SAMPLE_TRANSFER, transfer.getId(), task.getBatchId(),
                CcConstants.ACT_SAMPLE_TRANSFER_START,
                String.format("任务 %s 样本 %s 第%d次转移：交出方 %s 已发起（第一步），接收方=%s（%s）；"
                                + "交出地=%s，送达地=%s，备注=%s。当前仅第一人确认，样本停留 IN_TRANSFER 待第二人确认",
                        task.getInspectionNo(), sample.getSealCode(), transferSeq,
                        transfer.getFromHolderName(), transfer.getToHolderId(), transfer.getToHolderName(),
                        nz(transfer.getFromLocation()), nz(transfer.getToLocation()), nz(transfer.getTransferNote())));
        return transfer;
    }

    /** 双人确认第二步：指定接收方（第二人）本人确认；确认后样本 RECEIVED，持有方转移 */
    @Transactional
    public SampleTransfer confirmTransfer(TransferConfirmRequest req) {
        SampleTransfer transfer = transferMapper.selectById(req.getTransferId());
        if (transfer == null) {
            throw new BusinessException("样本转移单不存在: " + req.getTransferId());
        }
        // 区域隔离：确认人也必须在任务所属监管区域内（由 requireTaskInRegion 校验当前人区域归属）
        InspectionTask task = requireTaskInRegion(transfer.getInspectionId());
        InspectionSample sample = sampleMapper.selectById(transfer.getSampleId());
        if (sample == null) {
            throw new BusinessException("样本不存在: " + transfer.getSampleId());
        }

        if (CcConstants.TRANSFER_CONFIRMED.equals(transfer.getConfirmStatus())) {
            throw new BusinessException(String.format(
                    "转移单#%d 已于 %s 由 %s 双人确认完成，不能重复确认（转移事实只进不退）",
                    transfer.getId(), transfer.getConfirmTime(), transfer.getConfirmerName()));
        }
        UserPrincipal principal = UserContext.current();
        if (!transfer.getToHolderId().equals(principal.getId())) {
            throw new BusinessException(String.format(
                    "样本转移必须双人确认：该转移单指定的第二确认人是账号ID=%d（%s），当前操作人账号ID=%d 无权确认",
                    transfer.getToHolderId(), transfer.getToHolderName(), principal.getId()));
        }
        // 数据库约束 ck_cc_transfer_person 已保证 from<>to；此处再给一道明确业务提示
        if (transfer.getFromHolderId().equals(principal.getId())) {
            throw new BusinessException("第二确认人不能与交出方为同一人，必须双人确认");
        }

        transfer.setConfirmStatus(CcConstants.TRANSFER_CONFIRMED);
        transfer.setConfirmerId(principal.getId());
        transfer.setConfirmerName(UserContext.currentName());
        transfer.setConfirmTime(LocalDateTime.now());
        transferMapper.updateById(transfer);

        // 双人确认完成：样本转为已接收，持有方转移给接收方。
        sample.setStatus(CcConstants.SAMPLE_RECEIVED);
        sample.setHolderId(principal.getId());
        sample.setHolderName(UserContext.currentName());
        sampleMapper.updateById(sample);
        // MyBatis-Plus updateById 默认忽略 null 字段，挂起转移ID须用独立 UpdateWrapper 显式置 NULL，
        // 否则“半程停留”标记残留会误判仍有未完成转移。
        sampleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<InspectionSample>()
                .eq("id", sample.getId())
                .set("pending_transfer_id", null));
        sample.setPendingTransferId(null);

        opLogService.log(CcConstants.ENT_SAMPLE_TRANSFER, transfer.getId(), task.getBatchId(),
                CcConstants.ACT_SAMPLE_TRANSFER_CONFIRM,
                String.format("任务 %s 样本 %s 第%d次转移完成双人确认：第二人（接收方）%s 于 %s 确认接收，"
                                + "样本状态=RECEIVED，持有方由 %s 转移为 %s。确认备注=%s",
                        task.getInspectionNo(), sample.getSealCode(), transfer.getTransferSeq(),
                        transfer.getConfirmerName(), transfer.getConfirmTime(),
                        transfer.getFromHolderName(), transfer.getConfirmerName(),
                        nz(req.getConfirmNote())));
        return transfer;
    }

    // ---------------- 抽检结论回写批次（不覆盖原始运输记录） ----------------

    /**
     * 出具抽检结论并回写批次：要求至少封存一个样本、且无停留待处理（半程）的转移。
     * 结论只写批次 inspection_* 三列与任务结论列，不修改批次状态/签收/责任主体等原始运输记录。
     */
    @Transactional
    public InspectionTask conclude(InspectionConcludeRequest req) {
        InspectionTask task = requireTaskInRegion(req.getInspectionId());
        if (CcConstants.INSP_VOIDED.equals(task.getStatus())) {
            throw new BusinessException(String.format("抽检任务 %s 已作废，不能出结论", task.getInspectionNo()));
        }
        if (CcConstants.INSP_CONCLUDED.equals(task.getStatus())) {
            throw new BusinessException(String.format(
                    "抽检任务 %s 已出具结论（%s），结论只追加一次，不能重复出具/覆盖",
                    task.getInspectionNo(), task.getConcludeResult()));
        }
        List<InspectionSample> samples = listSamples(task.getId());
        if (samples.isEmpty()) {
            throw new BusinessException(String.format(
                    "抽检任务 %s 尚未封存任何样本，不能出具抽检结论", task.getInspectionNo()));
        }
        List<String> waiting = new ArrayList<>();
        for (InspectionSample s : samples) {
            if (CcConstants.SAMPLE_IN_TRANSFER.equals(s.getStatus()) || s.getPendingTransferId() != null) {
                waiting.add(s.getSealCode());
            }
        }
        if (!waiting.isEmpty()) {
            throw new BusinessException(String.format(
                    "尚有样本转移只完成一半（等待第二人确认）：%s；样本停留待处理期间不能出具抽检结论", waiting));
        }

        String result = req.getResult().trim().toUpperCase();
        TransportBatch batchBefore = batchMapper.selectById(task.getBatchId());
        // 记录原始运输字段，回写后校验不被覆盖
        String originStatus = batchBefore.getStatus();
        String originSignBy = batchBefore.getSignBy();
        String originResponsibleOrg = batchBefore.getResponsibleOrg();
        String originResponsibleParty = batchBefore.getResponsibleParty();

        LocalDateTime now = LocalDateTime.now();
        // 1) 任务结论列（只追加一次）
        task.setStatus(CcConstants.INSP_CONCLUDED);
        task.setConcludeResult(result);
        task.setConcludeNote(req.getNote().trim());
        task.setConcludeBy(UserContext.currentId());
        task.setConcludeByName(UserContext.currentName());
        task.setConcludeTime(now);
        taskMapper.updateById(task);

        // 2) 仅回写批次“抽检专用列”，绝不触碰原始运输记录
        batchBefore.setInspectionResult(result);
        batchBefore.setInspectionNote(req.getNote().trim());
        batchBefore.setInspectionTime(now);
        batchMapper.updateById(batchBefore);

        // 3) 防御性自检：原始运输记录必须保持原值
        TransportBatch after = batchMapper.selectById(task.getBatchId());
        if (!java.util.Objects.equals(after.getStatus(), originStatus)
                || !java.util.Objects.equals(after.getSignBy(), originSignBy)
                || !java.util.Objects.equals(after.getResponsibleOrg(), originResponsibleOrg)
                || !java.util.Objects.equals(after.getResponsibleParty(), originResponsibleParty)) {
            throw new BusinessException("抽检结论回写异常：检测到原始运输记录被改动，事务回滚");
        }

        opLogService.log(CcConstants.ENT_INSPECTION, task.getId(), task.getBatchId(),
                CcConstants.ACT_INSPECTION_CONCLUDE,
                String.format("任务 %s 对批次 %s 出具抽检结论=%s（%s）：%s。结论仅回写批次抽检专用列"
                                + "（inspection_result/note/time），批次状态/签收/责任主体等原始运输记录保持不变（批次状态仍为=%s）",
                        task.getInspectionNo(), task.getBatchNoSnap(), result,
                        CcConstants.INSP_QUALIFIED.equals(result) ? "合格" : "不合格",
                        task.getConcludeNote(), after.getStatus()));
        return task;
    }

    // ---------------- 作废（已封存不可作废；样本无删除路径） ----------------

    /** 仅 PENDING（从未封存样本）任务可作废；已封存样本后禁止作废，样本本身任何状态都不可删除 */
    @Transactional
    public InspectionTask voidTask(InspectionVoidRequest req) {
        InspectionTask task = requireTaskInRegion(req.getInspectionId());
        if (CcConstants.INSP_VOIDED.equals(task.getStatus())) {
            throw new BusinessException(String.format("抽检任务 %s 已作废，不能重复作废", task.getInspectionNo()));
        }
        if (CcConstants.INSP_CONCLUDED.equals(task.getStatus())) {
            throw new BusinessException(String.format("抽检任务 %s 已出结论，不能作废", task.getInspectionNo()));
        }
        int sampleCount = task.getSampleTotal() == null ? 0 : task.getSampleTotal();
        if (sampleCount > 0) {
            throw new BusinessException(String.format(
                    "抽检任务 %s 已封存 %d 个样本，已封存样本不可删除、任务不可作废（仅未抽样的待处理任务可作废）",
                    task.getInspectionNo(), sampleCount));
        }
        task.setStatus(CcConstants.INSP_VOIDED);
        task.setVoidReason(req.getReason().trim());
        task.setVoidTime(LocalDateTime.now());
        taskMapper.updateById(task);

        opLogService.log(CcConstants.ENT_INSPECTION, task.getId(), task.getBatchId(),
                CcConstants.ACT_INSPECTION_VOID,
                String.format("抽检任务 %s（批次 %s）在封存样本前作废，原因=%s；无样本被删除",
                        task.getInspectionNo(), task.getBatchNoSnap(), task.getVoidReason()));
        return task;
    }

    // ---------------- 查询（区域隔离） ----------------

    public InspectionTask requireTaskInRegion(Long taskId) {
        InspectionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException("抽检任务不存在: " + taskId);
        }
        InspectorRegion region = currentRegion();
        if (!region.getRegionCode().equals(task.getRegionCode())) {
            // 跨区域访问按“不可见”处理，不泄露任务是否存在
            throw new BusinessException(String.format(
                    "抽检任务不存在: %d（当前监管区域=%s 无权访问其它区域数据，数据已按监管区域隔离）",
                    taskId, region.getRegionCode()));
        }
        return task;
    }

    public InspectionSample requireSampleInRegion(Long sampleId) {
        InspectionSample sample = sampleMapper.selectById(sampleId);
        if (sample == null) {
            throw new BusinessException("抽检样本不存在: " + sampleId);
        }
        requireTaskInRegion(sample.getInspectionId());
        return sample;
    }

    /** 任务列表：强制按当前监管人员区域隔离；可按批次/状态过滤 */
    public List<InspectionTask> listTasks(Long batchId, String status) {
        InspectorRegion region = currentRegion();
        QueryWrapper<InspectionTask> qw = new QueryWrapper<InspectionTask>()
                .eq("region_code", region.getRegionCode())
                .orderByDesc("id");
        if (batchId != null) {
            qw.eq("batch_id", batchId);
        }
        if (status != null && !status.trim().isEmpty()) {
            qw.eq("status", status.trim().toUpperCase());
        }
        return taskMapper.selectList(qw);
    }

    /** 任务详情（区域隔离）：任务 + 样本（含转移双人确认记录） */
    public Map<String, Object> detail(Long taskId) {
        InspectionTask task = requireTaskInRegion(taskId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task", task);

        List<InspectionSample> samples = listSamples(taskId);
        List<Map<String, Object>> sampleViews = new ArrayList<>();
        for (InspectionSample s : samples) {
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("sample", s);
            sv.put("transfers", listTransfers(s.getId()));
            sampleViews.add(sv);
        }
        data.put("samples", sampleViews);
        data.put("opLogs", opLogService.listByBatch(task.getBatchId()));
        return data;
    }

    public List<InspectionSample> listSamples(Long taskId) {
        return sampleMapper.selectList(new QueryWrapper<InspectionSample>()
                .eq("inspection_id", taskId).orderByAsc("sample_seq"));
    }

    public List<SampleTransfer> listTransfers(Long sampleId) {
        return transferMapper.selectList(new QueryWrapper<SampleTransfer>()
                .eq("sample_id", sampleId).orderByAsc("transfer_seq"));
    }

    /** 按批次聚合抽检视图（供批次台账挂接；台账查看者不强制区域，因其本来就有批次台账权限） */
    public Map<String, Object> inspectionViewForBatch(Long batchId) {
        List<InspectionTask> tasks = taskMapper.selectList(new QueryWrapper<InspectionTask>()
                .eq("batch_id", batchId).orderByAsc("id"));
        List<Map<String, Object>> taskViews = new ArrayList<>();
        for (InspectionTask t : tasks) {
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("task", t);
            List<InspectionSample> samples = listSamples(t.getId());
            List<Map<String, Object>> sampleViews = new ArrayList<>();
            for (InspectionSample s : samples) {
                Map<String, Object> sv = new LinkedHashMap<>();
                sv.put("sample", s);
                sv.put("transfers", listTransfers(s.getId()));
                sampleViews.add(sv);
            }
            tv.put("samples", sampleViews);
            taskViews.add(tv);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("taskCount", tasks.size());
        view.put("tasks", taskViews);
        return view;
    }

    /** 待处理视图：当前区域内停留待处理的任务（PENDING）与半程转移（IN_TRANSFER 样本） */
    public Map<String, Object> pendingView() {
        InspectorRegion region = currentRegion();
        List<InspectionTask> pendingTasks = taskMapper.selectList(new QueryWrapper<InspectionTask>()
                .eq("region_code", region.getRegionCode())
                .in("status", CcConstants.INSP_PENDING, CcConstants.INSP_SEALED)
                .orderByAsc("id"));
        // 半程转移：样本 IN_TRANSFER（第一人与第二人确认之间）
        List<InspectionTask> regionTasks = taskMapper.selectList(new QueryWrapper<InspectionTask>()
                .eq("region_code", region.getRegionCode()));
        List<Map<String, Object>> waitingTransfers = new ArrayList<>();
        for (InspectionTask t : regionTasks) {
            for (InspectionSample s : listSamples(t.getId())) {
                if (CcConstants.SAMPLE_IN_TRANSFER.equals(s.getStatus()) && s.getPendingTransferId() != null) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("task", t);
                    w.put("sample", s);
                    w.put("transfer", transferMapper.selectById(s.getPendingTransferId()));
                    waitingTransfers.add(w);
                }
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("region", region);
        view.put("pendingTasks", pendingTasks);
        view.put("waitingTransfers", waitingTransfers);
        return view;
    }

    // ---------------- 导出（承运人电话脱敏 + 审计） ----------------

    /**
     * 导出当前区域抽检任务 CSV：承运人联系电话脱敏（保留前3后2），逐次写导出审计流水。
     * 返回 [表头, 行...] 由控制器写出。
     */
    public List<String> exportRowsCsv(Long batchId, String status) {
        List<InspectionTask> tasks = listTasks(batchId, status);
        List<String> rows = new ArrayList<>();
        rows.add("抽检任务号,批次号,监管区域,承运商,承运人,承运人电话(脱敏),车牌,抽检状态,封存样本数,转移次数,抽检结论,结论时间,发起监管人");
        for (InspectionTask t : tasks) {
            rows.add(String.join(",",
                    csv(t.getInspectionNo()), csv(t.getBatchNoSnap()),
                    csv(t.getRegionCode() + "/" + t.getRegionName()),
                    csv(t.getCarrierNameSnap()), csv(t.getDriverNameSnap()),
                    csv(maskPhone(t.getDriverPhoneSnap())), csv(t.getPlateNoSnap()),
                    csv(t.getStatus()), csv(t.getSampleTotal()), csv(t.getTransferTotal()),
                    csv(t.getConcludeResult()), csv(t.getConcludeTime()), csv(t.getInspectorName())));
        }
        opLogService.log(CcConstants.ENT_INSPECTION, null, batchId, CcConstants.ACT_INSPECTION_EXPORT,
                String.format("导出药品运输抽检 CSV（监管区域=%s，批次过滤=%s，状态过滤=%s）：共 %d 条；"
                                + "承运人联系电话已脱敏（保留前3后2），只读导出不改写/删除任何抽检与运输记录",
                        currentRegion().getRegionCode(), batchId == null ? "全部" : batchId,
                        status == null ? "全部" : status, tasks.size()));
        return rows;
    }

    /** 电话脱敏：保留前3位与后2位，其余以 * 替代；空值原样返回，过短号码全脱敏 */
    public static String maskPhone(String phone) {
        if (phone == null) {
            return "";
        }
        String p = phone.trim();
        if (p.isEmpty()) {
            return "";
        }
        int len = p.length();
        if (len <= 5) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < len; i++) {
                sb.append('*');
            }
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder(p.substring(0, 3));
        for (int i = 3; i < len - 2; i++) {
            sb.append('*');
        }
        sb.append(p.substring(len - 2));
        return sb.toString();
    }

    // ---------------- 私有工具 ----------------

    private int countTransfers(Long sampleId) {
        Long n = transferMapper.selectCount(new QueryWrapper<SampleTransfer>().eq("sample_id", sampleId));
        return n == null ? 0 : n.intValue();
    }

    /** 抽检任务号：CJ + 8位序列 + 1位校验位（数字，库内唯一由 uk 约束兜底） */
    private String generateInspectionNo() {
        long seq = sequenceMapper.nextInspectionNo();
        return String.format("CJ%08d%d", seq, checkDigit(seq));
    }

    /** 唯一封存码：FC + 8位序列 + 1位校验位 */
    private String generateSealCode() {
        long seq = sequenceMapper.nextSealNo();
        return String.format("FC%08d%d", seq, checkDigit(seq));
    }

    /** 对 8 位序列求 1 位十进制校验位（加权和模 10），降低封存码人工抄录差错 */
    private static int checkDigit(long seq) {
        String s = String.format("%08d", seq);
        int sum = 0;
        for (int i = 0; i < s.length(); i++) {
            int digit = s.charAt(i) - '0';
            sum += digit * (i + 1);
        }
        return sum % 10;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 摘要计算失败", e);
        }
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
