package com.example.batch.worker.exports.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.export.sql-template")
public class SqlTemplateExportSecurityProperties {

    /**
     * Statement timeout for each query execution.
     */
    private int queryTimeoutSeconds = 30;

    /**
     * Hard cap to avoid accidental huge pages.
     */
    private int maxPageSize = 5000;

    /**
     * Required named parameters in template SQL to enforce tenant isolation.
     */
    private List<String> requiredParams = new ArrayList<>(List.of("tenantId", "batchNo"));

    /**
     * Allowed schema names for table references. Empty list means all schemas are allowed.
     * Example: ["biz", "ref"] — tables in other schemas (e.g. pg_catalog) will be rejected.
     */
    private List<String> allowedSchemas = new ArrayList<>();

    /**
     * Reject {@code SELECT *} / {@code SELECT table.*} at parse time.
     */
    private boolean forbidSelectStar = true;

    /**
     * Run {@code EXPLAIN (FORMAT JSON)} on the base SQL before the first page fetch.
     * Disabled by default; enable in environments where you want cost/row estimates checked.
     */
    private boolean explainCheckEnabled = false;

    /**
     * Maximum estimated row count returned by EXPLAIN. <= 0 means no limit.
     * Only used when {@link #explainCheckEnabled} is true.
     */
    private long maxEstimatedRows = 5_000_000L;

    /**
     * Maximum estimated total cost returned by EXPLAIN. <= 0 means no limit.
     * Only used when {@link #explainCheckEnabled} is true.
     */
    private double maxPlanCost = -1;
}
