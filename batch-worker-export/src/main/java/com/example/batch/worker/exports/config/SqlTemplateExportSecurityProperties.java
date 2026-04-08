package com.example.batch.worker.exports.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SQL 模板导出安全配置，绑定前缀 {@code batch.worker.export.sql-template}。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.export.sql-template")
public class SqlTemplateExportSecurityProperties {

    /**
     * 每次查询的语句超时时间（秒）。
     */
    private int queryTimeoutSeconds = 30;

    /**
     * 单页最大行数上限，防止误配大页造成内存压力。
     */
    private int maxPageSize = 5000;

    /**
     * 模板 SQL 中必须包含的具名参数，用于强制租户隔离。
     */
    private List<String> requiredParams = new ArrayList<>(List.of("tenantId", "batchNo"));

    /**
     * 允许引用的 schema 白名单，空列表表示不限制。
     * 示例：["biz", "ref"] — 引用其他 schema（如 pg_catalog）的表将被拒绝。
     */
    private List<String> allowedSchemas = new ArrayList<>();

    /**
     * 在解析阶段拒绝 {@code SELECT *} / {@code SELECT table.*}。
     */
    private boolean forbidSelectStar = true;

    /**
     * 在首页查询前对基础 SQL 执行 {@code EXPLAIN (FORMAT JSON)} 预检。
     * 默认关闭；在需要检查执行计划代价或预估行数的环境中可开启。
     */
    private boolean explainCheckEnabled = false;

    /**
     * EXPLAIN 预估最大行数上限，&lt;= 0 表示不限制。
     * 仅在 {@link #explainCheckEnabled} 为 true 时生效。
     */
    private long maxEstimatedRows = 5_000_000L;

    /**
     * EXPLAIN 预估最大总代价上限，&lt;= 0 表示不限制。
     * 仅在 {@link #explainCheckEnabled} 为 true 时生效。
     */
    private double maxPlanCost = -1;
}
