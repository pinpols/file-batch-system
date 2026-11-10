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
import org.springframework.dao.DataIntegrityViolationException;
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
  private static final String CONTROL_SCRIPT_IN_CONTAINER = "/tmp/platform-control-plane-roles.sql";
  private static final String OWNERSHIP_ROLE_SCRIPT_IN_CONTAINER =
      "/tmp/import-ingress-ownership-role.sql";
  private static final String SCAN_ROLE_SCRIPT_IN_CONTAINER = "/tmp/import-ingress-scan-role.sql";
  private static final String SCAN_MAINTENANCE_SCRIPT_IN_CONTAINER =
      "/tmp/import-ingress-scan-maintenance.sql";
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
    Path controlScript = repository.resolve("scripts/db/platform-control-plane-roles.sql");
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(controlScript.toString()), CONTROL_SCRIPT_IN_CONTAINER);
    Path ownershipRoleScript = repository.resolve("scripts/db/import-ingress-ownership-role.sql");
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(ownershipRoleScript.toString()),
        OWNERSHIP_ROLE_SCRIPT_IN_CONTAINER);
    Path scanRoleScript = repository.resolve("scripts/db/import-ingress-scan-role.sql");
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(scanRoleScript.toString()), SCAN_ROLE_SCRIPT_IN_CONTAINER);
    Path scanMaintenanceScript =
        repository.resolve("scripts/db/import-ingress-scan-maintenance.sql");
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(scanMaintenanceScript.toString()),
        SCAN_MAINTENANCE_SCRIPT_IN_CONTAINER);
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

  @Test
  @DisplayName("扫描协调状态仅供明确授权的 Import 角色读写并可安全撤销")
  void shouldRestrictIngressScanStateToAuthorizedImportRole() throws Exception {
    Map<String, String> roles = createWorkerRoles();
    ExecResult workerGrant = runScript(roles, "grant");
    assertThat(workerGrant.getExitCode()).as(workerGrant.getStderr()).isZero();
    String importRole = roles.get("import");
    String exportRole = roles.get("export");
    for (String role : List.of(importRole, exportRole)) {
      jdbc.execute(
          "GRANT SELECT ON batch.import_ingress_scan_storage_lease, batch.import_ingress_scan_state, "
              + "batch.import_ingress_scan_object TO " + quoteIdentifier(role));
    }

    String scope = UUID.randomUUID().toString().replace("-", "").repeat(2);
    String insertStorageSql =
        "INSERT INTO batch.import_ingress_scan_storage_lease (storage_id) VALUES (?)";
    jdbc.update(insertStorageSql, scope);
    String insertScopeSql = """
        INSERT INTO batch.import_ingress_scan_state (scope_id, storage_id, bucket_name, object_prefix)
        VALUES (?, ?, 'test-bucket', 'tenant-one/')
        """;
    jdbc.update(insertScopeSql, scope, scope);

    // 单独的表权限不足以绕过 FORCE RLS；专属策略生效后才能看到和修改状态。
    assertThat(countAs(importRole, "SELECT count(*) FROM batch.import_ingress_scan_state"))
        .isZero();
    assertThat(countAs(exportRole, "SELECT count(*) FROM batch.import_ingress_scan_state"))
        .isZero();
    assertThat(countAs(importRole, "SELECT count(*) FROM batch.import_ingress_scan_storage_lease"))
        .isZero();
    ExecResult grant = runScanRoleScript(importRole, "grant");
    assertThat(grant.getExitCode()).as(grant.getStderr()).isZero();
    ExecResult repeatGrant = runScanRoleScript(importRole, "grant");
    assertThat(repeatGrant.getExitCode()).as(repeatGrant.getStderr()).isZero();
    assertThat(countAs(importRole, "SELECT count(*) FROM batch.import_ingress_scan_state"))
        .isPositive();
    assertThat(countAs(importRole, "SELECT count(*) FROM batch.import_ingress_scan_storage_lease"))
        .isPositive();
    assertThat(countAs(exportRole, "SELECT count(*) FROM batch.import_ingress_scan_storage_lease"))
        .isZero();

    String insertObjectSql = """
        INSERT INTO batch.import_ingress_scan_object
          (scope_id, object_digest, object_key, size_bytes, first_observed_at, seen_cycle, is_manifest)
        VALUES ('%s', '%s', 'tenant-one/input.csv', 1, clock_timestamp(), 1, false)
        """.formatted(scope, "a".repeat(64));
    assertAllowed(importRole, insertObjectSql);
    assertThat(countAs(importRole, "SELECT count(*) FROM batch.import_ingress_scan_object"))
        .isPositive();
    assertThat(countAs(exportRole, "SELECT count(*) FROM batch.import_ingress_scan_object"))
        .isZero();

    ExecResult revoke = runScanRoleScript(importRole, "revoke");
    assertThat(revoke.getExitCode()).as(revoke.getStderr()).isZero();
    assertDenied(importRole, "SELECT * FROM batch.import_ingress_scan_state");
    assertDenied(importRole, "SELECT * FROM batch.import_ingress_scan_storage_lease");
    assertDenied(importRole, "SELECT * FROM batch.import_ingress_scan_object");
    assertThat(runScanRoleScript("PUBLIC", "grant").getExitCode()).isNotZero();
  }

  @Test
  @DisplayName("归属声明仅供受信 Import 与 Orchestrator 读取，重跑生成授权不丢失读取权限")
  void shouldRetainIngressOwnershipReadPolicy_whenGeneratedGrantsAreReapplied() throws Exception {
    Map<String, String> roles = createWorkerRoles();
    String importRole = roles.get("import");
    String exportRole = roles.get("export");
    String orchestratorRole = "ownership_orchestrator_" + suffix();
    String consoleRole = "ownership_console_" + suffix();
    String triggerRole = "ownership_trigger_" + suffix();
    Map<String, String> controlRoles =
        Map.of("orchestrator", orchestratorRole, "console", consoleRole, "trigger", triggerRole);
    for (String role : controlRoles.values()) {
      jdbc.execute("CREATE ROLE " + quoteIdentifier(role) + " NOLOGIN NOSUPERUSER NOBYPASSRLS");
    }
    assertThat(runScript(roles, "grant").getExitCode()).isZero();
    assertThat(runControlPlaneScript(controlRoles, "grant").getExitCode()).isZero();

    String scope = suffix().repeat(2);
    String insertScopeSql = """
        INSERT INTO batch.import_ingress_ownership
          (scope_id, storage_id, bucket_name, object_prefix, state)
        VALUES (?, ?, 'grant-test', ?, 'ACTIVE')
        """;
    jdbc.update(insertScopeSql, scope, scope, "grant-test/" + scope + "/");
    String insertBindingSql = """
        INSERT INTO batch.import_ingress_ownership_binding
          (scope_id, binding_type, tenant_id, object_prefix, channel_code)
        VALUES (?, 'CHANNEL', 'tenant-a', ?, 'channel-a')
        """;
    jdbc.update(insertBindingSql, scope, "grant-test/" + scope + "/tenant-a/");
    String scopeCountSql =
        "SELECT count(*) FROM batch.import_ingress_ownership WHERE scope_id='" + scope + "'";
    String bindingCountSql =
        "SELECT count(*) FROM batch.import_ingress_ownership_binding WHERE scope_id='" + scope
            + "'";

    // 表级 SELECT 不会绕过 FORCE RLS，专属策略需要 DBA 明确配置。
    assertThat(countAs(importRole, scopeCountSql)).isZero();
    assertThat(countAs(orchestratorRole, scopeCountSql)).isZero();
    for (String role : List.of(exportRole, consoleRole, triggerRole)) {
      assertDenied(role, scopeCountSql);
      assertDenied(role, bindingCountSql);
    }
    for (String role : List.of(importRole, orchestratorRole)) {
      ExecResult grant = runOwnershipRoleScript(role, "grant");
      assertThat(grant.getExitCode()).as(grant.getStderr()).isZero();
      assertThat(countAs(role, scopeCountSql)).isEqualTo(1);
      assertThat(countAs(role, bindingCountSql)).isEqualTo(1);
      assertDenied(role, "UPDATE batch.import_ingress_ownership SET state='RELEASED' WHERE false");
      assertDenied(role, "DELETE FROM batch.import_ingress_ownership_binding WHERE false");
    }
    assertThat(runScript(roles, "grant").getExitCode()).isZero();
    assertThat(runControlPlaneScript(controlRoles, "grant").getExitCode()).isZero();
    for (String role : List.of(importRole, orchestratorRole)) {
      assertThat(countAs(role, scopeCountSql)).isEqualTo(1);
      assertThat(countAs(role, bindingCountSql)).isEqualTo(1);
    }

    String cursorSql = """
        INSERT INTO batch.file_governance_reconcile_cursor (storage_id, object_prefix)
        VALUES ('%s', 'grant-test/')
        """.formatted(scope);
    assertAllowed(orchestratorRole, cursorSql);
    assertAllowed(
        orchestratorRole,
        "UPDATE batch.file_governance_reconcile_cursor SET version=version WHERE false");
    assertDenied(
        orchestratorRole, "DELETE FROM batch.file_governance_reconcile_cursor WHERE false");
    assertDenied(importRole, "SELECT * FROM batch.file_governance_reconcile_cursor");

    for (String role : List.of(importRole, orchestratorRole)) {
      ExecResult revoked = runOwnershipRoleScript(role, "revoke");
      assertThat(revoked.getExitCode()).as(revoked.getStderr()).isZero();
      assertDenied(role, scopeCountSql);
      assertDenied(role, bindingCountSql);
    }
  }

  @Test
  @DisplayName("扫描协调状态约束阻止无效租约和同范围重复对象摘要")
  void shouldEnforceScanLeaseAndObjectIdentityConstraints() {
    String scope = UUID.randomUUID().toString().replace("-", "").repeat(2);
    String insertStorageSql =
        "INSERT INTO batch.import_ingress_scan_storage_lease (storage_id) VALUES (?)";
    jdbc.update(insertStorageSql, scope);
    String insertScopeSql = """
        INSERT INTO batch.import_ingress_scan_state (scope_id, storage_id, bucket_name, object_prefix)
        VALUES (?, ?, 'test-bucket', 'tenant-one/')
        """;
    jdbc.update(insertScopeSql, scope, scope);
    String invalidLeaseSql = """
        UPDATE batch.import_ingress_scan_state SET lease_owner = ? WHERE scope_id = ?
        """;
    assertThatThrownBy(() -> jdbc.update(invalidLeaseSql, UUID.randomUUID(), scope))
        .isInstanceOf(DataIntegrityViolationException.class);

    String insertObjectSql = """
        INSERT INTO batch.import_ingress_scan_object
          (scope_id, object_digest, object_key, size_bytes, first_observed_at, seen_cycle, is_manifest)
        VALUES (?, ?, ?, 1, clock_timestamp(), 1, false)
        """;
    String digest = "a".repeat(64);
    jdbc.update(insertObjectSql, scope, digest, "tenant-one/first.csv");
    assertThatThrownBy(() -> jdbc.update(insertObjectSql, scope, digest, "tenant-one/second.csv"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("扫描范围维护拒绝活动租约并可分批、幂等切换，不影响其他范围")
  void shouldMaintainScanScopeInBoundedBatches() throws Exception {
    String storage = suffix().repeat(2);
    String scope = suffix().repeat(2);
    String otherScope = suffix().repeat(2);
    String insertStorageSql =
        "INSERT INTO batch.import_ingress_scan_storage_lease (storage_id) VALUES (?)";
    jdbc.update(insertStorageSql, storage);
    String insertScopeSql = """
        INSERT INTO batch.import_ingress_scan_state
          (scope_id, storage_id, bucket_name, object_prefix, config_digest)
        VALUES (?, ?, 'test-bucket', ?, ?)
        """;
    jdbc.update(insertScopeSql, scope, storage, "tenant-one/", "a".repeat(64));
    jdbc.update(insertScopeSql, otherScope, storage, "tenant-two/", "a".repeat(64));
    String insertObjectSql = """
        INSERT INTO batch.import_ingress_scan_object
          (scope_id, object_digest, object_key, size_bytes, first_observed_at, seen_cycle, is_manifest)
        VALUES (?, ?, ?, 1, clock_timestamp(), 1, false)
        """;
    for (int index = 0; index < 3; index++) {
      jdbc.update(insertObjectSql, scope, "%064x".formatted(index), "input-" + index);
    }
    jdbc.update(insertObjectSql, otherScope, "a".repeat(64), "other-input");

    assertThat(runScanMaintenance(scope, null, null).getExitCode()).isZero();
    String countSql = "SELECT count(*) FROM batch.import_ingress_scan_object WHERE scope_id = ?";
    assertThat(jdbc.queryForObject(countSql, Long.class, scope)).isEqualTo(3L);
    String occupySql = """
        UPDATE batch.import_ingress_scan_storage_lease
           SET lease_owner = ?, lease_until = clock_timestamp() + interval '5 minutes'
         WHERE storage_id = ?
        """;
    jdbc.update(occupySql, UUID.randomUUID(), storage);
    assertThat(runScanMaintenance(scope, "begin", null).getExitCode()).isNotZero();
    String releaseSql = """
        UPDATE batch.import_ingress_scan_storage_lease SET lease_owner = NULL, lease_until = NULL
         WHERE storage_id = ?
        """;
    jdbc.update(releaseSql, storage);
    assertThat(runScanMaintenance(scope, "begin", null).getExitCode()).isZero();
    assertThat(runScanMaintenance(scope, "begin", null).getExitCode()).isZero();
    assertThat(runScanMaintenance(scope, "clear-page", null).getExitCode()).isZero();
    assertThat(jdbc.queryForObject(countSql, Long.class, scope)).isEqualTo(1L);
    assertThat(runScanMaintenance(scope, "activate", null).getExitCode()).isNotZero();
    assertThat(runScanMaintenance(scope, "clear-page", null).getExitCode()).isZero();
    assertThat(runScanMaintenance(scope, "activate", null).getExitCode()).isZero();
    assertThat(runScanMaintenance(scope, "activate", null).getExitCode()).isZero();
    assertThat(jdbc.queryForObject(countSql, Long.class, otherScope)).isEqualTo(1L);
    String stateSql = "SELECT phase FROM batch.import_ingress_scan_state WHERE scope_id = ?";
    assertThat(jdbc.queryForObject(stateSql, String.class, scope)).isEqualTo("DISCOVER");

    Map<String, String> roles = createWorkerRoles();
    assertThat(runScript(roles, "grant").getExitCode()).isZero();
    assertThat(runScanRoleScript(roles.get("import"), "grant").getExitCode()).isZero();
    ExecResult denied = runScanMaintenance(scope, "begin", roles.get("import"));
    assertThat(denied.getExitCode()).isNotZero();
    assertThat(denied.getStderr()).contains("仅扫描状态表的所有者");
    assertThat(jdbc.queryForObject(stateSql, String.class, scope)).isEqualTo("DISCOVER");
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

  private static long countAs(String role, String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SET ROLE " + quoteIdentifier(role));
      try (var result = statement.executeQuery(sql)) {
        result.next();
        return result.getLong(1);
      }
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

  private static ExecResult runScanRoleScript(String role, String action) {
    try {
      return POSTGRES.execInContainer(
          "psql",
          "-X",
          "-v",
          "ON_ERROR_STOP=1",
          "-v",
          "import_role=" + role,
          "-v",
          "action=" + action,
          "-U",
          POSTGRES.getUsername(),
          "-d",
          POSTGRES.getDatabaseName(),
          "-f",
          SCAN_ROLE_SCRIPT_IN_CONTAINER);
    } catch (Exception failure) {
      throw new IllegalStateException("psql execution failed", failure);
    }
  }

  private static ExecResult runControlPlaneScript(Map<String, String> roles, String action) {
    List<String> command =
        new ArrayList<>(List.of("psql", "-X", "-v", "ON_ERROR_STOP=1", "-v", "action=" + action));
    roles.forEach((module, role) -> command.addAll(List.of("-v", module + "_role=" + role)));
    command.addAll(List.of(
        "-U",
        POSTGRES.getUsername(),
        "-d",
        POSTGRES.getDatabaseName(),
        "-f",
        CONTROL_SCRIPT_IN_CONTAINER));
    try {
      return POSTGRES.execInContainer(command.toArray(String[]::new));
    } catch (Exception failure) {
      throw new IllegalStateException("psql execution failed", failure);
    }
  }

  private static ExecResult runOwnershipRoleScript(String role, String action) {
    try {
      return POSTGRES.execInContainer(
          "psql",
          "-X",
          "-v",
          "ON_ERROR_STOP=1",
          "-v",
          "runtime_role=" + role,
          "-v",
          "action=" + action,
          "-U",
          POSTGRES.getUsername(),
          "-d",
          POSTGRES.getDatabaseName(),
          "-f",
          OWNERSHIP_ROLE_SCRIPT_IN_CONTAINER);
    } catch (Exception failure) {
      throw new IllegalStateException("psql execution failed", failure);
    }
  }

  private static ExecResult runScanMaintenance(String scope, String action, String role) {
    List<String> command = new ArrayList<>(List.of(
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-v",
        "scope_id=" + scope,
        "-v",
        "max_rows=2",
        "-U",
        POSTGRES.getUsername(),
        "-d",
        POSTGRES.getDatabaseName()));
    if (action != null) {
      command.addAll(List.of("-v", "action=" + action));
    }
    if (role != null) {
      command.addAll(List.of("-c", "SET ROLE " + quoteIdentifier(role)));
    }
    command.addAll(List.of("-f", SCAN_MAINTENANCE_SCRIPT_IN_CONTAINER));
    try {
      return POSTGRES.execInContainer(command.toArray(String[]::new));
    } catch (Exception failure) {
      throw new IllegalStateException("psql execution failed", failure);
    }
  }
}
