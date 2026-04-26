package com.example.batch.common.health;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;

/** 通用启动自检：检查项完全由 {@link BatchStartupSelfCheckProperties} 配置，避免各模块复制 JDBC 校验逻辑。 */
@Slf4j
public class BatchStartupSelfCheck {

  private static final List<String> QUARTZ_STANDARD_TABLES =
      List.of(
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
          "qrtz_locks");

  private final DataSource dataSource;
  private final BatchStartupSelfCheckProperties properties;
  private final ObjectProvider<Flyway> flyway;

  public BatchStartupSelfCheck(
      DataSource dataSource,
      BatchStartupSelfCheckProperties properties,
      ObjectProvider<Flyway> flyway) {
    this.dataSource = dataSource;
    this.properties = properties;
    this.flyway = flyway;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (!properties.isEnabled() || !hasAnyCheck()) {
      return;
    }

    List<String> problems = new ArrayList<>();
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    Flyway fw = flyway.getIfAvailable();

    if (properties.isFlywayValidate()) {
      if (fw == null) {
        problems.add("已开启 flyway-validate，但当前上下文不存在 Flyway bean，无法执行校验。");
      } else {
        try {
          fw.validate();
        } catch (Exception ex) {
          problems.add("Flyway 脚本校验失败：" + ex.getMessage());
        }
      }
    }

    if (!properties.getRequiredFlywayVersions().isEmpty()) {
      if (fw == null) {
        problems.add("配置了 required-flyway-versions，但当前上下文不存在 Flyway bean，无法读取已应用迁移。");
      } else {
        try {
          Set<String> applied =
              Arrays.stream(appliedFlywayVersionsFrom(fw)).collect(Collectors.toSet());
          for (String version : properties.getRequiredFlywayVersions()) {
            if (!applied.contains(version)) {
              problems.add("缺少迁移版本：V" + version + "（请确认对应脚本已应用）。");
            }
          }
        } catch (Exception ex) {
          problems.add("读取 Flyway 已应用迁移信息失败：" + ex.getMessage());
        }
      }
    }

    for (String schemaName : properties.getSchemas()) {
      checkSchemaExists(jdbc, problems, schemaName);
    }

    for (BatchStartupSelfCheckProperties.TableCheck t : properties.getTables()) {
      if (t.getSchema() == null || t.getName() == null) {
        problems.add("tables 配置项缺少 schema 或 name，请检查 batch.startup-self-check.tables。");
        continue;
      }
      checkTableExists(jdbc, problems, t.getSchema(), t.getName());
    }

    for (BatchStartupSelfCheckProperties.ColumnCheck c : properties.getColumns()) {
      if (c.getSchema() == null || c.getTable() == null || c.getName() == null) {
        problems.add("columns 配置项缺少 schema、table 或 name，请检查 batch.startup-self-check.columns。");
        continue;
      }
      checkColumnExists(jdbc, problems, c.getSchema(), c.getTable(), c.getName(), c.getHint());
    }

    if (properties.isQuartzStandardTables()) {
      String qs = properties.getQuartzSchema();
      checkSchemaExists(jdbc, problems, qs);
      for (String tableName : QUARTZ_STANDARD_TABLES) {
        checkTableExists(jdbc, problems, qs, tableName);
      }
    }

    String ctx = properties.getContextName();
    if (problems.isEmpty()) {
      log.info("启动自检通过（{}）：配置项对应的 schema / 表 / 列 / Flyway 均满足预期。", ctx);
      return;
    }

    log.error("启动自检发现问题（{}）（请按下列项逐一处理）：", ctx);
    for (String p : problems) {
      log.error(" - {}", p);
    }
  }

  private static String[] appliedFlywayVersionsFrom(Flyway fw) {
    MigrationInfo[] applied = fw.info().applied();
    String[] versions = new String[applied.length];
    for (int i = 0; i < applied.length; i++) {
      var version = applied[i].getVersion();
      versions[i] = version == null ? "" : version.getVersion();
    }
    return versions;
  }

  private boolean hasAnyCheck() {
    if (properties.isFlywayValidate()) {
      return true;
    }
    if (!properties.getRequiredFlywayVersions().isEmpty()) {
      return true;
    }
    if (!properties.getSchemas().isEmpty()) {
      return true;
    }
    if (!properties.getTables().isEmpty()) {
      return true;
    }
    if (!properties.getColumns().isEmpty()) {
      return true;
    }
    return properties.isQuartzStandardTables();
  }

  private static void checkSchemaExists(
      JdbcTemplate jdbc, List<String> problems, String schemaName) {
    Integer cnt =
        jdbc.queryForObject(
            """
            select count(*) from information_schema.schemata
            where schema_name = ?
            """,
            Integer.class,
            schemaName);
    if (cnt == null || cnt == 0) {
      problems.add("缺少 schema：`" + schemaName + "`（请确认数据库初始化或 Flyway 迁移已创建）。");
    }
  }

  private static void checkTableExists(
      JdbcTemplate jdbc, List<String> problems, String schemaName, String tableName) {
    Integer cnt =
        jdbc.queryForObject(
            """
            select count(*)
              from information_schema.tables
             where table_schema = ?
               and table_name = ?
            """,
            Integer.class,
            schemaName,
            tableName);
    if (cnt == null || cnt == 0) {
      problems.add("缺少表：`" + schemaName + "." + tableName + "`（请确认对应迁移版本已应用）。");
    }
  }

  private static void checkColumnExists(
      JdbcTemplate jdbc,
      List<String> problems,
      String schemaName,
      String tableName,
      String columnName,
      String hint) {
    Integer cnt =
        jdbc.queryForObject(
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
            columnName);
    if (cnt == null || cnt == 0) {
      String suffix = hint == null || hint.isBlank() ? "" : "（请确认 " + hint + " 已执行）";
      problems.add("缺少列：`" + schemaName + "." + tableName + "." + columnName + "`" + suffix + "。");
    }
  }
}
