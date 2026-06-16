package com.example.batch.console.domain.rbac.web.response;

import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 一键建租户编排结果:建租户 → (可选)复制默认配置 → 就绪自检,一次调用闭环返回。
 *
 * @param tenant 新建租户
 * @param configInit 配置复制结果;未指定 initConfigFrom 时为 null(不出现在 JSON)
 * @param readiness 建完(并复制配置后)的就绪自检结果
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProvisionTenantResponse(
    ConsoleTenantResponse tenant,
    TenantConfigBatchInitResponse configInit,
    TenantReadinessResponse readiness) {}
