package com.example.batch.console.web.request;

import com.example.batch.common.validation.ValidTenantId;
import lombok.Data;

@Data
public class ForceOfflineWorkerRequest {

    @ValidTenantId
    private String tenantId;
}
