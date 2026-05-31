package com.example.batch.common.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase A · RLS 行级隔离反例测试。
 *
 * <p>验证 5 个关键行为(scripts/db/business/rls-phase-a.sql + RlsTenantSessionSupport):
 *
 * <ol>
 *   <li>未 SET app.tenant_id(transition 模式):SELECT 返所有租户行 — 向后兼容
 *   <li>SET app.tenant_id='ta':SELECT 只返 ta 行
 *   <li>SET app.tenant_id='ta' + INSERT tenant_id='tb':WITH CHECK 拒绝(POLICY 违反)
 *   <li>SET app.tenant_id='' 空串:transition 模式视为未设,返所有
 *   <li>RlsTenantContextHolder + RlsTenantSessionSupport.applyIfPresent 集成
 * </ol>
 *
 * <p>用单 PG container,避免 PSQL_BUSINESS 跨 JVM 状态污染。
 */
@DisplayName("Phase A · 业务库 RLS 行级隔离 反例")
class RlsTenantIsolationIntegrationTest {

  private static final String POSTGRES_IMAGE = "postgres:17";
  private static PostgreSQLContainer<?> POSTGRES;
  private static HikariDataSource DATASOURCE;
  private static JdbcTemplate JDBC;
  private static TransactionTemplate TX;

  @BeforeAll
  static void startContainer() throws Exception {
    POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse(POSTGRES_IMAGE))
            .withDatabaseName("batch_business")
            .withUsername("batch_user")
            .withPassword("batch_pass_123")
            .withUrlParam("sslmode", "disable");
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
    JDBC.execute("CREATE SCHEMA IF NOT EXISTS biz");
    JDBC.execute("CREATE SCHEMA IF NOT EXISTS batch");
    JDBC.execute(
        """
        CREATE TABLE IF NOT EXISTS biz.customer_account (
          id BIGSERIAL PRIMARY KEY,
          tenant_id VARCHAR(64) NOT NULL,
          customer_no VARCHAR(64) NOT NULL,
          customer_name VARCHAR(256) NOT NULL,
          status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
          created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
          CONSTRAINT uk_customer_account_tenant_no UNIQUE (tenant_id, customer_no)
        )
        """);

    // 2) 应用 RLS phase A 的 ENABLE + policy
    // Testcontainers batch_user 是 SUPERUSER,默认绕过 RLS。建非特权 role rls_app_user,后续 SET ROLE 切过去。
    JDBC.execute("CREATE ROLE rls_app_user NOSUPERUSER NOBYPASSRLS");
    JDBC.execute("GRANT USAGE ON SCHEMA biz TO rls_app_user");
    JDBC.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON biz.customer_account TO rls_app_user");
    JDBC.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA biz TO rls_app_user");
    JDBC.execute("ALTER TABLE biz.customer_account ENABLE ROW LEVEL SECURITY");
    JDBC.execute("ALTER TABLE biz.customer_account FORCE ROW LEVEL SECURITY");
    JDBC.execute(
        """
        CREATE POLICY tenant_isolation_transition ON biz.customer_account
          AS PERMISSIVE
          FOR ALL
          TO PUBLIC
          USING (
            current_setting('app.tenant_id', true) IS NULL
            OR current_setting('app.tenant_id', true) = ''
            OR tenant_id = current_setting('app.tenant_id', true)
          )
          WITH CHECK (
            current_setting('app.tenant_id', true) IS NULL
            OR current_setting('app.tenant_id', true) = ''
            OR tenant_id = current_setting('app.tenant_id', true)
          )
        """);

