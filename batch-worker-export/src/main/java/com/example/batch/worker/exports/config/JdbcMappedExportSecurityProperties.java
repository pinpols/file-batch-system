package com.example.batch.worker.exports.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.export.jdbc-mapped")
public class JdbcMappedExportSecurityProperties {

    private List<String> allowedSchemas = new ArrayList<>(List.of("biz"));
}
