package com.example.batch.worker.imports.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.import.jdbc-mapped")
public class JdbcMappedImportSecurityProperties {

    /**
     * jdbc_mapped_import 允许使用的 schema（默认：仅 {@code biz} 业务 schema）。
     */
    private List<String> allowedSchemas = new ArrayList<>(List.of("biz"));
}
