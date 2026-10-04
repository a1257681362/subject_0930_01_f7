package com.evops.coldchain.controller;

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
import com.evops.coldchain.service.InspectionService;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * 药品运输抽检（V7）：
 * 区域绑定 -> 按批次发起任务 -> 抽样封存（唯一封存码/采样人/位置/数量/照片）->
 * 样本转移双人确认（半程停留待处理）-> 抽检结论回写批次（不覆盖原始运输记录）。
 * 查询按监管区域隔离；导出对承运人联系电话脱敏并逐次留痕；已封存样本不可删除。
 */
@RestController
@RequestMapping("/api/cold-chain/inspections")
public class InspectionController {

    private final InspectionService inspectionService;

    public InspectionController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    /** 绑定监管人员-监管区域（演示环境建数；生产对接组织架构） */
    @PostMapping("/regions")
    public ApiResponse<InspectorRegion> bindRegion(@Valid @RequestBody InspectorRegionRequest req) {
        return ApiResponse.ok(inspectionService.bindRegion(req));
    }

    /** 按运输批次发起抽检任务（区域取当前登录监管人员绑定值，前端不可指定） */
    @PostMapping("/tasks/create")
    public ApiResponse<InspectionTask> createTask(@Valid @RequestBody InspectionCreateRequest req) {
        return ApiResponse.ok(inspectionService.createTask(req));
    }

    /** 抽检任务列表（强制按当前监管区域隔离；可按批次/状态过滤） */
    @GetMapping("/tasks")
    public ApiResponse<List<InspectionTask>> listTasks(
            @RequestParam(value = "batchId", required = false) Long batchId,
            @RequestParam(value = "status", required = false) String status) {
        return ApiResponse.ok(inspectionService.listTasks(batchId, status));
    }

    /** 待处理视图：待抽样任务 + 停留半程待第二人确认的样本转移 */
    @GetMapping("/pending")
    public ApiResponse<Map<String, Object>> pending() {
        return ApiResponse.ok(inspectionService.pendingView());
    }

    /** 抽检任务详情（任务 + 样本 + 双人转移记录 + 批次操作日志；区域隔离） */
    @GetMapping("/tasks/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable("id") Long id) {
        return ApiResponse.ok(inspectionService.detail(id));
    }

    /** 抽样封存：生成唯一封存码，记录采样人/位置/数量/封存照片（含 SHA-256） */
    @PostMapping("/samples/seal")
    public ApiResponse<InspectionSample> sealSample(@Valid @RequestBody SampleSealRequest req) {
        return ApiResponse.ok(inspectionService.sealSample(req));
    }

    /** 双人确认第一步：交出方发起转移，样本停留 IN_TRANSFER 待第二人确认 */
    @PostMapping("/transfers/start")
    public ApiResponse<SampleTransfer> startTransfer(@Valid @RequestBody TransferStartRequest req) {
        return ApiResponse.ok(inspectionService.startTransfer(req));
    }

    /** 双人确认第二步：指定接收方本人确认，样本 RECEIVED、持有方转移 */
    @PostMapping("/transfers/confirm")
    public ApiResponse<SampleTransfer> confirmTransfer(@Valid @RequestBody TransferConfirmRequest req) {
        return ApiResponse.ok(inspectionService.confirmTransfer(req));
    }

    /** 出具抽检结论并回写批次（只写 inspection_* 专用列，不覆盖原始运输记录） */
    @PostMapping("/conclude")
    public ApiResponse<InspectionTask> conclude(@Valid @RequestBody InspectionConcludeRequest req) {
        return ApiResponse.ok(inspectionService.conclude(req));
    }

    /** 作废未抽样任务（已封存样本后禁止作废；样本任何状态不可删除） */
    @PostMapping("/void")
    public ApiResponse<InspectionTask> voidTask(@Valid @RequestBody InspectionVoidRequest req) {
        return ApiResponse.ok(inspectionService.voidTask(req));
    }

    /**
     * 导出当前监管区域抽检任务 CSV（需 coldchain:inspection:export）。
     * 承运人联系电话脱敏（保留前3后2）；UTF-8 BOM；逐次写导出审计流水；只读导出。
     */
    @GetMapping("/export")
    public void export(@RequestParam(value = "batchId", required = false) Long batchId,
                       @RequestParam(value = "status", required = false) String status,
                       HttpServletResponse response) throws java.io.IOException {
        List<String> rows = inspectionService.exportRowsCsv(batchId, status);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"inspections.csv\"");
        response.getOutputStream().write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        PrintWriter writer = new PrintWriter(new java.io.OutputStreamWriter(response.getOutputStream(),
                java.nio.charset.StandardCharsets.UTF_8));
        for (String row : rows) {
            writer.println(row);
        }
        writer.flush();
    }
}
