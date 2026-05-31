package com.example.batch.worker.atomic.runtime;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;

/**
 * ADR-035 §9 — SPI 执行器(sql / storedproc)共享的 DataSource 解析 + 角色闸 + 回调式 acquire/release。
 *
 * <p>本类**只能被 batch-worker-atomic 内的 SQL / StoredProc executor 使用**(SPI-only;pipeline 4 个 worker
 * 各自管自己模块内 DB 访问,不依赖本类),不破 ADR-035 §9 红线。
 *
 * <p>提供三件套:
 *
 * <ol>
 *   <li>{@link #resolveDataSourceBeanName} — 统一 param-vs-config DataSource 选择 + allowedBeans 白名单守门
 *   <li>{@link #requireNonOsCapableRole} — 拒绝 superuser / pg_execute_server_program / 文件读写角色; 这是堵
 *       OS 的硬保证(黑名单可绕,角色闸不可)
 *   <li>{@link #withConnection} — 回调式 acquire/release/rollback,opt-in 用法
 * </ol>
 *
 * <p>**现有 SqlTaskExecutor / StoredProcTaskExecutor 仍 inline 处理连接 + 角色 +
 * 事务**(语义与执行模式深耦合,直译过来更费力);本类是 **新增的可选路径**,后续 PR 渐进迁移。
 */
@Slf4j
public final class AtomicConnectionManager {

  private AtomicConnectionManager() {}

  /**
   * 解析最终要用的 dataSource bean 名,并对 param 覆盖做白名单校验。
   *
   * <p>规则:param 缺省回落到 {@code configuredBeanName}(可能 null = 默认库)。param 显式给出且与 configured 不同时,必须命中
   * {@code allowedBeans},否则抛 {@link IllegalArgumentException}。
   *
   * @return 校验后的 bean 名,或 null = 用默认 DataSource
   */
  public static String resolveDataSourceBeanName(
      String configuredBeanName, String paramBeanName, Set<String> allowedBeans) {
    if (paramBeanName == null
        || paramBeanName.isBlank()
        || paramBeanName.equals(configuredBeanName)) {
      return configuredBeanName;
    }
    Set<String> allow = allowedBeans == null ? Set.of() : allowedBeans;
    if (!allow.contains(paramBeanName)) {
      throw new IllegalArgumentException(
          "dataSourceBean '" + paramBeanName + "' not in allowedDataSourceBeans=" + allow);
    }
    return paramBeanName;
  }

  /** 取 DataSource:bean 名为 null → defaultDs;否则从 beanFactory 拿。 */
  public static DataSource resolveDataSource(
      String beanName, BeanFactory beanFactory, DataSource defaultDs) {
    if (beanName == null) {
      return Objects.requireNonNull(defaultDs, "defaultDs required when beanName null");
    }
    return beanFactory.getBean(beanName, DataSource.class);
  }

  /**
   * 检测当前 DB 角色不带 OS 能力(SUPERUSER / pg_execute_server_program / pg_read_server_files /
   * pg_write_server_files)。任何一个命中即抛 {@link SecurityException}。这是 SQL 路径堵 OS 的硬保证。
   *
   * <p>仅 PostgreSQL 实现;其他方言需要在调用前自行守门或扩展本方法。
   */
  public static void requireNonOsCapableRole(Connection conn) throws SQLException {
    try (Statement st = conn.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT rolsuper,"
                    + " pg_has_role(current_user, 'pg_execute_server_program', 'MEMBER') AS prog,"
                    + " pg_has_role(current_user, 'pg_read_server_files', 'MEMBER') AS rdf,"
                    + " pg_has_role(current_user, 'pg_write_server_files', 'MEMBER') AS wsf "
                    + "FROM pg_roles WHERE rolname = current_user")) {
      if (rs.next()) {
        boolean superuser = rs.getBoolean("rolsuper");
        boolean prog = rs.getBoolean("prog");
        boolean rdf = rs.getBoolean("rdf");
        boolean wsf = rs.getBoolean("wsf");
        if (superuser || prog || rdf || wsf) {
          throw new SecurityException(
              String.format(
                  Locale.ROOT,
                  "DB role has OS capability (superuser=%s, exec_server_program=%s,"
                      + " read_server_files=%s, write_server_files=%s); refusing to run SPI."
                      + " Use a least-privilege non-superuser role, or disable"
                      + " forbidOsCapableRole only in trusted test envs.",
                  superuser,
                  prog,
                  rdf,
                  wsf));
        }
      }
    }
  }

  /** {@link #withConnection} 的回调签名 — 允许抛 SQLException。 */
  @FunctionalInterface
  public interface ConnectionCallback<T> {
    T call(Connection conn) throws SQLException;
  }

  /**
   * 回调式 acquire/release。autoCommit 关时自动 commit(callback 正常返回)/ rollback(异常), 然后 restore 原始
   * autoCommit + readOnly。
   *
   * @param ds 数据源
   * @param opts 选项(可空,见 {@link Options})
   * @param callback 业务逻辑;签名内的 Connection 已按 opts 配好
   */
  public static <T> T withConnection(DataSource ds, Options opts, ConnectionCallback<T> callback)
      throws SQLException {
    Options o = opts == null ? Options.defaults() : opts;
    try (Connection conn = ds.getConnection()) {
      if (o.forbidOsCapableRole) {
        requireNonOsCapableRole(conn);
      }
      boolean originalAutoCommit = conn.getAutoCommit();
      boolean originalReadOnly = conn.isReadOnly();
      conn.setAutoCommit(o.autoCommit);
      if (o.readOnly) conn.setReadOnly(true);
      try {
        T result = callback.call(conn);
        if (!o.autoCommit) conn.commit();
        return result;
      } catch (SQLException | RuntimeException ex) {
        if (!o.autoCommit) {
          try {
            conn.rollback();
          } catch (SQLException rb) {
            log.warn("rollback failed: {}", rb.getMessage());
          }
        }
        throw ex;
      } finally {
        try {
          conn.setAutoCommit(originalAutoCommit);
        } catch (SQLException restore) {
          log.warn("restore autoCommit failed: {}", restore.getMessage());
        }
        if (o.readOnly) {
          try {
            conn.setReadOnly(originalReadOnly);
          } catch (SQLException restore) {
            log.warn("restore readOnly failed: {}", restore.getMessage());
          }
        }
      }
    }
  }

  /** withConnection 选项。 */
  public static final class Options {
    public final boolean autoCommit;
    public final boolean readOnly;
    public final boolean forbidOsCapableRole;

    private Options(boolean autoCommit, boolean readOnly, boolean forbidOsCapableRole) {
      this.autoCommit = autoCommit;
      this.readOnly = readOnly;
      this.forbidOsCapableRole = forbidOsCapableRole;
    }

    public static Options defaults() {
      return new Options(false, false, true);
    }

    public static Options readOnly() {
      return new Options(false, true, true);
    }

    public Options withAutoCommit(boolean v) {
      return new Options(v, this.readOnly, this.forbidOsCapableRole);
    }

    public Options withReadOnly(boolean v) {
      return new Options(this.autoCommit, v, this.forbidOsCapableRole);
    }

    public Options withForbidOsCapableRole(boolean v) {
      return new Options(this.autoCommit, this.readOnly, v);
    }
  }
}
