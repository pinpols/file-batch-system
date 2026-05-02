package com.example.batch.console.web.response.auth;

import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse;
import jakarta.annotation.Nullable;
import java.util.List;

public record BatchCreateTenantsResponse(
    List<ConsoleTenantResponse> tenants, @Nullable TenantConfigBatchInitResponse configInit) {}
