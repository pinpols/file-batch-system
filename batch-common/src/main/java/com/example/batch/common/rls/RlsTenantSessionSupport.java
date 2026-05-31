package com.example.batch.common.rls;

import com.example.batch.common.utils.Texts;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * 把 {@link RlsTenantContextHolder} 当前 tenant_id 写到 PostgreSQL session 变量 `app.tenant_id`,触发 biz.*
 * 表上的 RLS policy 强制过滤。
 *
 * <p>必须在事务内部调用(取的是 Spring 当前 tx 的同一 connection,SET LOCAL 跟 INSERT/UPDATE/SELECT 共享 tx 生命周期,tx
 * 提交/回滚后自动 reset)。
 *
 * <p>Phase A transition 模式:RLS policy 在 `app.tenant_id` 未设时允许全部(向后兼容);设了则强制等值。所以
 * **本工具调用失败也不影响**已有功能,只是 RLS 防御不生效。
 *
 * <p>未来 strict 模式:policy 去掉 IS NULL 分支,本工具不调 → INSERT 被 policy 拒绝(显式可见错误)。
 */
@Slf4j
public final class RlsTenantSessionSupport {

  /** PostgreSQL custom GUC 名(`app.*` 前缀避开内置 namespace)。RLS policy 用 current_setting 读。 */
  public static final String SESSION_VAR_NAME = "app.tenant_id";

  private RlsTenantSessionSupport() {}

  /**
   * 若 ThreadLocal 有 tenant_id,则执行 `SET LOCAL app.tenant_id = '?'` 写到当前事务。
   *
   * <p>SET LOCAL 等价于 transaction-scoped,事务结束(COMMIT / ROLLBACK)自动 reset,不会污染 connection pool
   * 内的下一个使用者。
   *
   * @param dataSource 目标 DataSource(必须是当前事务正在用的 — 用 {@link DataSourceUtils} 取得 tx 内 connection,与
   *     mapper 共享)
   */
  public static void applyIfPresent(DataSource dataSource) {
    String tenantId = RlsTenantContextHolder.get();
    if (!Texts.hasText(tenantId)) {
      return;
    }
    Connection conn = DataSourceUtils.getConnection(dataSource);
    boolean releaseAfter = !DataSourceUtils.isConnectionTransactional(conn, dataSource);
    try (Statement stmt = conn.createStatement()) {
      // tenant_id 已是受控字符串(来自 JobInstance.tenant_id,带 @ValidTenantId 校验);
      // 但为防御 SQL injection,显式 escape 单引号。
      stmt.execute("SET LOCAL " + SESSION_VAR_NAME + " = '" + escapeSqlLiteral(tenantId) + "'");
      if (log.isTraceEnabled()) {
        log.trace("RLS SET LOCAL {} = {}", SESSION_VAR_NAME, tenantId);
      }
    } catch (SQLException e) {
      // Phase A transition 模式下 RLS 不严,SET 失败只是兜底缺失,不阻断业务
      log.warn("RLS SET LOCAL failed (tenant={}): {}", tenantId, e.getMessage());
    } finally {
      if (releaseAfter) {
        DataSourceUtils.releaseConnection(conn, dataSource);
      }
    }
  }

  /** 显式 RESET(对接非事务路径 / 测试环境清理用)。事务结束 SET LOCAL 自动 reset,业务路径无需调。 */
  public static void reset(DataSource dataSource) {
    Connection conn = DataSourceUtils.getConnection(dataSource);
    boolean releaseAfter = !DataSourceUtils.isConnectionTransactional(conn, dataSource);
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("RESET " + SESSION_VAR_NAME);
    } catch (SQLException e) {
      log.warn("RLS RESET {} failed: {}", SESSION_VAR_NAME, e.getMessage());
    } finally {
      if (releaseAfter) {
        DataSourceUtils.releaseConnection(conn, dataSource);
      }
    }
  }

  /** SQL 字符串字面量内单引号 escape:`'` → `''`(PostgreSQL 标准)。 */
  private static String escapeSqlLiteral(String raw) {
    return raw.replace("'", "''");
  }
}
