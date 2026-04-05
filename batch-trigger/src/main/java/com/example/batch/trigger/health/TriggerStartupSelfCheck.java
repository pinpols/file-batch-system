package com.example.batch.trigger.health;

import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Trigger 启动自检：主要检查 Quartz JDBC JobStore 所需的 quartz.QRTZ_* 表是否已初始化。
 *
 * <p>该自检只写日志（默认不 fail-fast），用于联调环境快速发现“schema 有了但表缺失”的问题。
 */
@Component
@RequiredArgsConstructor
public class TriggerStartupSelfCheck {

    private static final Logger log = LoggerFactory.getLogger(TriggerStartupSelfCheck.class);

    private final DataSource dataSource;

    @Value("${batch.trigger.startup-self-check.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            return;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<String> problems = new ArrayList<>();

        checkSchemaExists(jdbc, problems, "quartz");
        checkQuartzTables(jdbc, problems);

        if (problems.isEmpty()) {
            log.info("trigger 启动自检通过：Quartz schema/tables 满足预期。");
            return;
        }
        log.error("trigger 启动自检发现问题（会影响 Quartz JobStore）：");
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
            problems.add("缺少 schema：`" + schemaName + "`（Flyway 未执行或数据库初始化不完整）。");
        }
    }

    private static void checkQuartzTables(JdbcTemplate jdbc, List<String> problems) {
        List<String> quartzTables = List.of(
                "qrtz_job_details",
                "qrtz_triggers",
                "qrtz_simple_triggers",
                "qrtz_cron_triggers",
                "qrtz_simprop_triggers",
                "qrtz_blob_triggers",
                "qrtz_calendars",
                "qrtz_paused_trigger_grps",
                "qrtz_fired_triggers",
                "qrtz_scheduler_state",
                "qrtz_locks"
        );
        for (String table : quartzTables) {
            Integer cnt = jdbc.queryForObject(
                    """
                            select count(*)
                              from information_schema.tables
                             where table_schema = 'quartz'
                               and table_name = ?
                            """,
                    Integer.class,
                    table
            );
            if (cnt == null || cnt == 0) {
                problems.add("缺少表：`quartz." + table + "`（请确认 `V2__create_quartz_tables_postgres_2_5_2.sql` 已执行）。");
            }
        }
    }
}

