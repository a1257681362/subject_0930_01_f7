package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 监管人员-监管区域绑定（V7）：一个监管账号绑定一个监管区域。
 * 抽检任务查询/操作按此区域隔离；未绑定区域的监管账号不能发起/查看抽检。
 */
@TableName("t_cc_inspector_region")
public class InspectorRegion extends BaseEntity {
    private Long inspectorId;
    /** 监管区域代码，如 HD / HN / HB */
    private String regionCode;
    private String regionName;
    private java.time.LocalDateTime bindTime;

    public Long getInspectorId() { return inspectorId; }
    public void setInspectorId(Long inspectorId) { this.inspectorId = inspectorId; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
    public java.time.LocalDateTime getBindTime() { return bindTime; }
    public void setBindTime(java.time.LocalDateTime bindTime) { this.bindTime = bindTime; }
}
