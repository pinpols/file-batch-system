package com.example.batch.common.health;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 声明式启动自检：在 {@code ApplicationReadyEvent} 后校验 schema / 表 / 列与（可选）Flyway 状态，仅写日志、不阻断启动。
 */
@Data
@ConfigurationProperties(prefix = "batch.startup-self-check")
public class BatchStartupSelfCheckProperties {

    private boolean enabled = true;

    /** 日志前缀语境，如 {@code orchestrator}、{@code trigger}。 */
    private String contextName = "应用";

    /** 若存在 Flyway bean，是否在启动时执行 {@link org.flywaydb.core.Flyway#validate()}。 */
    private boolean flywayValidate = false;

    /** 要求已应用的 Flyway 版本号（与 {@link org.flywaydb.core.api.MigrationInfo#getVersion()} 的字符串形式一致，如 {@code 31}）。 */
    private List<String> requiredFlywayVersions = new ArrayList<>();

    private List<String> schemas = new ArrayList<>();

    private List<TableCheck> tables = new ArrayList<>();

    private List<ColumnCheck> columns = new ArrayList<>();

    /**
     * 是否检查 Quartz JDBC JobStore 标准表（在 {@link #quartzSchema} 下）。
     */
    private boolean quartzStandardTables = false;

    private String quartzSchema = "quartz";

    @Data
    public static class TableCheck {
        private String schema;
        private String name;
    }

    @Data
    public static class ColumnCheck {
        private String schema;
        private String table;
        private String name;
        /** 可选：写入问题描述，提示对应迁移脚本。 */
        private String hint;
    }
}
