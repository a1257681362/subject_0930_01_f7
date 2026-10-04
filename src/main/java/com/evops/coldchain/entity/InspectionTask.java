package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * 药品运输抽检任务（V7）。
 * 监管人员按运输批次发起；数据按创建人绑定的监管区域（regionCode）隔离。
 * 已封存样本后任务不可作废；结论只回写批次 inspection_* 专用列，不覆盖原始运输记录。
 */
@TableName("t_cc_inspection_task")
public class InspectionTask extends BaseEntity {
    private String inspectionNo;
    private Long batchId;
    /** 批次关键信息快照（建任务时固化） */
    private String batchNoSnap;
    private String carrierNameSnap;
    private String driverNameSnap;
    /** 承运人联系电话快照：库内原值保留，导出 CSV 时脱敏 */
    private String driverPhoneSnap;
    private String plateNoSnap;
    /** 创建方监管区域（查询/操作按此隔离） */
    private String regionCode;
    private String regionName;
    private Long inspectorId;
    private String inspectorName;
    private String samplingLocation;
    private String samplingBasis;
    /** PENDING / SEALED / CONCLUDING / CONCLUDED / VOIDED */
    private String status;
    private Integer sampleTotal;
    private Integer transferTotal;
    /** QUALIFIED / UNQUALIFIED */
    private String concludeResult;
    private String concludeNote;
    private Long concludeBy;
    private String concludeByName;
    private LocalDateTime concludeTime;
    private String voidReason;
    private LocalDateTime voidTime;

    public String getInspectionNo() { return inspectionNo; }
    public void setInspectionNo(String inspectionNo) { this.inspectionNo = inspectionNo; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNoSnap() { return batchNoSnap; }
    public void setBatchNoSnap(String batchNoSnap) { this.batchNoSnap = batchNoSnap; }
    public String getCarrierNameSnap() { return carrierNameSnap; }
    public void setCarrierNameSnap(String carrierNameSnap) { this.carrierNameSnap = carrierNameSnap; }
    public String getDriverNameSnap() { return driverNameSnap; }
    public void setDriverNameSnap(String driverNameSnap) { this.driverNameSnap = driverNameSnap; }
    public String getDriverPhoneSnap() { return driverPhoneSnap; }
    public void setDriverPhoneSnap(String driverPhoneSnap) { this.driverPhoneSnap = driverPhoneSnap; }
    public String getPlateNoSnap() { return plateNoSnap; }
    public void setPlateNoSnap(String plateNoSnap) { this.plateNoSnap = plateNoSnap; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
    public Long getInspectorId() { return inspectorId; }
    public void setInspectorId(Long inspectorId) { this.inspectorId = inspectorId; }
    public String getInspectorName() { return inspectorName; }
    public void setInspectorName(String inspectorName) { this.inspectorName = inspectorName; }
    public String getSamplingLocation() { return samplingLocation; }
    public void setSamplingLocation(String samplingLocation) { this.samplingLocation = samplingLocation; }
    public String getSamplingBasis() { return samplingBasis; }
    public void setSamplingBasis(String samplingBasis) { this.samplingBasis = samplingBasis; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getSampleTotal() { return sampleTotal; }
    public void setSampleTotal(Integer sampleTotal) { this.sampleTotal = sampleTotal; }
    public Integer getTransferTotal() { return transferTotal; }
    public void setTransferTotal(Integer transferTotal) { this.transferTotal = transferTotal; }
    public String getConcludeResult() { return concludeResult; }
    public void setConcludeResult(String concludeResult) { this.concludeResult = concludeResult; }
    public String getConcludeNote() { return concludeNote; }
    public void setConcludeNote(String concludeNote) { this.concludeNote = concludeNote; }
    public Long getConcludeBy() { return concludeBy; }
    public void setConcludeBy(Long concludeBy) { this.concludeBy = concludeBy; }
    public String getConcludeByName() { return concludeByName; }
    public void setConcludeByName(String concludeByName) { this.concludeByName = concludeByName; }
    public LocalDateTime getConcludeTime() { return concludeTime; }
    public void setConcludeTime(LocalDateTime concludeTime) { this.concludeTime = concludeTime; }
    public String getVoidReason() { return voidReason; }
    public void setVoidReason(String voidReason) { this.voidReason = voidReason; }
    public LocalDateTime getVoidTime() { return voidTime; }
    public void setVoidTime(LocalDateTime voidTime) { this.voidTime = voidTime; }
}
