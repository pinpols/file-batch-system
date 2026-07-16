package io.github.pinpols.batch.common.rls;

import io.github.pinpols.batch.common.utils.Texts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Pattern;
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
 * <p>strict 模式:policy 必须要求 `app.tenant_id` 存在且与行租户一致。本工具缺少有效上下文或无法写入 GUC
 * 时立即失败，禁止业务事务在没有数据库租户隔离的情况下继续执行。
 */
@Slf4j
public final class RlsTenantSessionSupport {

  /** PostgreSQL custom GUC 名(`app.*` 前缀避开内置 namespace)。RLS policy 用 current_setting 读。 */
  public static final String SESSION_VAR_NAME = "app.tenant_id";

  /**
   * tenantId 形态白名单。
   *
   * <p>P2-2(2026-06-03,docs/analysis/2026-06-03-deep-scan-be-security.md):虽然 {@code
   * set_config('app.tenant_id', ?, true)} 已是 PreparedStatement 绑定参数(SQL 注入路径关闭),仍保留形态白名单 做"纵深防御 +
   * 类型守护"——RLS 策略期望 tenant_id 是 ASCII 短串,任何 Unicode escape / 控制字符进 GUC 会让 RLS policy
   * 比较语义异常(NULL/类型转换/隐式 collation)。
   */
  static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");

  private RlsTenantSessionSupport() {}

  /**
   * 执行 `SET LOCAL app.tenant_id = '?'` 写到当前事务。
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
      throw new IllegalStateException(
          "RLS tenant context is missing; refusing to access tenant-scoped business data");
    }
    if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
      // 形态不合法即认为上游校验失守；继续执行会让 RLS 失去明确的租户边界。
      throw new IllegalArgumentException(
          "RLS tenant context has invalid shape (len=" + tenantId.length() + ")");
    }
    Connection conn = DataSourceUtils.getConnection(dataSource);
    boolean releaseAfter = !DataSourceUtils.isConnectionTransactional(conn, dataSource);
    try (PreparedStatement ps = conn.prepareStatement("SELECT set_config(?, ?, true)")) {
      // P2-2(2026-06-03):改 PreparedStatement,GUC 名与 tenantId 均走 bind 参数,
      // 关闭字符串拼接路径(任何 escape 漏洞 / Unicode 注入都不再相关)。
      // set_config(.,.,true) 等价于 SET LOCAL,事务结束(COMMIT/ROLLBACK)自动 reset。
      ps.setString(1, SESSION_VAR_NAME);
      ps.setString(2, tenantId);
      ps.execute();
      if (log.isTraceEnabled()) {
        log.trace("RLS set_config {} = {}", SESSION_VAR_NAME, tenantId);
      }
    } catch (SQLException e) {
      // SET LOCAL 失败意味着本事务没有数据库级租户隔离，必须阻断而不是降级放行。
      throw new IllegalStateException("RLS set_config failed; refusing tenant-scoped operation", e);
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
      throw new IllegalStateException("RLS RESET " + SESSION_VAR_NAME + " failed", e);
    } finally {
      if (releaseAfter) {
        DataSourceUtils.releaseConnection(conn, dataSource);
      }
    }
  }
}
