package com.example.batch.worker.imports.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.datasource.business")
public class BusinessDataSourceProperties {

    private String url;
    private String username;
    private String password;
    private String schema;
}
