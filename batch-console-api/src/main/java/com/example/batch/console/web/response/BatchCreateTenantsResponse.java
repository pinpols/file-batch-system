package com.example.batch.console.web.response;

import jakarta.annotation.Nullable;
import java.util.List;

public record BatchCreateTenantsResponse(
    List<ConsoleTenantResponse> tenants, @Nullable TenantConfigBatchInitResponse configInit) {}
