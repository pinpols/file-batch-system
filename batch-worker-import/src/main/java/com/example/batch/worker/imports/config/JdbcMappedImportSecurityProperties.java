package com.example.batch.worker.imports.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.import.jdbc-mapped")
public class JdbcMappedImportSecurityProperties {

    /**
     * Schemas allowed in jdbc_mapped_import (default: only {@code biz} business schema).
     */
    private List<String> allowedSchemas = new ArrayList<>(List.of("biz"));
}
