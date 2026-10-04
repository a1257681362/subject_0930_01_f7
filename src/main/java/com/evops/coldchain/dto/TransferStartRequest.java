package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 样本转移发起请求（双人确认的第一步：交出方发起）。
 * 接收方（第二人）必须与交出方为不同账号；发起后样本停留 IN_TRANSFER 待第二人确认。
 */
public class TransferStartRequest {
    @NotNull(message = "抽检样本ID不能为空")
    private Long sampleId;
    /** 接收方（第二确认人）账号ID，必须与当前交出方不同 */
    @NotNull(message = "接收方（第二确认人）账号ID不能为空")
    private Long toHolderId;
    @NotBlank(message = "接收方姓名不能为空")
    private String toHolderName;
    private String fromLocation;
    private String toLocation;
    private String transferNote;

    public Long getSampleId() { return sampleId; }
    public void setSampleId(Long sampleId) { this.sampleId = sampleId; }
    public Long getToHolderId() { return toHolderId; }
    public void setToHolderId(Long toHolderId) { this.toHolderId = toHolderId; }
    public String getToHolderName() { return toHolderName; }
    public void setToHolderName(String toHolderName) { this.toHolderName = toHolderName; }
    public String getFromLocation() { return fromLocation; }
    public void setFromLocation(String fromLocation) { this.fromLocation = fromLocation; }
    public String getToLocation() { return toLocation; }
    public void setToLocation(String toLocation) { this.toLocation = toLocation; }
    public String getTransferNote() { return transferNote; }
    public void setTransferNote(String transferNote) { this.transferNote = transferNote; }
}
