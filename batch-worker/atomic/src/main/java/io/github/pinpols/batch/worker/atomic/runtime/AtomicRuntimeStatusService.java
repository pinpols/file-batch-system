package io.github.pinpols.batch.worker.atomic.runtime;

import io.github.pinpols.batch.worker.atomic.config.AtomicWorkerConfiguration;
import io.github.pinpols.batch.worker.atomic.http.HttpExecutorProperties;
import io.github.pinpols.batch.worker.atomic.runtime.AtomicRuntimeStatus.HttpStatus;
import io.github.pinpols.batch.worker.atomic.runtime.AtomicRuntimeStatus.ShellStatus;
import io.github.pinpols.batch.worker.atomic.runtime.AtomicRuntimeStatus.SqlStatus;
import io.github.pinpols.batch.worker.atomic.runtime.AtomicRuntimeStatus.StoredProcStatus;
import io.github.pinpols.batch.worker.atomic.shell.ShellExecutorProperties;
import io.github.pinpols.batch.worker.atomic.sql.SqlExecutorProperties;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Locale;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 收集 4 个 executor 的当前 effective 配置 + SQL 连接方言,组装 {@link AtomicRuntimeStatus} 快照。
 *
 * <p>纯读取已绑定的 {@code @ConfigurationProperties} bean,无 IO 副作用(SQL 方言探测有一次 JDBC 元数据查询,异常时降级为 {@code
 * "unknown"})。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AtomicRuntimeStatusService {

  private final AtomicWorkerConfiguration workerConfiguration;
  private final Environment environment;
  private final ObjectProvider<ShellExecutorProperties> shellProps;
  private final ObjectProvider<SqlExecutorProperties> sqlProps;
  private final ObjectProvider<HttpExecutorProperties> httpProps;
  private final ObjectProvider<StoredProcExecutorProperties> storedProcProps;
  private final ObjectProvider<DataSource> dataSourceProvider;

  public AtomicRuntimeStatus snapshot() {
    return new AtomicRuntimeStatus(
        workerConfiguration.workerCode(),
        workerConfiguration.workerType(),
        shellStatus(),
        sqlStatus(),
        httpStatus(),
        storedProcStatus());
  }

  private ShellStatus shellStatus() {
    ShellExecutorProperties p = shellProps.getIfAvailable();
    if (p == null) {
      return new ShellStatus(false, 0);
    }
    return new ShellStatus(p.isEnabled(), safeSize(p.getCommandWhitelist()));
  }

  private SqlStatus sqlStatus() {
    SqlExecutorProperties p = sqlProps.getIfAvailable();
    boolean enabled = p != null && p.isEnabled();
    return new SqlStatus(enabled, enabled ? detectDialect() : "n/a");
  }

  private HttpStatus httpStatus() {
    HttpExecutorProperties p = httpProps.getIfAvailable();
    if (p == null) {
      return new HttpStatus(false, false, "absent", 0);
    }
    String source;
    if (environment.containsProperty(HttpExecutorProdDefaults.PROP_ENFORCE_ALLOWLIST)) {
      source = "explicit";
    } else if (isProdProfile()) {
      // prod profile 下未显式配置 → HttpExecutorProdDefaults 已隐式翻 true(若原为 true 则已是 true)。
      source = "prod-default";
    } else {
      source = "dev-default";
    }
    return new HttpStatus(
        p.isEnabled(), p.isEnforceAllowlist(), source, safeSize(p.getAllowedHostPatterns()));
  }

  private StoredProcStatus storedProcStatus() {
    StoredProcExecutorProperties p = storedProcProps.getIfAvailable();
    if (p == null) {
      return new StoredProcStatus(false, 0);
    }
    return new StoredProcStatus(p.isEnabled(), safeSize(p.getAllowedSchemas()));
  }

  private String detectDialect() {
    DataSource ds = dataSourceProvider.getIfAvailable();
    if (ds == null) {
      return "unknown";
    }
    try (Connection conn = ds.getConnection()) {
      String product = conn.getMetaData().getDatabaseProductName();
      return product == null ? "unknown" : product;
    } catch (SQLException ex) {
      log.debug("atomic runtime status: detect SQL dialect failed", ex);
      return "unknown";
    }
  }

  private boolean isProdProfile() {
    String[] active = environment.getActiveProfiles();
    if (active == null) {
      return false;
    }
    for (String p : active) {
      if (p == null) {
        continue;
      }
      if ("prod".equalsIgnoreCase(p.trim())) {
        return true;
      }
      for (String token : p.toLowerCase(Locale.ROOT).split("[\\-_,]")) {
        if ("prod".equals(token)) {
          return true;
        }
      }
    }
    return false;
  }

  private static int safeSize(Collection<?> c) {
    return c == null ? 0 : c.size();
  }
}
