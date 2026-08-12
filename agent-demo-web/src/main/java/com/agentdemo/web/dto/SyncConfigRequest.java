package com.agentdemo.web.dto;

import lombok.Data;

import java.util.List;

/**
 * 同步配置请求 DTO
 * <p>
 * 业务含义：前端一次性提交全部厂商配置，后端调用 replaceAll 替换所有现有配置。
 * 适用于配置导入、批量更新等场景。
 * </p>
 */
@Data
public class SyncConfigRequest {

    /** 厂商配置列表 */
    private List<VendorRequest> vendors;
}
