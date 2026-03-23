package com.example.batch.console.web.query;

import lombok.Data;

@Data
public class ConfigChangeLogQueryRequest {

    private String tenantId;
    private String configType;
    private String configKey;
    private String changeAction;
}
