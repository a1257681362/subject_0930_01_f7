package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 抽检样本（V7）：监管人员按批次抽取后现场封存。
 * 封存即生成库内唯一封存码（sealCode），记录采样人、实际采样位置、抽样数量与封存照片摘要。
 * 样本行只追加、状态 SEALED -> IN_TRANSFER -> RECEIVED 单向流转；
 * 不存在删除状态与删除路径——已封存样本不可删除。
 */
@TableName("t_cc_inspection_sample")
public class InspectionSample extends BaseEntity {
    private Long inspectionId;
    private Long batchId;
    /** 唯一封存码（FC+8位序列+1位校验位） */
    private String sealCode;
    /** 任务内样本序号，从 1 递增 */
    private Integer sampleSeq;
    private Integer sampleQty;
    private String samplingLocation;
    private String samplerName;
    private Long samplerId;
    private LocalDateTime samplingTime;
    /** 封存照片（演示环境内容落库，生产应转对象存储） */
    private String photoFileName;
    private String photoFileContent;
    private Long photoFileSize;
    private String photoSha256;
    /** SEALED / IN_TRANSFER / RECEIVED（无 DELETED） */
    private String status;
    /** 当前持有方；转移双人确认后更新为接收方 */
    private Long holderId;
    private String holderName;
    /** 半程停留：等待第二人确认的转移单ID（确认后清空） */
    private Long pendingTransferId;

    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getSealCode() { return sealCode; }
    public void setSealCode(String sealCode) { this.sealCode = sealCode; }
    public Integer getSampleSeq() { return sampleSeq; }
    public void setSampleSeq(Integer sampleSeq) { this.sampleSeq = sampleSeq; }
    public Integer getSampleQty() { return sampleQty; }
    public void setSampleQty(Integer sampleQty) { this.sampleQty = sampleQty; }
    public String getSamplingLocation() { return samplingLocation; }
    public void setSamplingLocation(String samplingLocation) { this.samplingLocation = samplingLocation; }
    public String getSamplerName() { return samplerName; }
    public void setSamplerName(String samplerName) { this.samplerName = samplerName; }
    public Long getSamplerId() { return samplerId; }
    public void setSamplerId(Long samplerId) { this.samplerId = samplerId; }
    public LocalDateTime getSamplingTime() { return samplingTime; }
    public void setSamplingTime(LocalDateTime samplingTime) { this.samplingTime = samplingTime; }
    public String getPhotoFileName() { return photoFileName; }
    public void setPhotoFileName(String photoFileName) { this.photoFileName = photoFileName; }
    public String getPhotoFileContent() { return photoFileContent; }
    public void setPhotoFileContent(String photoFileContent) { this.photoFileContent = photoFileContent; }
    public Long getPhotoFileSize() { return photoFileSize; }
    public void setPhotoFileSize(Long photoFileSize) { this.photoFileSize = photoFileSize; }
    public String getPhotoSha256() { return photoSha256; }
    public void setPhotoSha256(String photoSha256) { this.photoSha256 = photoSha256; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getHolderId() { return holderId; }
    public void setHolderId(Long holderId) { this.holderId = holderId; }
    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }
    public Long getPendingTransferId() { return pendingTransferId; }
    public void setPendingTransferId(Long pendingTransferId) { this.pendingTransferId = pendingTransferId; }
}
