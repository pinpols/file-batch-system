package com.example.batch.console.domain.query;

import lombok.Data;

@Data
public class ConfigReleaseQuery {

    private String tenantId;
    private String configType;
    private String configKey;
    private String configStatus;
    private Integer versionNo;
}
