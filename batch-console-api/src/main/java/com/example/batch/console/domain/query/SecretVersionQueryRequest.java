package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class SecretVersionQueryRequest {

    private String tenantId;
    private String secretRef;
    private String secretStatus;
    private Boolean currentVersion;
}
