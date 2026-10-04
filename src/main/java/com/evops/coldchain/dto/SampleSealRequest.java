package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.time.LocalDateTime;

/**
 * 抽检样本封存请求：按批次抽样并现场封存。
 * 封存码由系统生成（库内唯一）；采样人/实际采样位置/数量/封存照片为必填留痕项，
 * 照片内容落库即计算 SHA-256 摘要。
 */
public class SampleSealRequest {
    @NotNull(message = "抽检任务ID不能为空")
    private Long inspectionId;
    /** 抽样数量（必须 > 0） */
    @NotNull(message = "抽样数量不能为空")
    @Positive(message = "抽样数量必须大于0")
    private Integer sampleQty;
    /** 实际采样位置（必填） */
    @NotBlank(message = "采样位置不能为空")
    private String samplingLocation;
    /** 采样人（必填） */
    @NotBlank(message = "采样人不能为空")
    private String samplerName;
    /** 采样时间（不传取当前时间） */
    private LocalDateTime samplingTime;
    /** 封存照片文件名（必填） */
    @NotBlank(message = "封存照片文件名不能为空")
    private String photoFileName;
    /** 封存照片内容（必填，演示环境落库；生产应转对象存储） */
    @NotBlank(message = "封存照片内容不能为空")
    private String photoFileContent;

    public Long getInspectionId() { return inspectionId; }
    public void setInspectionId(Long inspectionId) { this.inspectionId = inspectionId; }
    public Integer getSampleQty() { return sampleQty; }
    public void setSampleQty(Integer sampleQty) { this.sampleQty = sampleQty; }
    public String getSamplingLocation() { return samplingLocation; }
    public void setSamplingLocation(String samplingLocation) { this.samplingLocation = samplingLocation; }
    public String getSamplerName() { return samplerName; }
    public void setSamplerName(String samplerName) { this.samplerName = samplerName; }
    public LocalDateTime getSamplingTime() { return samplingTime; }
    public void setSamplingTime(LocalDateTime samplingTime) { this.samplingTime = samplingTime; }
    public String getPhotoFileName() { return photoFileName; }
    public void setPhotoFileName(String photoFileName) { this.photoFileName = photoFileName; }
    public String getPhotoFileContent() { return photoFileContent; }
    public void setPhotoFileContent(String photoFileContent) { this.photoFileContent = photoFileContent; }
}
