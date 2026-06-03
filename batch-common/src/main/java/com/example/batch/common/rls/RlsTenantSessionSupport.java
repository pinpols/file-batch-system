package com.example.batch.common.rls;

import com.example.batch.common.utils.Texts;
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
 * <p>Phase A transition 模式:RLS policy 在 `app.tenant_id` 未设时允许全部(向后兼容);设了则强制等值。所以
 * **本工具调用失败也不影响**已有功能,只是 RLS 防御不生效。
 *
 * <p>未来 strict 模式:policy 去掉 IS NULL 分支,本工具不调 → INSERT 被 policy 拒绝(显式可见错误)。
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
    if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
      // 形态不合法即认为上游 @ValidTenantId 失守,不进 GUC 防 RLS policy 语义崩。
      // 保留 WARN 让运维侧暴露(prod 会被 alerting 抓到)。
      log.warn("RLS skip: tenantId shape rejected (len={})", tenantId.length());
      return;
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
      // Phase A transition 模式下 RLS 不严,SET 失败只是兜底缺失,不阻断业务
      log.warn("RLS set_config failed (tenant={}): {}", tenantId, e.getMessage());
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
}
