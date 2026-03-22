package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class FileTemplateQueryRequest {

    private String tenantId;
    private String templateCode;
    private String templateType;
    private Boolean enabled;
}
