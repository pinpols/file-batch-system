package io.github.pinpols.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase A · RLS 行级隔离反例测试。
 *
 * <p>验证 5 个关键行为(scripts/db/business/rls-phase-a.sql + RlsTenantSessionSupport):
 *
 * <ol>
 *   <li>未 SET app.tenant_id(strict 模式):SELECT 返 0 行 — DB 层 fail-closed
 *   <li>SET app.tenant_id='ta':SELECT 只返 ta 行
 *   <li>SET app.tenant_id='ta' + INSERT tenant_id='tb':WITH CHECK 拒绝(POLICY 违反)
 *   <li>SET app.tenant_id='' 空串(strict 模式):SELECT 返 0 行
 *   <li>RlsTenantContextHolder + RlsTenantSessionSupport.applyIfPresent 集成
 * </ol>
 *
 * <p>用单 PG container,避免 PSQL_BUSINESS 跨 JVM 状态污染。
 *
 * <p><b>测试基类例外(必须裸 JDBC)</b>:本测试故意不继承 {@code AbstractIntegrationTest}。RLS POLICY USING/WITH CHECK
 * 的反例验证依赖 {@code SET LOCAL app.tenant_id} session-scope GUC + 显式 INSERT, 而 HikariCP 池化 connection
 * 跨测试复用会污染 GUC 状态;基类提供的 Spring tx 模板与 RLS 反例所需的 「裸 connection 直发 SET LOCAL + INSERT +
 * SELECT」不兼容。自起独立 PG 容器是必要隔离。
 */
@DisplayName("Phase A · 业务库 RLS 行级隔离 反例")
class RlsTenantIsolationIntegrationTest {

  private static PostgreSQLContainer POSTGRES;
  private static HikariDataSource DATASOURCE;
  private static JdbcTemplate JDBC;
  private static TransactionTemplate TX;

  @SuppressWarnings("resource")
  @BeforeAll
  static void startContainer() throws Exception {
    final String createSchemaSql = """
        CREATE SCHEMA IF NOT EXISTS biz
    """;
    final String createSchemaSql2 = """
        CREATE SCHEMA IF NOT EXISTS batch
    """;
    final String createIfSql = """
        CREATE TABLE IF NOT EXISTS biz.customer_account (
          id BIGSERIAL PRIMARY KEY,
          tenant_id VARCHAR(64) NOT NULL,
          customer_no VARCHAR(64) NOT NULL,
          customer_name VARCHAR(256) NOT NULL,
          status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
          created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
          CONSTRAINT uk_customer_account_tenant_no UNIQUE (tenant_id, customer_no)
        )
        """;
    final String createRoleSql = """
        CREATE ROLE rls_app_user NOSUPERUSER NOBYPASSRLS
    """;
    final String grantUsageSql = """
        GRANT USAGE ON SCHEMA biz TO rls_app_user
    """;
    final String grantSelectSql = """
        GRANT SELECT, INSERT, UPDATE, DELETE ON biz.customer_account TO rls_app_user
    """;
    final String grantUsageSql2 = """
        GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA biz TO rls_app_user
    """;
    final String alterCustomerAccountSql = """
        ALTER TABLE biz.customer_account ENABLE ROW LEVEL SECURITY
    """;
    final String alterCustomerAccountSql2 = """
        ALTER TABLE biz.customer_account FORCE ROW LEVEL SECURITY
    """;
    final String createPolicySql = """
        CREATE POLICY tenant_isolation_strict ON biz.customer_account
          AS PERMISSIVE
          FOR ALL
          TO PUBLIC
          USING (tenant_id = current_setting('app.tenant_id', true))
          WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
        """;
    final String insertCustomerAccountSql = """
        INSERT INTO biz.customer_account (tenant_id, customer_no, customer_name) VALUES (?,?,?)
    """;

    POSTGRES = TestPostgresContainers.business();
    POSTGRES.start();

    HikariConfig cfg = new HikariConfig();
    cfg.setJdbcUrl(POSTGRES.getJdbcUrl());
    cfg.setUsername(POSTGRES.getUsername());
    cfg.setPassword(POSTGRES.getPassword());
    cfg.setMaximumPoolSize(4);
    DATASOURCE = new HikariDataSource(cfg);
    JDBC = new JdbcTemplate(DATASOURCE);
    PlatformTransactionManager tm = new DataSourceTransactionManager(DATASOURCE);
    TX = new TransactionTemplate(tm);

    // 1) biz schema + customer_account 表 + ta/tb 种子
    JDBC.execute(createSchemaSql);
    JDBC.execute(createSchemaSql2);
    JDBC.execute(createIfSql);

    // 2) 应用 RLS strict policy
    // Testcontainers batch_user 是 SUPERUSER,默认绕过 RLS。建非特权 role rls_app_user,后续 SET ROLE 切过去。
    JDBC.execute(createRoleSql);
    JDBC.execute(grantUsageSql);
    JDBC.execute(grantSelectSql);
    JDBC.execute(grantUsageSql2);
    JDBC.execute(alterCustomerAccountSql);
    JDBC.execute(alterCustomerAccountSql2);
    JDBC.execute(createPolicySql);

    // 3) 种子:使用 superuser 建立测试数据；后续查询显式切到非特权 RLS role。
    JDBC.update(insertCustomerAccountSql, "ta", "ta-001", "Customer A1");
    JDBC.update(insertCustomerAccountSql, "ta", "ta-002", "Customer A2");
    JDBC.update(insertCustomerAccountSql, "ta", "ta-003", "Customer A3");
    JDBC.update(insertCustomerAccountSql, "tb", "tb-001", "Customer B1");
    JDBC.update(insertCustomerAccountSql, "tb", "tb-002", "Customer B2");
  }

