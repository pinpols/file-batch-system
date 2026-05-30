package com.example.batch.console.domain.rbac.web.response;

import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse;
import jakarta.annotation.Nullable;
import java.util.List;

public record BatchCreateTenantsResponse(
    List<ConsoleTenantResponse> tenants, @Nullable TenantConfigBatchInitResponse configInit) {}
