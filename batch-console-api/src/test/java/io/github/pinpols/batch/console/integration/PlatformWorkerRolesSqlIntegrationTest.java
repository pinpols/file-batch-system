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
 * ARCH-20261103-01：Worker 平台库最小授权脚本在完整迁移后的真实 PostgreSQL 上执行。
 *
 * <p>验证清单内权限全部生效、Orchestrator 所有的状态写入被数据库拒绝、job_task 只能写登记列，以及重复授权收敛与撤销。只校验授权本身；
 * Worker 业务路径在受限角色下的端到端运行仍需目标环境演练。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Worker 平台库授权脚本：清单权限生效、越界写入被拒、重复授权收敛与撤销")
class PlatformWorkerRolesSqlIntegrationTest {

  private static final String SCRIPT_IN_CONTAINER = "/tmp/platform-worker-roles.sql";
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
    Path script = repository.resolve("scripts/db/platform-worker-roles.sql");
    POSTGRES.copyFileToContainer(MountableFile.forHostPath(script.toString()), SCRIPT_IN_CONTAINER);
    manifest = new ObjectMapper()
        .readTree(repository.resolve("scripts/db/platform-worker-grants.json").toFile());
  }

  @Test
  @DisplayName("全部 Worker 授权后清单内表、列与序列权限生效，重复执行结果不变")
  void shouldGrantManifestPrivileges_whenAppliedTwice() {
    Map<String, String> roles = createWorkerRoles();

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
    for (Map.Entry<String, String> worker : roles.entrySet()) {
      for (Map.Entry<String, JsonNode> table :
          manifest.path("workers").path(worker.getKey()).path("tables").properties()) {
        for (JsonNode privilege : table.getValue().path("privileges")) {
          Boolean granted = jdbc.queryForObject(
              tablePrivilegeSql,
              Boolean.class,
              worker.getValue(),
              table.getKey(),
              privilege.asText());
          assertThat(granted)
              .as("%s %s %s", worker.getKey(), privilege, table.getKey())
              .isTrue();
        }
        for (JsonNode column : table.getValue().path("update_columns")) {
          Boolean granted = jdbc.queryForObject(
              columnPrivilegeSql,
              Boolean.class,
              worker.getValue(),
              table.getKey(),
              column.asText());
          assertThat(granted)
              .as("%s UPDATE(%s) %s", worker.getKey(), column, table.getKey())
              .isTrue();
        }
        if (table.getValue().path("lock_only").asBoolean()) {
          Boolean lockable = jdbc.queryForObject(
              "SELECT has_any_column_privilege(?, ?, 'UPDATE')",
              Boolean.class,
              worker.getValue(),
              table.getKey());
          assertThat(lockable)
              .as("%s lock-only %s", worker.getKey(), table.getKey())
              .isTrue();
        }
        boolean inserts = table.getValue().path("privileges").toString().contains("INSERT");
        if (inserts) {
          Boolean sequencesUsable = jdbc.queryForObject(
              sequencePrivilegeSql,
              Boolean.class,
              worker.getValue(),
              table.getKey(),
              table.getKey());
          assertThat(sequencesUsable)
              .as("%s sequences of %s", worker.getKey(), table.getKey())
              .isNotEqualTo(Boolean.FALSE);
        }
      }
    }
  }

  @Test
  @DisplayName("Worker 角色改写调度状态与发件箱被数据库拒绝，job_task 只能写登记列")
  void shouldRejectOrchestratorOwnedWrites_whenUsingWorkerRoles() throws Exception {
    Map<String, String> roles = createWorkerRoles();
    ExecResult granted = runScript(roles, "grant");
    assertThat(granted.getExitCode()).as(granted.getStderr()).isZero();

    for (String role : roles.values()) {
      assertDenied(role, "UPDATE batch.job_instance SET tenant_id = tenant_id WHERE false");
      assertDenied(role, "UPDATE batch.job_partition SET tenant_id = tenant_id WHERE false");
      assertDenied(role, "UPDATE batch.workflow_run SET tenant_id = tenant_id WHERE false");
      assertDenied(role, "UPDATE batch.job_task SET task_status = task_status WHERE false");
      assertDenied(role, "DELETE FROM batch.job_task WHERE false");
      assertDenied(role, "DELETE FROM batch.outbox_event WHERE false");
      assertDenied(role, "DELETE FROM batch.tenant WHERE false");
      // Pipeline 创建对父实例持 FOR KEY SHARE 引用锁，只需“仅锁”列权限。
      assertAllowed(role, "SELECT 1 FROM batch.job_instance WHERE false FOR KEY SHARE");
    }
    assertAllowed(
        roles.get("process"),
        "UPDATE batch.job_task SET process_staging_batch_key = process_staging_batch_key WHERE false");
    assertAllowed(
        roles.get("dispatch"),
        "UPDATE batch.job_task SET external_outcome_resolution_consumed_at ="
            + " external_outcome_resolution_consumed_at WHERE false");
    assertDenied(
        roles.get("import"),
        "UPDATE batch.job_task SET process_staging_batch_key = process_staging_batch_key WHERE false");
  }

  @Test
  @DisplayName("撤销后清单权限全部移除；缺少参数、PUBLIC 或超级用户角色时脚本失败")
  void shouldRevokeAndRejectInvalidArguments() {
    Map<String, String> roles = createWorkerRoles();
    ExecResult granted = runScript(roles, "grant");
    assertThat(granted.getExitCode()).as(granted.getStderr()).isZero();

    ExecResult revoked = runScript(roles, "revoke");
    assertThat(revoked.getExitCode()).as(revoked.getStderr()).isZero();
    String anyTablePrivilegeSql = """
        SELECT count(*) FROM information_schema.role_table_grants WHERE grantee = ?
        """;
    String schemaPrivilegeSql = "SELECT has_schema_privilege(?, 'batch', 'USAGE')";
    for (String role : roles.values()) {
      Integer remaining = jdbc.queryForObject(anyTablePrivilegeSql, Integer.class, role);
      Boolean schemaUsage = jdbc.queryForObject(schemaPrivilegeSql, Boolean.class, role);
      assertThat(remaining).as(role).isZero();
      assertThat(schemaUsage).as(role).isFalse();
    }

    String superuser = "worker_super_" + suffix();
    jdbc.execute("CREATE ROLE " + quoteIdentifier(superuser) + " NOLOGIN SUPERUSER");
    assertThat(runScript(roles, null).getExitCode()).isNotZero();
    assertThat(runScript(Map.of(), "grant").getExitCode()).isNotZero();
    assertThat(runScript(roles, "unknown").getExitCode()).isNotZero();
    assertThat(runScript(Map.of("import", "PUBLIC"), "grant").getExitCode()).isNotZero();
    assertThat(runScript(Map.of("import", "absent_" + suffix()), "grant").getExitCode())
        .isNotZero();
    assertThat(runScript(Map.of("import", superuser), "grant").getExitCode()).isNotZero();
  }

  private static Map<String, String> createWorkerRoles() {
    Map<String, String> roles = new LinkedHashMap<>();
    String suffix = suffix();
    for (Map.Entry<String, JsonNode> entry : manifest.path("workers").properties()) {
      String worker = entry.getKey();
      // 一个角色名带空格和引号，覆盖脚本对标识符的 %I 引用。
      String role =
          "import".equals(worker) ? "worker \"import " + suffix : "worker_" + worker + "_" + suffix;
      jdbc.execute("CREATE ROLE " + quoteIdentifier(role) + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
      roles.put(worker, role);
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
    roles.forEach((worker, role) -> command.addAll(List.of("-v", worker + "_role=" + role)));
    command.addAll(List.of(
        "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-f", SCRIPT_IN_CONTAINER));
    try {
      return POSTGRES.execInContainer(command.toArray(String[]::new));
    } catch (Exception failure) {
      throw new IllegalStateException("psql execution failed", failure);
    }
  }
}
