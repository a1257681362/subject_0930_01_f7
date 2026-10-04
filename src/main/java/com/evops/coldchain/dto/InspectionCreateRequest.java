package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/**
 * 抽检任务发起请求：监管人员按运输批次发起抽检。
 * 监管区域不接受前端传入，一律以当前登录监管人员绑定的区域为准（区域隔离在服务端强制）。
 */
public class InspectionCreateRequest {
    @NotNull(message = "被抽检运输批次ID不能为空")
    private Long batchId;
    /** 计划采样位置（样本级以实际采样位置为准） */
    private String samplingLocation;
    /** 抽检依据/事由 */
    private String samplingBasis;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSamplingLocation() { return samplingLocation; }
    public void setSamplingLocation(String samplingLocation) { this.samplingLocation = samplingLocation; }
    public String getSamplingBasis() { return samplingBasis; }
    public void setSamplingBasis(String samplingBasis) { this.samplingBasis = samplingBasis; }
}
