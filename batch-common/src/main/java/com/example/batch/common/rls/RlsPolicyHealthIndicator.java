package com.example.batch.common.rls;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Phase A · RLS healthcheck — 启动期 + actuator/health 时确认 biz.* 10 张表(+ batch.process_staging) 都启用了
 * RLS 并异常退出 transition policy。
 *
 * <p>检查项:对 `pg_class.relrowsecurity / relforcerowsecurity` 校验 ENABLE + FORCE,对 `pg_policies` 校验存在
 * `tenant_isolation_transition` policy。
 *
 * <p>缺一报 DOWN,details 列出缺哪张表 — 让平台运维加新 biz 表时漏配 RLS 立刻可见。
 */
@Slf4j
public class RlsPolicyHealthIndicator implements HealthIndicator {

  /** 受 RLS 保护的 biz 表 + batch.process_staging。新加业务表必须更新本清单 + Flyway 加 policy。 */
  public static final List<String> EXPECTED_RLS_TABLES =
      List.of(
          "biz.customer_account",
          "biz.customer_processed",
          "biz.process_account_summary",
          "biz.process_event_copy",
          "biz.process_order_event",
          "biz.risk_alert",
          "biz.risk_score",
          "biz.settlement_batch",
          "biz.settlement_detail",
          "biz.transaction",
          "batch.process_staging");

  /** 翻转 transition → strict 期间,健康检查接受任一 policy 名(灰度兼容)。 */
  public static final List<String> ACCEPTED_POLICY_NAMES =
      List.of("tenant_isolation_transition", "tenant_isolation_strict");

  /**
   * @deprecated 改用 {@link #ACCEPTED_POLICY_NAMES}。保留是为了 PR #155 引用兼容。
   */
  @Deprecated public static final String EXPECTED_POLICY_NAME = "tenant_isolation_transition";

  private final DataSource businessDataSource;

  public RlsPolicyHealthIndicator(DataSource businessDataSource) {
    this.businessDataSource = businessDataSource;
  }

  @Override
  public Health health() {
    List<String> missingRls = new ArrayList<>();
    List<String> missingForce = new ArrayList<>();
    List<String> missingPolicy = new ArrayList<>();
    boolean tableNotFound = false;

    Connection conn = DataSourceUtils.getConnection(businessDataSource);
    try (Statement st = conn.createStatement()) {
      for (String fqTable : EXPECTED_RLS_TABLES) {
        String[] parts = fqTable.split("\\.", 2);
        String schema = parts[0];
        String table = parts[1];

        // 1. 表是否存在
        boolean exists;
        try (ResultSet rs =
            st.executeQuery(
                "SELECT 1 FROM information_schema.tables WHERE table_schema='"
                    + schema
                    + "' AND table_name='"
                    + table
                    + "'")) {
          exists = rs.next();
        }
        if (!exists) {
          tableNotFound = true;
          continue;
        }

        // 2. ENABLE + FORCE 检查
        try (ResultSet rs =
            st.executeQuery(
                "SELECT relrowsecurity, relforcerowsecurity FROM pg_class c "
                    + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname='"
                    + schema
                    + "' AND c.relname='"
                    + table
                    + "'")) {
          if (rs.next()) {
            if (!rs.getBoolean(1)) missingRls.add(fqTable);
            if (!rs.getBoolean(2)) missingForce.add(fqTable);
          }
        }

        // 3. policy 是否存在 — 接受 transition 或 strict 任一(灰度兼容)
        String policyInList = "('" + String.join("','", ACCEPTED_POLICY_NAMES) + "')";
        try (ResultSet rs =
            st.executeQuery(
                "SELECT 1 FROM pg_policies WHERE schemaname='"
                    + schema
                    + "' AND tablename='"
                    + table
                    + "' AND policyname IN "
                    + policyInList)) {
          if (!rs.next()) missingPolicy.add(fqTable);
        }
      }
    } catch (SQLException e) {
      log.warn("RLS health check failed: {}", e.getMessage());
      return Health.down().withException(e).build();
    } finally {
      DataSourceUtils.releaseConnection(conn, businessDataSource);
    }

    Health.Builder builder =
        (missingRls.isEmpty() && missingForce.isEmpty() && missingPolicy.isEmpty())
            ? Health.up()
            : Health.down();
    builder.withDetail("expectedTables", EXPECTED_RLS_TABLES.size());
    if (tableNotFound) {
      // 部分表可能在某些部署里不存在(只装单一 worker module),不算 fail
      builder.withDetail(
          "note", "some expected tables missing in this deployment (single-worker mode?)");
    }
    if (!missingRls.isEmpty()) builder.withDetail("missingEnableRls", missingRls);
    if (!missingForce.isEmpty()) builder.withDetail("missingForceRls", missingForce);
    if (!missingPolicy.isEmpty()) builder.withDetail("missingPolicy", missingPolicy);
    return builder.build();
  }
}
