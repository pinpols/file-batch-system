package com.example.batch.common.health;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.mapper.InformationSchemaMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

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

  private final InformationSchemaMapper informationSchemaMapper;
  private final BatchStartupSelfCheckProperties properties;
  private final ObjectProvider<Flyway> flyway;

  public BatchStartupSelfCheck(
      InformationSchemaMapper informationSchemaMapper,
      BatchStartupSelfCheckProperties properties,
      ObjectProvider<Flyway> flyway) {
    this.informationSchemaMapper = informationSchemaMapper;
    this.properties = properties;
    this.flyway = flyway;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady() {
    if (!properties.isEnabled() || !hasAnyCheck()) {
      return;
    }

    List<String> problems = new ArrayList<>();
    Flyway fw = flyway.getIfAvailable();

    runFlywayValidate(fw, problems);
    runRequiredFlywayVersions(fw, problems);
    runConfiguredSchemas(problems);
    runConfiguredTables(problems);
    runConfiguredColumns(problems);
    runQuartzStandardTables(problems);

    reportResult(problems);
  }

  private void runFlywayValidate(Flyway fw, List<String> problems) {
    if (!properties.isFlywayValidate()) {
      return;
    }
    if (fw == null) {
      problems.add("已开启 flyway-validate，但当前上下文不存在 Flyway bean，无法执行校验。");
      return;
    }
    try {
      fw.validate();
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(BatchStartupSelfCheck.class, "catch:Exception", ex);

      problems.add("Flyway 脚本校验失败：" + ex.getMessage());
    }
  }

  private void runRequiredFlywayVersions(Flyway fw, List<String> problems) {
    if (properties.getRequiredFlywayVersions().isEmpty()) {
      return;
    }
    if (fw == null) {
      problems.add("配置了 required-flyway-versions，但当前上下文不存在 Flyway bean，无法读取已应用迁移。");
      return;
    }
    try {
      Set<String> applied =
          Arrays.stream(appliedFlywayVersionsFrom(fw)).collect(Collectors.toSet());
      for (String version : properties.getRequiredFlywayVersions()) {
        if (!applied.contains(version)) {
          problems.add("缺少迁移版本：V" + version + "（请确认对应脚本已应用）。");
        }
      }
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(BatchStartupSelfCheck.class, "catch:Exception", ex);

      problems.add("读取 Flyway 已应用迁移信息失败：" + ex.getMessage());
    }
  }

  private void runConfiguredSchemas(List<String> problems) {
    for (String schemaName : properties.getSchemas()) {
      checkSchemaExists(problems, schemaName);
    }
  }

  private void runConfiguredTables(List<String> problems) {
    for (BatchStartupSelfCheckProperties.TableCheck t : properties.getTables()) {
      if (t.getSchema() == null || t.getName() == null) {
        problems.add("tables 配置项缺少 schema 或 name，请检查 batch.startup-self-check.tables。");
        continue;
      }
      checkTableExists(problems, t.getSchema(), t.getName());
    }
  }

  private void runConfiguredColumns(List<String> problems) {
    for (BatchStartupSelfCheckProperties.ColumnCheck c : properties.getColumns()) {
      if (c.getSchema() == null || c.getTable() == null || c.getName() == null) {
        problems.add("columns 配置项缺少 schema、table 或 name，请检查 batch.startup-self-check.columns。");
        continue;
      }
      checkColumnExists(problems, c.getSchema(), c.getTable(), c.getName(), c.getHint());
    }
  }

  private void runQuartzStandardTables(List<String> problems) {
    if (!properties.isQuartzStandardTables()) {
      return;
    }
    String qs = properties.getQuartzSchema();
    checkSchemaExists(problems, qs);
    for (String tableName : QUARTZ_STANDARD_TABLES) {
      checkTableExists(problems, qs, tableName);
    }
  }

  private void reportResult(List<String> problems) {
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

  private void checkSchemaExists(List<String> problems, String schemaName) {
    if (informationSchemaMapper.countSchema(schemaName) == 0) {
      problems.add("缺少 schema：`" + schemaName + "`（请确认数据库初始化或 Flyway 迁移已创建）。");
    }
  }

  private void checkTableExists(List<String> problems, String schemaName, String tableName) {
    if (informationSchemaMapper.countTable(schemaName, tableName) == 0) {
      problems.add("缺少表：`" + schemaName + "." + tableName + "`（请确认对应迁移版本已应用）。");
    }
  }

  private void checkColumnExists(
      List<String> problems, String schemaName, String tableName, String columnName, String hint) {
    if (informationSchemaMapper.countColumn(schemaName, tableName, columnName) == 0) {
      String suffix = hint == null || hint.isBlank() ? "" : "（请确认 " + hint + " 已执行）";
      problems.add("缺少列：`" + schemaName + "." + tableName + "." + columnName + "`" + suffix + "。");
    }
  }
}
