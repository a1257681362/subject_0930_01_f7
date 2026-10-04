package com.evops.coldchain.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.InspectionSample;
import com.evops.coldchain.entity.InspectionTask;
import com.evops.coldchain.entity.SampleTransfer;
import com.evops.coldchain.entity.TransportBatch;
import com.evops.coldchain.mapper.TransportBatchMapper;
import com.evops.coldchain.service.InspectionService;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 药品运输抽检管理页面（V7，Thymeleaf 服务端渲染 + fetch 调用既有 API）。
 * 页面与接口共用同一套卡口：区域隔离、唯一封存码、双人确认半程停留、已封存不可作废/删除、
 * 结论只回写批次。当前监管人员区域未绑定时页面给出明确提示。
 */
@Controller
@RequestMapping("/ui/cold-chain")
public class InspectionPageController {

    @Resource
    private InspectionService inspectionService;
    @Resource
    private TransportBatchMapper batchMapper;

    @GetMapping("/inspections")
    public String inspections(Model model) {
        com.evops.coldchain.entity.InspectorRegion region;
        try {
            region = inspectionService.currentRegion();
        } catch (BusinessException ex) {
            model.addAttribute("region", null);
            model.addAttribute("regionError", ex.getMessage());
            return "coldchain/inspections";
        }
        model.addAttribute("region", region);

        // 可发起抽检的批次：在途/异常挂起/已签收（抽检可发生在运输各环节），排除已撤回
        List<TransportBatch> batches = batchMapper.selectList(new QueryWrapper<TransportBatch>()
                .ne("status", com.evops.coldchain.CcConstants.BATCH_REVOKED)
                .orderByDesc("id"));
        model.addAttribute("batches", batches);

        List<InspectionTask> tasks = inspectionService.listTasks(null, null);
        List<Map<String, Object>> taskViews = new ArrayList<>();
        List<Map<String, Object>> waitingTransfers = new ArrayList<>();
        for (InspectionTask t : tasks) {
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("task", t);
            List<InspectionSample> samples = inspectionService.listSamples(t.getId());
            List<Map<String, Object>> sampleViews = new ArrayList<>();
            for (InspectionSample s : samples) {
                Map<String, Object> sv = new LinkedHashMap<>();
                sv.put("sample", s);
                List<SampleTransfer> transfers = inspectionService.listTransfers(s.getId());
                sv.put("transfers", transfers);
                sampleViews.add(sv);
                if (com.evops.coldchain.CcConstants.SAMPLE_IN_TRANSFER.equals(s.getStatus())
                        && s.getPendingTransferId() != null) {
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("task", t);
                    w.put("sample", s);
                    w.putAll(sv);
                    waitingTransfers.add(w);
                }
            }
            tv.put("samples", sampleViews);
            taskViews.add(tv);
        }
        model.addAttribute("taskViews", taskViews);
        model.addAttribute("waitingTransfers", waitingTransfers);
        return "coldchain/inspections";
    }
}