    // 3) 种子:ta 3 条,tb 2 条(用 transition 默认允许直接插)
    JDBC.update(
        "INSERT INTO biz.customer_account (tenant_id, customer_no, customer_name) VALUES (?,?,?)",
        "ta",
        "ta-001",
        "Customer A1");
    JDBC.update(
        "INSERT INTO biz.customer_account (tenant_id, customer_no, customer_name) VALUES (?,?,?)",
        "ta",
        "ta-002",
        "Customer A2");
    JDBC.update(
        "INSERT INTO biz.customer_account (tenant_id, customer_no, customer_name) VALUES (?,?,?)",
        "ta",
        "ta-003",
        "Customer A3");
    JDBC.update(
        "INSERT INTO biz.customer_account (tenant_id, customer_no, customer_name) VALUES (?,?,?)",
        "tb",
        "tb-001",
        "Customer B1");
    JDBC.update(
        "INSERT INTO biz.customer_account (tenant_id, customer_no, customer_name) VALUES (?,?,?)",
        "tb",
        "tb-002",
        "Customer B2");
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
  @DisplayName("反例 1:未 SET app.tenant_id(transition)→ 返 5 行(向后兼容)")
  void selectWithoutTenantContext_returnsAllRows() {
    // 新 tx,未 SET LOCAL
    Long total =
        TX.execute(
            status -> JDBC.queryForObject("SELECT count(*) FROM biz.customer_account", Long.class));
    assertThat(total).isEqualTo(5L);
  }

  @Test
  @DisplayName("反例 2:SET app.tenant_id='ta' → SELECT 只返 ta 的 3 行")
  void selectWithTenantTa_returnsOnlyTaRows() {
    List<String> noList =
        TX.execute(
            status -> {
              JDBC.execute("SET LOCAL ROLE rls_app_user");
              JDBC.execute("SET LOCAL app.tenant_id = 'ta'");
              List<String> out = new ArrayList<>();
              JDBC.query(
                  "SELECT customer_no FROM biz.customer_account ORDER BY customer_no",
                  (ResultSet rs) -> {
                    out.add(rs.getString(1));
                  });
              return out;
            });
    assertThat(noList).containsExactly("ta-001", "ta-002", "ta-003");
  }

  @Test
  @DisplayName("反例 3:SET app.tenant_id='ta' 后 INSERT tenant_id='tb' → POLICY 拒绝(WITH CHECK 触发)")
  void insertCrossTenant_isRejectedByPolicy() {
    assertThatThrownBy(
            () ->
                TX.execute(
                    status -> {
                      JDBC.execute("SET LOCAL ROLE rls_app_user");
                      JDBC.execute("SET LOCAL app.tenant_id = 'ta'");
                      JDBC.update(
                          "INSERT INTO biz.customer_account (tenant_id, customer_no, customer_name)"
                              + " VALUES (?,?,?)",
                          "tb",
                          "tb-cross-001",
                          "Cross Insert");
                      return null;
                    }))
        .rootCause()
        .hasMessageContaining("row-level security")
        .hasMessageContaining("violates");
  }

  @Test
  @DisplayName("反例 4:SET app.tenant_id='' 空串 → transition 视为未设,返全部 5 行")
  void selectWithEmptyTenantContext_returnsAllRows() {
    Long total =
        TX.execute(
            status -> {
              JDBC.execute("SET LOCAL ROLE rls_app_user");
              JDBC.execute("SET LOCAL app.tenant_id = ''");
              return JDBC.queryForObject("SELECT count(*) FROM biz.customer_account", Long.class);
            });
    assertThat(total).isEqualTo(5L);
  }

  @Test
  @DisplayName("集成:RlsTenantContextHolder + RlsTenantSessionSupport.applyIfPresent → 等价 SET LOCAL")
  void contextHolderIntegration_appliesSessionVar() {
    Long total =
        RlsTenantContextHolder.runWithTenant(
            "tb",
            () ->
                TX.execute(
                    status -> {
                      JDBC.execute("SET LOCAL ROLE rls_app_user");
                      RlsTenantSessionSupport.applyIfPresent(DATASOURCE);
                      return JDBC.queryForObject(
                          "SELECT count(*) FROM biz.customer_account", Long.class);
                    }));
    // tb 只 2 条
    assertThat(total).isEqualTo(2L);
  }
}
