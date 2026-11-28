package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

import java.util.List;

import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;

import jakarta.annotation.Nullable;

public record BatchCreateTenantsResponse(
        List<ConsoleTenantResponse> tenants, @Nullable TenantConfigBatchInitResponse configInit) {}
