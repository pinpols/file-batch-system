package io.github.pinpols.batch.console.domain.rbac.application.contract.response;

import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import jakarta.annotation.Nullable;
import java.util.List;

public record BatchCreateTenantsResponse(
    List<ConsoleTenantResponse> tenants, @Nullable TenantConfigBatchInitResponse configInit) {}
