package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 样本转移双人确认记录（V7，只追加）。
 * 第一人（交出方 fromHolder）发起转移后为 PENDING，样本停留 IN_TRANSFER 待处理；
 * 必须由与交出方不同的第二人（接收方 confirmer=toHolder）二次确认才 CONFIRMED。
 * 审批只完成一半时本行为 PENDING、样本为 IN_TRANSFER，双方账号/时间均可追溯。
 */
@TableName("t_cc_sample_transfer")
public class SampleTransfer extends BaseEntity {
    private Long sampleId;
    private Long inspectionId;
    private Long batchId;
    /** 样本内第几次转移，从 1 递增 */
    private Integer transferSeq;
    private Long fromHolderId;
    private String fromHolderName;
    private Long toHolderId;
    private String toHolderName;
    private String fromLocation;
    private String toLocation;
    private String transferNote;
    /** PENDING 待第二人确认 / CONFIRMED 双人确认完成 */
    private String confirmStatus;
    private LocalDateTime handoverTime;
    private Long confirmerId;
    private String confirmerName;
    private LocalDateTime confirmTime;

    public Long getSampleId() { return sampleId; }
    public void setSampleId(Long sampleId) { this.sampleId = sampleId; }
    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Integer getTransferSeq() { return transferSeq; }
    public void setTransferSeq(Integer transferSeq) { this.transferSeq = transferSeq; }
    public Long getFromHolderId() { return fromHolderId; }
    public void setFromHolderId(Long fromHolderId) { this.fromHolderId = fromHolderId; }
    public String getFromHolderName() { return fromHolderName; }
    public void setFromHolderName(String fromHolderName) { this.fromHolderName = fromHolderName; }
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
    public String getConfirmStatus() { return confirmStatus; }
    public void setConfirmStatus(String confirmStatus) { this.confirmStatus = confirmStatus; }
    public LocalDateTime getHandoverTime() { return handoverTime; }
    public void setHandoverTime(LocalDateTime handoverTime) { this.handoverTime = handoverTime; }
    public Long getConfirmerId() { return confirmerId; }
    public void setConfirmerId(Long confirmerId) { this.confirmerId = confirmerId; }
    public String getConfirmerName() { return confirmerName; }
    public void setConfirmerName(String confirmerName) { this.confirmerName = confirmerName; }
    public LocalDateTime getConfirmTime() { return confirmTime; }
    public void setConfirmTime(LocalDateTime confirmTime) { this.confirmTime = confirmTime; }
}
