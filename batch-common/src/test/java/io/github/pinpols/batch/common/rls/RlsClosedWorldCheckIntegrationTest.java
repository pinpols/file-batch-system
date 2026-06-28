package io.github.pinpols.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.testing.TestContainerImages;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase A · RLS 闭世界守护(closed-world)集成测试。
 *
 * <p>验证 {@link RlsClosedWorldChecker} / {@link RlsPolicyHealthIndicator} / {@link
 * RlsStartupFailFastCheck} 的闭世界行为:
 *
 * <ol>
 *   <li>全部 biz 表配好 policy → health UP
 *   <li>新建 biz 表不加 policy → health DOWN 且 missing 列出该表(旧硬编码清单做不到)
 *   <li>分区表不误报:父表有 policy → UP,分区子表不进 missing
 *   <li>豁免清单:把漏配表加进 exempt → 回 UP
 *   <li>startup-fail-fast:缺表时守门组件抛 IllegalStateException
 * </ol>
 *
 * <p><b>测试基类例外(裸 JDBC)</b>:同 {@code RlsStrictModePreflightIntegrationTest} —— RLS 状态验证靠 pg_class /
 * pg_policies 真实建表 + 施 policy,自起独立 PG 容器是必要隔离,非违规。
 */
@DisplayName("Phase A · RLS 闭世界守护")
class RlsClosedWorldCheckIntegrationTest {

  private static PostgreSQLContainer postgres;
  private static HikariDataSource dataSource;
  private static JdbcTemplate jdbc;

  @SuppressWarnings("resource")
  @BeforeAll
  static void startContainer() {
    postgres =
        new PostgreSQLContainer(DockerImageName.parse(TestContainerImages.POSTGRES))
            .withDatabaseName("batch_business")
            .withUsername("batch_user")
            .withPassword("batch_pass_123")
            .withUrlParam("sslmode", "disable");
    postgres.start();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(postgres.getJdbcUrl());
    cfg.setUsername(postgres.getUsername());
    cfg.setPassword(postgres.getPassword());
    cfg.setMaximumPoolSize(4);
    dataSource = new HikariDataSource(cfg);
    jdbc = new JdbcTemplate(dataSource);

    jdbc.execute("CREATE SCHEMA IF NOT EXISTS biz");
  }

  @AfterAll
  static void stopContainer() {
    if (dataSource != null) {
      dataSource.close();
    }
    if (postgres != null) {
      postgres.stop();
    }
  }

  @AfterEach
  void dropAllBizTables() {
    jdbc.execute("DROP SCHEMA IF EXISTS biz CASCADE");
    jdbc.execute("CREATE SCHEMA biz");
  }

  // ─── 建表 + 施 policy 套路(对齐 rls-phase-a.sql) ──────────────────────────

  private void createPlainTableWithRls(String table) {
    jdbc.execute(
        "CREATE TABLE biz."
            + table
            + " (id BIGSERIAL, tenant_id VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, id))");
    applyRls("biz." + table);
  }

  private void createPlainTableNoRls(String table) {
    jdbc.execute(
        "CREATE TABLE biz."
            + table
            + " (id BIGSERIAL, tenant_id VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, id))");
  }

  /** 分区父表 + 4 个 hash 子表(对齐 customer_account 在 create_biz_tables.sql 的形态)。RLS 仅施于父表。 */
  private void createPartitionedTableWithRls(String table) {
    jdbc.execute(
        "CREATE TABLE biz."
            + table
            + " (id BIGSERIAL, tenant_id VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, id))"
            + " PARTITION BY HASH (tenant_id)");
    for (int i = 0; i < 4; i++) {
      jdbc.execute(
          "CREATE TABLE biz."
              + table
              + "_p"
              + i
              + " PARTITION OF biz."
              + table
              + " FOR VALUES WITH (MODULUS 4, REMAINDER "
              + i
              + ")");
    }
    applyRls("biz." + table);
  }

  private void applyRls(String fqTable) {
    jdbc.execute("ALTER TABLE " + fqTable + " ENABLE ROW LEVEL SECURITY");
    jdbc.execute("ALTER TABLE " + fqTable + " FORCE ROW LEVEL SECURITY");
    jdbc.execute(
        "CREATE POLICY tenant_isolation_transition ON "
            + fqTable
            + " AS PERMISSIVE FOR ALL TO PUBLIC"
            + " USING (current_setting('app.tenant_id', true) IS NULL"
            + "   OR tenant_id = current_setting('app.tenant_id', true))"
            + " WITH CHECK (current_setting('app.tenant_id', true) IS NULL"
            + "   OR tenant_id = current_setting('app.tenant_id', true))");
  }

  /** 建表 + ENABLE/FORCE RLS + 施一个**同名但语义坏**的 policy(policyClause 决定坏法)。 */
  private void createTableWithNamedPolicy(String table, String policyClause) {
    jdbc.execute(
        "CREATE TABLE biz."
            + table
            + " (id BIGSERIAL, tenant_id VARCHAR(64) NOT NULL, PRIMARY KEY (tenant_id, id))");
    jdbc.execute("ALTER TABLE biz." + table + " ENABLE ROW LEVEL SECURITY");
    jdbc.execute("ALTER TABLE biz." + table + " FORCE ROW LEVEL SECURITY");
    jdbc.execute("CREATE POLICY tenant_isolation_transition ON biz." + table + " " + policyClause);
  }

