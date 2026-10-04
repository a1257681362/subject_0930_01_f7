package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * 监管人员-监管区域绑定请求（演示环境用于建立区域归属；生产应对接组织架构）。
 * 一个监管账号绑定一个区域；抽检任务查询/操作按该区域隔离。
 */
public class InspectorRegionRequest {
    @NotNull(message = "监管人员账号ID不能为空")
    private Long inspectorId;
    @NotBlank(message = "监管区域代码不能为空")
    private String regionCode;
    @NotBlank(message = "监管区域名称不能为空")
    private String regionName;

    public Long getInspectorId() { return inspectorId; }
    public void setInspectorId(Long inspectorId) { this.inspectorId = inspectorId; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
}
