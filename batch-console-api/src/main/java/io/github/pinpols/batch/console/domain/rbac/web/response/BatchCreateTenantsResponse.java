package io.github.pinpols.batch.console.domain.rbac.web.response;

import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse;
import jakarta.annotation.Nullable;
import java.util.List;

public record BatchCreateTenantsResponse(
    List<ConsoleTenantResponse> tenants, @Nullable TenantConfigBatchInitResponse configInit) {}
