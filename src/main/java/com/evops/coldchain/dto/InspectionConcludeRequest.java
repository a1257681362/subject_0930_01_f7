package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;

/**
 * 抽检结论回写请求：结论只回写批次 inspection_* 专用列，不覆盖任何原始运输记录。
 * 出结论前要求任务下样本均已完成双人转移接收（不存在停留待处理的半程转移）。
 */
public class InspectionConcludeRequest {
    @NotNull(message = "抽检任务ID不能为空")
    private Long inspectionId;
    /** QUALIFIED 合格 / UNQUALIFIED 不合格 */
    @NotBlank(message = "抽检结论不能为空")
    @Pattern(regexp = "QUALIFIED|UNQUALIFIED", message = "抽检结论只能为 QUALIFIED 或 UNQUALIFIED")
    private String result;
    @NotBlank(message = "抽检结论说明不能为空")
    private String note;

    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
