package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 抽检任务作废请求：仅未封存（PENDING）任务可作废；已封存样本后不可作废。 */
public class InspectionVoidRequest {
    @NotNull(message = "抽检任务ID不能为空")
    private Long inspectionId;
    @NotBlank(message = "作废原因不能为空")
    private String reason;

    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
