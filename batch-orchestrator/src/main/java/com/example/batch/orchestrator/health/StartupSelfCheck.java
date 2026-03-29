package com.example.batch.orchestrator.health;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动自检：明确告诉你缺哪张表、缺哪条迁移、以及哪些 schema 不一致。
 *
 * <p>设计目标：不因为自检失败阻断启动；而是把关键信息写到日志里，便于定位。
 */
@Component
public class StartupSelfCheck {

    private static final Logger log = LoggerFactory.getLogger(StartupSelfCheck.class);

    private final DataSource dataSource;
    private final Flyway flyway;

    @Value("${batch.startup-self-check.enabled:true}")
    private boolean enabled;

    public StartupSelfCheck(DataSource dataSource, Flyway flyway) {
        this.dataSource = dataSource;
        this.flyway = flyway;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            return;
        }

        List<String> problems = new ArrayList<>();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        // 1) 校验 Flyway 脚本一致性（校验失败也不直接中断，仅记录原因）
        try {
            flyway.validate();
        } catch (Exception ex) {
            problems.add("Flyway 脚本校验失败：" + ex.getMessage());
        }

        // 2) 校验必要迁移是否已应用
        // 当前 batch_day 关键迁移版本：V31__add_batch_day_support.sql
        try {
            Set<String> applied = Arrays.stream(flyway.info().applied())
                    .map(MigrationInfo::getVersion)
                    .map(v -> v.toString())
                    .collect(Collectors.toSet());
            if (!applied.contains("31")) {
                problems.add("缺少迁移版本：V31__add_batch_day_support.sql（batch_day_instance 相关表可能未创建）。");
            }
        } catch (Exception ex) {
            problems.add("读取 Flyway 已应用迁移信息失败：" + ex.getMessage());
        }

        // 3) 检查必需 schema / 表 / 列
        checkSchemaExists(jdbc, problems, "batch");
        checkSchemaExists(jdbc, problems, "quartz");

        checkTableExists(jdbc, problems, "batch", "batch_day_instance");
        checkTableExists(jdbc, problems, "batch", "business_calendar");

        // batch.business_calendar 关键列（来自 V31__add_batch_day_support.sql）
        checkColumnExists(jdbc, problems, "batch", "business_calendar", "cutoff_time");
        checkColumnExists(jdbc, problems, "batch", "business_calendar", "late_arrival_tolerance_min");
        checkColumnExists(jdbc, problems, "batch", "business_calendar", "sla_offset_min");

        if (problems.isEmpty()) {
            log.info("启动自检通过：schema / 表 / 列 / Flyway 迁移版本均满足预期。");
            return;
        }

        // 统一输出，便于你直接复制到 issue/群里
        log.error("启动自检发现问题（请按下列项逐一处理）：");
        for (String p : problems) {
            log.error(" - {}", p);
        }
    }

    private static void checkSchemaExists(JdbcTemplate jdbc, List<String> problems, String schemaName) {
        Integer cnt = jdbc.queryForObject(
                """
                        select count(*) from information_schema.schemata
                        where schema_name = ?
                        """,
                Integer.class,
                schemaName
        );
        if (cnt == null || cnt == 0) {
            problems.add("缺少 schema：`" + schemaName + "`（请确认数据库初始化或 Flyway 迁移已创建）。");
        }
    }

    private static void checkTableExists(JdbcTemplate jdbc, List<String> problems, String schemaName, String tableName) {
        Integer cnt = jdbc.queryForObject(
                """
                        select count(*)
                          from information_schema.tables
                         where table_schema = ?
                           and table_name = ?
                        """,
                Integer.class,
                schemaName,
                tableName
        );
        if (cnt == null || cnt == 0) {
            problems.add("缺少表：`" + schemaName + "." + tableName + "`（请确认对应迁移版本已应用）。");
        }
    }

    private static void checkColumnExists(JdbcTemplate jdbc, List<String> problems,
                                            String schemaName, String tableName, String columnName) {
        Integer cnt = jdbc.queryForObject(
                """
                        select count(*)
                          from information_schema.columns
                         where table_schema = ?
                           and table_name = ?
                           and column_name = ?
                        """,
                Integer.class,
                schemaName,
                tableName,
                columnName
        );
        if (cnt == null || cnt == 0) {
            problems.add("缺少列：`" + schemaName + "." + tableName + "." + columnName + "`（请确认 V31__add_batch_day_support.sql 已执行）。");
        }
    }
}

