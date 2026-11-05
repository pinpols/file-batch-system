package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * ARCH-20261103-01：Console/Trigger/Orchestrator 平台库运行时角色授权脚本在完整迁移后的真实 PostgreSQL 上执行。
 *
 * <p>验证清单内表、序列与函数权限全部生效，Console/Trigger 改写 Orchestrator 所有的运行状态被拒，运行时角色都不能执行
 * DDL，以及重复授权收敛与撤销。业务路径在受限角色下的运行由受限角色演练覆盖。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("控制面平台库授权脚本：清单权限生效、越界写入与 DDL 被拒、重复授权收敛与撤销")
class PlatformControlPlaneRolesSqlIntegrationTest {

  private static final String SCRIPT_IN_CONTAINER = "/tmp/platform-control-plane-roles.sql";
  // 权限不足与非属主执行 DDL 都返回 42501。
  private static final String PERMISSION_DENIED = "42501";

  @Container
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.platform().withInitScript("db/platform-init.sql");

  private static DataSource dataSource;
  private static JdbcTemplate jdbc;
  private static JsonNode manifest;

  @BeforeAll
  static void migrateDisposableDatabase() throws Exception {
    dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .defaultSchema("batch")
        .schemas("batch", "quartz")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load()
        .migrate();
    jdbc = new JdbcTemplate(dataSource);
    Path repository = Path.of(System.getProperty("user.dir")).getParent();
    Path script = repository.resolve("scripts/db/platform-control-plane-roles.sql");
    POSTGRES.copyFileToContainer(MountableFile.forHostPath(script.toString()), SCRIPT_IN_CONTAINER);
    manifest = new ObjectMapper()
        .readTree(
            repository.resolve("scripts/db/platform-control-plane-grants.json").toFile());
  }

  @Test
  @DisplayName("三个模块授权后清单内表、列、序列与函数权限生效，重复执行结果不变")
  void shouldGrantManifestPrivileges_whenAppliedTwice() {
    Map<String, String> roles = createRoles();

    ExecResult first = runScript(roles, "grant");
    assertThat(first.getExitCode()).as(first.getStderr()).isZero();
    ExecResult second = runScript(roles, "grant");
    assertThat(second.getExitCode()).as(second.getStderr()).isZero();

    String tablePrivilegeSql = "SELECT has_table_privilege(?, ?, ?)";
    String columnPrivilegeSql = "SELECT has_column_privilege(?, ?, ?, 'UPDATE')";
    String sequencePrivilegeSql = """
        SELECT bool_and(has_sequence_privilege(?, sequence_name, 'USAGE'))
          FROM (SELECT pg_get_serial_sequence(?, attname) AS sequence_name
                  FROM pg_attribute
                 WHERE attrelid = CAST(? AS regclass) AND attnum > 0 AND NOT attisdropped) sequences
         WHERE sequence_name IS NOT NULL
        """;
    String functionPrivilegeSql = """
        SELECT bool_and(has_function_privilege(?, p.oid, 'EXECUTE'))
          FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname || '.' || p.proname = ?
        """;
    for (Map.Entry<String, String> module : roles.entrySet()) {
      JsonNode spec = manifest.path("modules").path(module.getKey());
      for (Map.Entry<String, JsonNode> table : spec.path("tables").properties()) {
        for (JsonNode privilege : table.getValue().path("privileges")) {
          Boolean granted = jdbc.queryForObject(
              tablePrivilegeSql,
              Boolean.class,
              module.getValue(),
              table.getKey(),
              privilege.asText());
          assertThat(granted)
              .as("%s %s %s", module.getKey(), privilege, table.getKey())
              .isTrue();
        }
        for (JsonNode column : table.getValue().path("update_columns")) {
          Boolean granted = jdbc.queryForObject(
              columnPrivilegeSql,
              Boolean.class,
              module.getValue(),
              table.getKey(),
              column.asText());
          assertThat(granted)
              .as("%s UPDATE(%s) %s", module.getKey(), column, table.getKey())
              .isTrue();
        }
        if (table.getValue().path("lock_only").asBoolean()) {
          Boolean lockable = jdbc.queryForObject(
              "SELECT has_any_column_privilege(?, ?, 'UPDATE')",
              Boolean.class,
              module.getValue(),
              table.getKey());
          assertThat(lockable)
              .as("%s lock-only %s", module.getKey(), table.getKey())
              .isTrue();
        }
        if (table.getValue().path("privileges").toString().contains("INSERT")) {
          Boolean sequencesUsable = jdbc.queryForObject(
              sequencePrivilegeSql,
              Boolean.class,
              module.getValue(),
              table.getKey(),
              table.getKey());
          assertThat(sequencesUsable)
              .as("%s sequences of %s", module.getKey(), table.getKey())
              .isNotEqualTo(Boolean.FALSE);
        }
      }
      for (JsonNode function : spec.path("functions")) {
        Boolean executable = jdbc.queryForObject(
            functionPrivilegeSql, Boolean.class, module.getValue(), function.asText());
        assertThat(executable).as("%s EXECUTE %s", module.getKey(), function).isTrue();
      }
    }
  }

