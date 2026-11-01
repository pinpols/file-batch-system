package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("配置缓存失效队列授权脚本：指定角色、策略冲突和撤销")
class ConfigCacheInvalidationRoleSqlIntegrationTest {

  @Container
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.platform().withInitScript("db/platform-init.sql");

  private static DataSource dataSource;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void migrateDisposableDatabase() {
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
    Path script = repository.resolve("scripts/db/config-cache-invalidation-role.sql");
    POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(script.toString()), "/tmp/config-cache-invalidation-role.sql");
  }

  @Test
  @DisplayName("含空格和引号的指定角色可重复授权及撤销，其他角色保持隔离")
  void shouldGrantAndRevoke_whenRoleContainsSpacesAndQuotes() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String role = "wdi \"role " + suffix;
    String neighbor = "wdi_neighbor_" + suffix;
    createRole(role);
    createRole(neighbor);
    String grantSchemaSql = "GRANT USAGE ON SCHEMA batch TO " + quoteIdentifier(role)
        + ", " + quoteIdentifier(neighbor);
    String grantNeighborSelectSql =
        "GRANT SELECT ON batch.config_cache_invalidation_outbox TO " + quoteIdentifier(neighbor);
    String neighborPolicySql = "CREATE POLICY " + quoteIdentifier("neighbor_" + suffix)
        + " ON batch.config_cache_invalidation_outbox TO " + quoteIdentifier(neighbor)
        + " USING (config_code = 'neighbor')";
    jdbc.execute(grantSchemaSql);
    jdbc.execute(grantNeighborSelectSql);
    jdbc.execute(neighborPolicySql);

    String targetTenant = "target-" + suffix;
    String neighborTenant = "neighbor-" + suffix;
    String insertSql = """
        INSERT INTO batch.config_cache_invalidation_outbox (tenant_id, config_type, config_code)
        VALUES (?, 'job-definition', 'target'), (?, 'job-definition', 'neighbor')
        """;
    jdbc.update(insertSql, targetTenant, neighborTenant);

    ExecResult firstGrant = runScript(role, "grant");
    assertThat(firstGrant.getExitCode()).as(firstGrant.getStderr()).isZero();
    ExecResult secondGrant = runScript(role, "grant");
    assertThat(secondGrant.getExitCode()).as(secondGrant.getStderr()).isZero();
    assertThat(visibleCount(role, targetTenant)).isOne();
    assertThat(visibleCount(role, neighborTenant)).isOne();
    assertThat(visibleCount(neighbor, targetTenant)).isZero();
    assertThat(visibleCount(neighbor, neighborTenant)).isOne();
    String setRoleSql = "SET ROLE " + quoteIdentifier(role);
    String insertAsRoleSql = """
        INSERT INTO batch.config_cache_invalidation_outbox (tenant_id, config_type, config_code)
        VALUES (?, 'job-definition', 'manual')
        """;
    String updateAsRoleSql = """
        UPDATE batch.config_cache_invalidation_outbox SET attempts = 1
         WHERE tenant_id = ? AND config_code = 'manual'
        """;
    String deleteAsRoleSql = """
        DELETE FROM batch.config_cache_invalidation_outbox
         WHERE tenant_id = ? AND config_code = 'manual'
        """;
    try (Connection connection = dataSource.getConnection();
        Statement setRole = connection.createStatement()) {
      setRole.execute(setRoleSql);
      for (String sql : List.of(insertAsRoleSql, updateAsRoleSql, deleteAsRoleSql)) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
          statement.setString(1, targetTenant);
          int affectedRows = statement.executeUpdate();
          assertThat(affectedRows).isOne();
        }
      }
    }

    ExecResult firstRevoke = runScript(role, "revoke");
    assertThat(firstRevoke.getExitCode()).as(firstRevoke.getStderr()).isZero();
    ExecResult secondRevoke = runScript(role, "revoke");
    assertThat(secondRevoke.getExitCode()).as(secondRevoke.getStderr()).isZero();
    String tablePrivilegeSql = """
        SELECT has_table_privilege(?, 'batch.config_cache_invalidation_outbox', 'SELECT')
        """;
    String sequencePrivilegeSql = """
        SELECT has_sequence_privilege(?, 'batch.config_cache_invalidation_outbox_id_seq', 'USAGE')
        """;
    String schemaPrivilegeSql = "SELECT has_schema_privilege(?, 'batch', 'USAGE')";
    Boolean tablePrivilege = jdbc.queryForObject(tablePrivilegeSql, Boolean.class, role);
    Boolean sequencePrivilege = jdbc.queryForObject(sequencePrivilegeSql, Boolean.class, role);
    Boolean schemaPrivilege = jdbc.queryForObject(schemaPrivilegeSql, Boolean.class, role);
    assertThat(tablePrivilege).isFalse();
    assertThat(sequencePrivilege).isFalse();
    assertThat(schemaPrivilege).isTrue();
    assertThat(visibleCount(neighbor, targetTenant)).isZero();
    assertThat(visibleCount(neighbor, neighborTenant)).isOne();
  }

  @Test
  @DisplayName("缺失或非法参数及同名策略冲突时失败，不能覆盖其他角色策略")
  void shouldReject_whenArgumentsAreInvalidOrPolicyConflicts() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String role = "wdi_target_" + suffix;
    String neighbor = "wdi_other_" + suffix;
    String withoutSchema = "wdi_unprovisioned_" + suffix;
    createRole(role);
    createRole(neighbor);
    createRole(withoutSchema);
    String grantSchemaSql = "GRANT USAGE ON SCHEMA batch TO " + quoteIdentifier(role)
        + ", " + quoteIdentifier(neighbor);
    jdbc.execute(grantSchemaSql);

    ExecResult missingRole = runScript(null, "grant");
    ExecResult missingAction = runScript(role, null);
    ExecResult publicRole = runScript("PUBLIC", "grant");
    ExecResult absentRole = runScript("absent_" + suffix, "grant");
    ExecResult invalidAction = runScript(role, "unknown");
    ExecResult missingSchema = runScript(withoutSchema, "grant");
    assertThat(missingRole.getExitCode()).isNotZero();
    assertThat(missingAction.getExitCode()).isNotZero();
    assertThat(publicRole.getExitCode()).isNotZero();
    assertThat(absentRole.getExitCode()).isNotZero();
    assertThat(invalidAction.getExitCode()).isNotZero();
    assertThat(missingSchema.getExitCode()).isNotZero();

    ExecResult granted = runScript(role, "grant");
    assertThat(granted.getExitCode()).as(granted.getStderr()).isZero();
    String policyNameSql = """
        SELECT p.polname FROM pg_policy p
         WHERE p.polrelid = 'batch.config_cache_invalidation_outbox'::regclass
           AND p.polroles = ARRAY[(SELECT oid FROM pg_roles WHERE rolname = ?)]::oid[]
        """;
    String policyName = jdbc.queryForObject(policyNameSql, String.class, role);
    String changePolicySql = "ALTER POLICY " + quoteIdentifier(policyName)
        + " ON batch.config_cache_invalidation_outbox TO " + quoteIdentifier(neighbor);
    jdbc.execute(changePolicySql);

    ExecResult conflictingGrant = runScript(role, "grant");
    ExecResult conflictingRevoke = runScript(role, "revoke");
    assertThat(conflictingGrant.getExitCode()).isNotZero();
    assertThat(conflictingRevoke.getExitCode()).isNotZero();
    String policyRoleSql = """
        SELECT CAST(? AS name) = ANY(roles) FROM pg_policies
         WHERE schemaname = 'batch' AND tablename = 'config_cache_invalidation_outbox'
           AND policyname = ?
        """;
    String privilegeSql = """
        SELECT has_table_privilege(?, 'batch.config_cache_invalidation_outbox', 'SELECT')
        """;
    Boolean policyStillBelongsToNeighbor =
        jdbc.queryForObject(policyRoleSql, Boolean.class, neighbor, policyName);
    Boolean privilegeStillPresent = jdbc.queryForObject(privilegeSql, Boolean.class, role);
    assertThat(policyStillBelongsToNeighbor).isTrue();
    assertThat(privilegeStillPresent).isTrue();
  }

  private static void createRole(String role) {
    String createRoleSql = "CREATE ROLE " + quoteIdentifier(role) + " NOLOGIN NOSUPERUSER NOBYPASSRLS";
    jdbc.execute(createRoleSql);
  }

  private static String quoteIdentifier(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  private static int visibleCount(String role, String tenant) throws Exception {
    String setRoleSql = "SET ROLE " + quoteIdentifier(role);
    String countSql = """
        SELECT count(*) FROM batch.config_cache_invalidation_outbox WHERE tenant_id = ?
        """;
    try (Connection connection = dataSource.getConnection();
        Statement setRole = connection.createStatement()) {
      setRole.execute(setRoleSql);
      try (PreparedStatement count = connection.prepareStatement(countSql)) {
        count.setString(1, tenant);
        try (ResultSet result = count.executeQuery()) {
          result.next();
          return result.getInt(1);
        }
      }
    }
  }

  private static ExecResult runScript(String role, String action) throws Exception {
    List<String> command = new ArrayList<>(List.of("psql", "-X", "-v", "ON_ERROR_STOP=1"));
    if (role != null) {
      command.add("-v");
      command.add("console_role=" + role);
    }
    if (action != null) {
      command.add("-v");
      command.add("action=" + action);
    }
    command.addAll(List.of(
        "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(),
        "-f", "/tmp/config-cache-invalidation-role.sql"));
    return POSTGRES.execInContainer(command.toArray(String[]::new));
  }
}