  // ─── 用例 ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("① 全部 biz 表配好 policy → health UP")
  void allTablesWithPolicy_healthUp() {
    createPlainTableWithRls("customer_account");
    createPlainTableWithRls("transaction");

    Health health = new RlsPolicyHealthIndicator(dataSource).health();

    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).doesNotContainKey("missingRlsTables");
  }

  @Test
  @DisplayName("② 新建 biz 表不加 policy → health DOWN 且 missing 列出 biz.foo(闭世界,旧硬编码清单做不到)")
  void newTableMissingPolicy_healthDownAndListsIt() {
    createPlainTableWithRls("customer_account");
    createPlainTableNoRls("foo");

    Health health = new RlsPolicyHealthIndicator(dataSource).health();

    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .containsKey("missingRlsTables")
        .containsKey("missingEnableRls")
        .containsKey("missingForceRls")
        .containsKey("missingPolicy");
    @SuppressWarnings("unchecked")
    List<String> missing = (List<String>) health.getDetails().get("missingRlsTables");
    assertThat(missing).contains("biz.foo").doesNotContain("biz.customer_account");
  }

  @Test
  @DisplayName("③ 分区表不误报:父表有 policy → UP,分区子表 customer_account_p0.. 不进 missing")
  void partitionChildren_notFalsePositive() {
    createPartitionedTableWithRls("customer_account");

    Health health = new RlsPolicyHealthIndicator(dataSource).health();

    assertThat(health.getStatus()).as("分区父表配好 RLS,子表继承,不该因子表无 policy 报 DOWN").isEqualTo(Status.UP);
    assertThat(health.getDetails()).doesNotContainKey("missingRlsTables");
  }

  @Test
  @DisplayName("④ 把漏配表加进豁免清单 → health 回 UP")
  void exemptedTable_healthUp() {
    createPlainTableWithRls("customer_account");
    createPlainTableNoRls("foo");

    Health withoutExempt = new RlsPolicyHealthIndicator(dataSource).health();
    assertThat(withoutExempt.getStatus()).isEqualTo(Status.DOWN);

    // 豁免接受带或不带 biz. 前缀
    Health exempt = new RlsPolicyHealthIndicator(dataSource, List.of("biz.foo")).health();
    assertThat(exempt.getStatus()).isEqualTo(Status.UP);

    Health exemptBare = new RlsPolicyHealthIndicator(dataSource, List.of("foo")).health();
    assertThat(exemptBare.getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @DisplayName("⑤ startup-fail-fast:缺表 → 守门组件抛 IllegalStateException;全过 → 不抛")
  void startupFailFast_throwsOnMissing() {
    createPlainTableWithRls("customer_account");
    createPlainTableNoRls("foo");

    RlsStartupFailFastCheck failing = new RlsStartupFailFastCheck(dataSource, List.of());
    assertThatThrownBy(failing::checkOnStartup)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("biz.foo");

    // 豁免后不抛
    RlsStartupFailFastCheck exempt = new RlsStartupFailFastCheck(dataSource, List.of("foo"));
    exempt.checkOnStartup();
  }

  // ─── P1-2:同名但语义坏的 policy 必须 DOWN(只验名存在发现不了) ─────────────

  @Test
  @DisplayName("⑥ 同名 policy 但 USING(true) 放行全表 → health DOWN(防只验名)")
  void policyUsingTrue_healthDown() {
    createTableWithNamedPolicy(
        "customer_account", "AS PERMISSIVE FOR ALL TO PUBLIC USING (true) WITH CHECK (true)");

    Health health = new RlsPolicyHealthIndicator(dataSource).health();

    assertThat(health.getStatus()).as("USING(true) 不引用 app.tenant_id,语义坏").isEqualTo(Status.DOWN);
    @SuppressWarnings("unchecked")
    List<String> missing = (List<String>) health.getDetails().get("missingPolicy");
    assertThat(missing).contains("biz.customer_account");
  }

  @Test
  @DisplayName("⑦ 同名 policy 但缺 WITH CHECK → health DOWN")
  void policyMissingWithCheck_healthDown() {
    createTableWithNamedPolicy(
        "customer_account",
        "AS PERMISSIVE FOR ALL TO PUBLIC"
            + " USING (tenant_id = current_setting('app.tenant_id', true))");

    Health health = new RlsPolicyHealthIndicator(dataSource).health();

    assertThat(health.getStatus()).as("FOR ALL 缺 WITH CHECK,写入不受隔离").isEqualTo(Status.DOWN);
  }

  @Test
  @DisplayName("⑧ 同名 policy 但 FOR SELECT 而非 FOR ALL → health DOWN")
  void policyForSelectNotAll_healthDown() {
    createTableWithNamedPolicy(
        "customer_account",
        "AS PERMISSIVE FOR SELECT TO PUBLIC"
            + " USING (tenant_id = current_setting('app.tenant_id', true))");

    Health health = new RlsPolicyHealthIndicator(dataSource).health();

    assertThat(health.getStatus()).as("FOR SELECT 不覆盖写入,cmd<>ALL").isEqualTo(Status.DOWN);
  }
}