  @Test
  @DisplayName("Console/Trigger 改写运行状态与发件箱被拒，运行时角色不能执行 DDL")
  void shouldRejectCrossOwnerWritesAndDdl_whenUsingRuntimeRoles() throws Exception {
    Map<String, String> roles = createRoles();
    ExecResult granted = runScript(roles, "grant");
    assertThat(granted.getExitCode()).as(granted.getStderr()).isZero();

    for (String module : List.of("console", "trigger")) {
      String role = roles.get(module);
      assertDenied(role, "UPDATE batch.job_instance SET tenant_id = tenant_id WHERE false");
      assertDenied(role, "UPDATE batch.job_task SET task_status = task_status WHERE false");
      assertDenied(role, "UPDATE batch.workflow_run SET tenant_id = tenant_id WHERE false");
      assertDenied(role, "DELETE FROM batch.outbox_event WHERE false");
    }
    assertDenied(roles.get("console"), "DELETE FROM batch.trigger_request WHERE false");
    assertDenied(roles.get("trigger"), "UPDATE batch.tenant SET tenant_id = tenant_id WHERE false");
    assertDenied(roles.get("console"), "SELECT 1 FROM quartz.qrtz_locks");

    assertAllowed(
        roles.get("orchestrator"),
        "UPDATE batch.job_instance SET tenant_id = tenant_id WHERE false");
    assertAllowed(
        roles.get("trigger"), "UPDATE quartz.qrtz_locks SET lock_name = lock_name WHERE false");
    assertAllowed(
        roles.get("trigger"), "UPDATE batch.trigger_request SET tenant_id = tenant_id WHERE false");

    for (String role : roles.values()) {
      assertDenied(role, "CREATE TABLE batch.runtime_ddl_probe (id int)");
      assertDenied(role, "ALTER TABLE batch.job_instance ADD COLUMN runtime_ddl_probe int");
      assertDenied(role, "TRUNCATE batch.job_instance");
    }
  }

  @Test
  @DisplayName("撤销后清单权限全部移除；缺少参数、PUBLIC 或超级用户角色时脚本失败")
  void shouldRevokeAndRejectInvalidArguments() {
    Map<String, String> roles = createRoles();
    ExecResult granted = runScript(roles, "grant");
    assertThat(granted.getExitCode()).as(granted.getStderr()).isZero();

    ExecResult revoked = runScript(roles, "revoke");
    assertThat(revoked.getExitCode()).as(revoked.getStderr()).isZero();
    String anyTablePrivilegeSql =
        "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee = ?";
    String anyRoutinePrivilegeSql =
        "SELECT count(*) FROM information_schema.role_routine_grants WHERE grantee = ?";
    for (String role : roles.values()) {
      assertThat(jdbc.queryForObject(anyTablePrivilegeSql, Integer.class, role))
          .as(role)
          .isZero();
      assertThat(jdbc.queryForObject(anyRoutinePrivilegeSql, Integer.class, role))
          .as(role)
          .isZero();
      for (String schema : List.of("batch", "archive", "quartz")) {
        Boolean usage = jdbc.queryForObject(
            "SELECT has_schema_privilege(?, ?, 'USAGE')", Boolean.class, role, schema);
        assertThat(usage).as("%s USAGE %s", role, schema).isFalse();
      }
    }

    String superuser = "control_super_" + suffix();
    jdbc.execute("CREATE ROLE " + quoteIdentifier(superuser) + " NOLOGIN SUPERUSER");
    assertThat(runScript(roles, null).getExitCode()).isNotZero();
    assertThat(runScript(Map.of(), "grant").getExitCode()).isNotZero();
    assertThat(runScript(roles, "unknown").getExitCode()).isNotZero();
    assertThat(runScript(Map.of("console", "PUBLIC"), "grant").getExitCode()).isNotZero();
    assertThat(runScript(Map.of("trigger", "absent_" + suffix()), "grant").getExitCode())
        .isNotZero();
    assertThat(runScript(Map.of("orchestrator", superuser), "grant").getExitCode())
        .isNotZero();
  }

  private static Map<String, String> createRoles() {
    Map<String, String> roles = new LinkedHashMap<>();
    String suffix = suffix();
    for (Map.Entry<String, JsonNode> entry : manifest.path("modules").properties()) {
      String module = entry.getKey();
      // 一个角色名带空格和引号，覆盖脚本对标识符的 %I 引用。
      String role = "console".equals(module)
          ? "control \"console " + suffix
          : "control_" + module + "_" + suffix;
      jdbc.execute("CREATE ROLE " + quoteIdentifier(role) + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
      roles.put(module, role);
    }
    return roles;
  }

  private static void assertDenied(String role, String sql) {
    assertThatThrownBy(() -> executeAs(role, sql))
        .as("%s: %s", role, sql)
        .isInstanceOfSatisfying(
            SQLException.class,
            failure -> assertThat(failure.getSQLState()).isEqualTo(PERMISSION_DENIED));
  }

  private static void assertAllowed(String role, String sql) throws SQLException {
    executeAs(role, sql);
  }

  private static void executeAs(String role, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + quoteIdentifier(role));
      statement.execute(sql);
    }
  }

  private static String suffix() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private static ExecResult runScript(Map<String, String> roles, String action) {
    List<String> command = new ArrayList<>(List.of("psql", "-X", "-v", "ON_ERROR_STOP=1"));
    if (action != null) {
      command.addAll(List.of("-v", "action=" + action));
    }
    roles.forEach((module, role) -> command.addAll(List.of("-v", module + "_role=" + role)));
    command.addAll(List.of(
        "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-f", SCRIPT_IN_CONTAINER));
    try {
      return POSTGRES.execInContainer(command.toArray(String[]::new));
    } catch (Exception failure) {
      throw new IllegalStateException("psql execution failed", failure);
    }
  }
}
