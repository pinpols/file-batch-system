package com.example.batch.console.application;

import com.example.batch.console.web.request.TenantConfigBatchInitRequest;
import com.example.batch.console.web.response.TenantConfigBatchInitResponse;

/**
 * 租户配置批量初始化应用服务。
 *
 * <p>向指定的多个租户批量推送作业定义、工作流定义、流水线定义、文件通道和文件模板配置。 绕过租户隔离守卫，调用方须持有 ROLE_ADMIN 权限。
 */
public interface ConsoleTenantConfigInitApplicationService {

    /**
     * 批量初始化或更新多个租户的配置。
     *
     * @param request 包含目标租户列表与各类配置模板
     * @param operator 操作人标识（来自认证信息）
     * @param batchOperationId 批次操作 ID，用于审计关联
     * @return 每个租户的初始化结果汇总
     */
    TenantConfigBatchInitResponse batchInit(
            TenantConfigBatchInitRequest request, String operator, String batchOperationId);
}
