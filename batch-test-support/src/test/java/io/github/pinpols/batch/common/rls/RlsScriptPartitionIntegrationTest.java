package io.github.pinpols.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/** 需要真实 psql 与独立角色/策略目录，故直接使用每用例隔离的业务 PG，不启动应用或共享平台库。 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("真实 RLS 安装、strict、回滚脚本的父叶覆盖、事务原子性和运行守护")
class RlsScriptPartitionIntegrationTest {

  private static final String INSTALL = "rls-phase-a.sql";
  private static final String STRICT = "rls-phase-a-strict.sql";
  private static final String TRANSITION = "rls-phase-a-rollback-to-transition.sql";
  private static final String WRITER = "batch_business_writer";
  private static final String TEST_PASSWORD = "rls-script-fixture-password";

  @Container
  private final PostgreSQLContainer postgres =
      TestPostgresContainers.business().withInitScript("db/create_biz_tables.sql");

  private JdbcTemplate admin;

  @BeforeEach
  void seedRealBusinessPartitions() {
    final String createProcessStagingProbeSql = """
        create table batch.process_staging_probe partition of batch.process_staging
        for values from ('2026-10-18 00:00:00+00') to ('2026-10-19 00:00:00+00')
        """;
    final String insertCustomerAccountSql = """
        insert into biz.customer_account (tenant_id, customer_no, customer_name) values
          ('tenant-a', 'a-001', 'Customer A'), ('tenant-b', 'b-001', 'Customer B')
        """;
    final String insertProcessStagingSql = """
        insert into batch.process_staging (
          batch_key, row_seq, tenant_id, target_schema, target_table, payload, staged_at
        ) values
          ('a-daily', 1, 'tenant-a', 'biz', 'target', '{}', '2026-10-18 01:00:00+00'),
          ('b-daily', 2, 'tenant-b', 'biz', 'target', '{}', '2026-10-18 01:00:00+00'),
          ('a-default', 3, 'tenant-a', 'biz', 'target', '{}', '2026-10-20 01:00:00+00'),
          ('b-default', 4, 'tenant-b', 'biz', 'target', '{}', '2026-10-20 01:00:00+00')
        """;

    admin = new JdbcTemplate(dataSource(postgres.getUsername(), postgres.getPassword()));
    admin.execute(createProcessStagingProbeSql);
    admin.update(insertCustomerAccountSql);
    admin.update(insertProcessStagingSql);
  }

  @Test
  @DisplayName("真实目录中的父表及每个叶子都可安装/重复安装、回滚、再strict，普通账号读写隔离")
  void shouldApplyRealScriptsToAllTenantRelationsAndPreserveStrictIsolation() throws Exception {
    final String createPolicySql = """
        create policy restrictive_probe on biz.customer_account as restrictive for all to public
        using (true) with check (true)
        """;

    assertScriptSuccess(INSTALL);
    assertScriptSuccess(INSTALL);
    DataSource writerSource = dataSource(WRITER, TEST_PASSWORD);
    assertThat(new BusinessDataSourceRoleChecker(writerSource).check().isClean())
        .isTrue();
    assertThat(new RlsClosedWorldChecker(writerSource, List.of()).check().isClean())
        .isTrue();
    verifyStrictReadsAndHiddenWrites(writerSource);

    assertScriptSuccess(TRANSITION);
    assertThat(new RlsClosedWorldChecker(writerSource, List.of()).check().isClean())
        .isFalse();
    JdbcTemplate writer = new JdbcTemplate(writerSource);
    for (String table : tenantRelations()) {
      final String selectCountSql = """
          select count(*) from %s\
          """.formatted(table);
      assertThat(writer.queryForObject(selectCountSql, Long.class))
          .as("transition direct read: " + table)
          .isEqualTo(admin.queryForObject(selectCountSql, Long.class));
    }
    assertScriptSuccess(STRICT);
    assertScriptSuccess(STRICT);
    assertThat(new RlsClosedWorldChecker(writerSource, List.of()).check().isClean())
        .isTrue();
    verifyStrictReadsAndHiddenWrites(writerSource);

    admin.execute(createPolicySql);
    assertThat(new RlsClosedWorldChecker(writerSource, List.of()).check().isClean())
        .isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {INSTALL, STRICT, TRANSITION})
  @DisplayName("staging策略失败时整个脚本回滚，不留下部分biz策略或新授予的账号")
  void shouldRollbackAllRoleAndPolicyChangesWhenLaterStageFails(String script) throws Exception {
    final String dropProcessStagingSql = """
        drop table batch.process_staging cascade
    """;
    final String createProcessStagingSql = """
        create table batch.process_staging (tenant_id integer not null)
    """;

    if (!INSTALL.equals(script)) {
      assertScriptSuccess(INSTALL);
      if (STRICT.equals(script)) {
        assertScriptSuccess(TRANSITION);
      }
    }
    // 故障在后续 staging 阶段发生，不能只依靠第一个 biz DO 自身的原子性。
    admin.execute(dropProcessStagingSql);
    admin.execute(createProcessStagingSql);
    String before = protectionSnapshot();
    Long writerRolesBefore = writerRoleCount();

    ExecResult result = runScript(script);

    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isNotZero();
    assertThat(protectionSnapshot()).isEqualTo(before);
    assertThat(writerRoleCount()).isEqualTo(writerRolesBefore);
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUPERUSER", "BYPASSRLS"})
  @DisplayName("安装遇到已有可绕过RLS的writer账号时拒绝，不静默授予业务表访问")
  void shouldRejectUnsafeExistingWriterRole(String unsafeAttribute) throws Exception {
    final String createRoleSql = """
        create role %s login password '%s' %s\
        """.formatted(WRITER, TEST_PASSWORD, unsafeAttribute);
    admin.execute(createRoleSql);
    String before = protectionSnapshot();

    ExecResult result = runScript(INSTALL);

    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isNotZero();
    assertThat(protectionSnapshot()).isEqualTo(before);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADDITIONAL", "TAUTOLOGY"})
  @DisplayName("实际可读到异租户行的宽松策略组合不能让闭世界守护报绿")
  void shouldRejectPermissivePolicyThatReallyExposesOtherTenantRows(String variant)
      throws Exception {
    final String selectTableoidSql = """
        select tableoid::regclass::text from biz.customer_account
        where tenant_id = 'tenant-b' and customer_no = 'b-001'
        """;
    final String setLocalSql = """
        set local app.tenant_id = 'tenant-a'
    """;

    assertScriptSuccess(INSTALL);
    String leaf = admin.queryForObject(selectTableoidSql, String.class);
    if ("ADDITIONAL".equals(variant)) {
      final String createPolicySql = """
          create policy unrelated_permit on %s as permissive for select to public using (true)\
          """.formatted(leaf);
      admin.execute(createPolicySql);
    } else {
      final String alterPolicySql = """
          alter policy tenant_isolation_strict on %s using (tenant_id = current_setting('app.tenant_id', true)\s\
          or true) with check (tenant_id = current_setting('app.tenant_id', true) or true)\
          """.formatted(leaf);
      admin.execute(alterPolicySql);
    }
    DataSource writerSource = dataSource(WRITER, TEST_PASSWORD);
    JdbcTemplate writer = new JdbcTemplate(writerSource);
    new TransactionTemplate(new DataSourceTransactionManager(writerSource))
        .executeWithoutResult(status -> {
          writer.execute(setLocalSql);
          final String selectCountSql = """
              select count(*) from %s where tenant_id = 'tenant-b'\
              """.formatted(leaf);
          assertThat(writer.queryForObject(selectCountSql, Long.class)).isEqualTo(1L);
        });

    RlsClosedWorldChecker.Result result =
        new RlsClosedWorldChecker(writerSource, List.of()).check();

    assertThat(result.isClean()).isFalse();
    assertThat(result.missingPolicy()).contains(leaf);
  }

  private void verifyStrictReadsAndHiddenWrites(DataSource writerSource) {
    final String setLocalSql = """
        set local app.tenant_id = 'tenant-a'
    """;
    final String updateCustomerAccountSql = """
        update biz.customer_account set customer_name = 'updated' where tenant_id = 'tenant-a'
    """;
    final String insertCustomerAccountSql = """
              insert into biz.customer_account (tenant_id, customer_no, customer_name)
              values ('tenant-b', 'rejected', 'rejected')
              """;
    final String updateCustomerAccountSql2 = """
        update biz.customer_account set tenant_id = 'tenant-b' where tenant_id = 'tenant-a'
    """;

    JdbcTemplate writer = new JdbcTemplate(writerSource);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(writerSource));
    List<String> tables = tenantRelations();
    assertThat(tables)
        .contains(
            "biz.customer_account",
            "biz.customer_account_p0",
            "biz.customer_account_p1",
            "biz.customer_account_p2",
            "biz.customer_account_p3",
            "batch.process_staging",
            "batch.process_staging_probe",
            "batch.process_staging_default");
    for (String table : tables) {
      final String selectCountSql = """
          select count(*) from %s\
          """.formatted(table);
      assertThat(writer.queryForObject(selectCountSql, Long.class)).isZero();
      final String selectTenantACountSql = """
          select count(*) from %s where tenant_id = 'tenant-a'\
          """.formatted(table);
      Long expected = admin.queryForObject(selectTenantACountSql, Long.class);
      transaction.executeWithoutResult(status -> {
        writer.execute(setLocalSql);
        final String selectTenantIdSql = """
            select tenant_id from %s\
            """.formatted(table);
        assertThat(writer.queryForList(selectTenantIdSql, String.class))
            .as(table)
            .hasSize(expected.intValue())
            .allMatch("tenant-a"::equals);
        final String updateStatementSql = """
            update %s set tenant_id = tenant_id where tenant_id = 'tenant-b'\
            """.formatted(table);
        assertThat(writer.update(updateStatementSql)).isZero();
        final String deleteStatementSql = """
            delete from %s where tenant_id = 'tenant-b'\
            """.formatted(table);
        assertThat(writer.update(deleteStatementSql)).isZero();
      });
    }
    transaction.executeWithoutResult(status -> {
      writer.execute(setLocalSql);
      assertThat(writer.update(updateCustomerAccountSql)).isEqualTo(1);
    });
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
          writer.execute(setLocalSql);
          writer.update(insertCustomerAccountSql);
        }))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
          writer.execute(setLocalSql);
          writer.update(updateCustomerAccountSql2);
        }))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("42501"));
  }

  private List<String> tenantRelations() {
    final String selectFormatSql = """
        select format('%I.%I', n.nspname, c.relname)
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped
        where c.relkind in ('r', 'p')
          and (n.nspname = 'biz' or (n.nspname = 'batch' and c.relname like 'process_staging%'))
        order by n.nspname, c.relname
        """;

    return admin.queryForList(selectFormatSql, String.class);
  }

  private String protectionSnapshot() {
    final String selectJsonbAggSql = """
        select jsonb_agg(to_jsonb(protection) order by table_schema, table_name)::text from (
          select n.nspname as table_schema, c.relname as table_name, c.relrowsecurity, c.relforcerowsecurity,
            (select jsonb_agg(to_jsonb(p) order by p.policyname) from pg_policies p
             where p.schemaname = n.nspname and p.tablename = c.relname) as policies
          from pg_class c join pg_namespace n on n.oid = c.relnamespace
          where c.relkind in ('r', 'p') and n.nspname in ('biz', 'batch')
        ) protection
        """;

    return admin.queryForObject(selectJsonbAggSql, String.class);
  }

  private Long writerRoleCount() {
    final String selectCountSql = """
        select count(*) from pg_roles where rolname = ?
    """;

    return admin.queryForObject(selectCountSql, Long.class, WRITER);
  }

  private void assertScriptSuccess(String script) throws Exception {
    ExecResult result = runScript(script);
    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
  }

  private ExecResult runScript(String script) throws Exception {
    Path repository = Path.of(System.getProperty("user.dir")).getParent();
    postgres.copyFileToContainer(
        MountableFile.forHostPath(
            repository.resolve("scripts/db/business/" + script).toString()),
        "/tmp/rls-script-probe.sql");
    return postgres.execInContainer(
        "psql",
        "-X",
        "-v",
        "ON_ERROR_STOP=1",
        "-U",
        postgres.getUsername(),
        "-d",
        postgres.getDatabaseName(),
        "-v",
        "writer_password=" + TEST_PASSWORD,
        "-v",
        "admin_password=" + TEST_PASSWORD,
        "-f",
        "/tmp/rls-script-probe.sql");
  }

  private DataSource dataSource(String username, String password) {
    return new DriverManagerDataSource(postgres.getJdbcUrl(), username, password);
  }
}
