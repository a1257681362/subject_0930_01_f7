package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;

/**
 * 样本转移第二人确认请求（双人确认的第二步：接收方确认）。
 * 只有转移单指定的接收方（toHolder）本人可确认，且不能与交出方为同一人。
 */
public class TransferConfirmRequest {
    @NotNull(message = "样本转移单ID不能为空")
    private Long transferId;
    /** 确认备注（如 封签完好、样本状态正常） */
    private String confirmNote;

    public Long getTransferId() { return transferId; }
    public void setTransferId(Long transferId) { this.transferId = transferId; }
    public String getConfirmNote() { return confirmNote; }
    public void setConfirmNote(String confirmNote) { this.confirmNote = confirmNote; }
}