  @AfterAll
  static void stopContainer() {
    if (DATASOURCE != null) DATASOURCE.close();
    if (POSTGRES != null) POSTGRES.stop();
  }

  @BeforeEach
  void clearThreadLocal() {
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("安全性 1:未 SET app.tenant_id(strict)→ 返 0 行(DB 层拒绝全表读取)")
  void selectWithoutTenantContext_returnsNoRows() {
    final String setLocalSql = """
        SET LOCAL ROLE rls_app_user
    """;
    final String selectCountSql = """
        SELECT count(*) FROM biz.customer_account
    """;

    // 新 tx,未 SET LOCAL
    Long total = TX.execute(status -> {
      JDBC.execute(setLocalSql);
      return JDBC.queryForObject(selectCountSql, Long.class);
    });
    assertThat(total).isZero();
  }

  @Test
  @DisplayName("反例 2:SET app.tenant_id='ta' → SELECT 只返 ta 的 3 行")
  void selectWithTenantTa_returnsOnlyTaRows() {
    final String setLocalSql = """
        SET LOCAL ROLE rls_app_user
    """;
    final String setLocalSql2 = """
        SET LOCAL app.tenant_id = 'ta'
    """;
    final String selectCustomerNoSql = """
        SELECT customer_no FROM biz.customer_account ORDER BY customer_no
    """;

    List<String> noList = TX.execute(status -> {
      JDBC.execute(setLocalSql);
      JDBC.execute(setLocalSql2);
      List<String> out = new ArrayList<>();
      JDBC.query(selectCustomerNoSql, (ResultSet rs) -> {
        out.add(rs.getString(1));
      });
      return out;
    });
    assertThat(noList).containsExactly("ta-001", "ta-002", "ta-003");
  }

  @Test
  @DisplayName("反例 3:SET app.tenant_id='ta' 后 INSERT tenant_id='tb' → POLICY 拒绝(WITH CHECK 触发)")
  void insertCrossTenant_isRejectedByPolicy() {
    final String setLocalSql = """
        SET LOCAL ROLE rls_app_user
    """;
    final String setLocalSql2 = """
        SET LOCAL app.tenant_id = 'ta'
    """;
    final String insertCustomerAccountSql = """
        INSERT INTO biz.customer_account (tenant_id, customer_no, customer_name) VALUES (?,?,?)\
        """;

    assertThatThrownBy(() -> TX.execute(status -> {
          JDBC.execute(setLocalSql);
          JDBC.execute(setLocalSql2);
          JDBC.update(insertCustomerAccountSql, "tb", "tb-cross-001", "Cross Insert");
          return null;
        }))
        .rootCause()
        .hasMessageContaining("row-level security")
        .hasMessageContaining("violates");
  }

  @Test
  @DisplayName("安全性 4:SET app.tenant_id='' 空串(strict)→ 返 0 行")
  void selectWithEmptyTenantContext_returnsNoRows() {
    final String setLocalSql = """
        SET LOCAL ROLE rls_app_user
    """;
    final String setLocalSql2 = """
        SET LOCAL app.tenant_id = ''
    """;
    final String selectCountSql = """
        SELECT count(*) FROM biz.customer_account
    """;

    Long total = TX.execute(status -> {
      JDBC.execute(setLocalSql);
      JDBC.execute(setLocalSql2);
      return JDBC.queryForObject(selectCountSql, Long.class);
    });
    assertThat(total).isZero();
  }

  @Test
  @DisplayName("集成:RlsTenantContextHolder + RlsTenantSessionSupport.applyIfPresent → 等价 SET LOCAL")
  void contextHolderIntegration_appliesSessionVar() {
    final String setLocalSql = """
        SET LOCAL ROLE rls_app_user
    """;
    final String selectCountSql = """
        SELECT count(*) FROM biz.customer_account
    """;

    Long total = RlsTenantContextHolder.runWithTenant(
        "tb",
        () -> TX.execute(status -> {
          JDBC.execute(setLocalSql);
          RlsTenantSessionSupport.applyIfPresent(DATASOURCE);
          return JDBC.queryForObject(selectCountSql, Long.class);
        }));
    // tb 只 2 条
    assertThat(total).isEqualTo(2L);
  }

  @Test
  @DisplayName("安全性 6:直接查询已授权的分区叶子仍受叶子 strict RLS 约束")
  void directPartitionAccess_isIsolatedByPartitionPolicy() {
    final String createPartitionedAccountSql = """
        CREATE TABLE biz.partitioned_account (
          tenant_id VARCHAR(64) NOT NULL,
          account_no VARCHAR(64) NOT NULL
        ) PARTITION BY HASH (tenant_id)
        """;
    final String grantSelectSql = """
        GRANT SELECT ON biz.partitioned_account TO rls_app_user
    """;
    final String insertPartitionedAccountSql = """
        INSERT INTO biz.partitioned_account (tenant_id, account_no) VALUES (?, ?)
    """;
    final String selectTableoidSql = """
        SELECT tableoid::regclass::text FROM biz.partitioned_account WHERE tenant_id = 'tenant-b'\s\
        AND account_no = 'b-001'\
        """;
    final String selectTableoidSql2 = """
        SELECT tableoid::regclass::text FROM biz.partitioned_account WHERE tenant_id = 'tenant-a'\s\
        AND account_no = 'a-001'\
        """;
    final String setLocalSql = """
        SET LOCAL ROLE rls_app_user
    """;
    final String setLocalSql2 = """
        SET LOCAL app.tenant_id = 'tenant-a'
    """;

    JDBC.execute(createPartitionedAccountSql);
    for (int remainder = 0; remainder < 4; remainder++) {
      final String createPartitionedAccountPSql = """
          CREATE TABLE biz.partitioned_account_p%d PARTITION OF biz.partitioned_account
          FOR VALUES WITH (MODULUS 4, REMAINDER %d)
          """.formatted(remainder, remainder);
      JDBC.execute(createPartitionedAccountPSql);
    }
    applyStrictPolicy("biz.partitioned_account");
    for (int remainder = 0; remainder < 4; remainder++) {
      String partition = "biz.partitioned_account_p" + remainder;
      applyStrictPolicy(partition);
      final String grantSelectSql2 = """
          GRANT SELECT ON %s TO rls_app_user
      """.formatted(partition);
      JDBC.execute(grantSelectSql2);
    }
    JDBC.execute(grantSelectSql);
    JDBC.update(insertPartitionedAccountSql, "tenant-a", "a-001");
    JDBC.update(insertPartitionedAccountSql, "tenant-b", "b-001");

    String tenantBPartition = JDBC.queryForObject(selectTableoidSql, String.class);
    assertThat(tenantBPartition).matches("biz\\.partitioned_account_p[0-3]");

    String tenantAPartition = JDBC.queryForObject(selectTableoidSql2, String.class);
    List<String> visibleTenants = TX.execute(status -> {
      JDBC.execute(setLocalSql);
      JDBC.execute(setLocalSql2);
      final String selectTenantIdSql = """
          SELECT tenant_id FROM %s
      """.formatted(tenantBPartition);
      return JDBC.queryForList(selectTenantIdSql, String.class);
    });
    // 两租户可能散列到同一叶子；隔离要求隐藏 B 的行，同时保留 A 的合法可见行。
    assertThat(visibleTenants)
        .containsExactlyElementsOf(
            tenantBPartition.equals(tenantAPartition) ? List.of("tenant-a") : List.of());
  }

  private void applyStrictPolicy(String table) {
    final String alterStatementSql = """
        ALTER TABLE %s ENABLE ROW LEVEL SECURITY
    """.formatted(table);
    JDBC.execute(alterStatementSql);
    final String forceRlsSql = """
        ALTER TABLE %s FORCE ROW LEVEL SECURITY
    """.formatted(table);
    JDBC.execute(forceRlsSql);
    final String createPolicySql = """
        CREATE POLICY tenant_isolation_strict ON %s AS PERMISSIVE FOR ALL TO PUBLIC
        USING (tenant_id = current_setting('app.tenant_id', true))
        WITH CHECK (tenant_id = current_setting('app.tenant_id', true))
        """.formatted(table);
    JDBC.execute(createPolicySql);
  }
}
