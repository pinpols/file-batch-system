package io.github.pinpols.batch.console.support.persistence;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 在当前事务持有的平台库连接上执行 {@code set_config('app.tenant_id', ?, true)}。 */
@Component
@RequiredArgsConstructor
public class JdbcTenantDatabaseContext implements TenantDatabaseContext {

  private static final String BIND_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, true)";

  private final DataSource dataSource;

  @Override
  public void bind(String tenantId) {
    if (EmptyChecks.isBlank(tenantId)
        || !TransactionSynchronizationManager.isActualTransactionActive()) {
      return;
    }
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      // 当前事务属于其他数据源时，这里拿到的是非事务连接，事务局部设置没有意义，直接跳过。
      if (!DataSourceUtils.isConnectionTransactional(connection, dataSource)) {
        return;
      }
      try (PreparedStatement statement = connection.prepareStatement(BIND_TENANT_SQL)) {
        statement.setString(1, tenantId);
        statement.execute();
      }
    } catch (SQLException failure) {
      // 绑定失败意味着本事务对强制 RLS 表没有租户上下文，继续执行会被拒绝或读到空结果。
      throw new IllegalStateException("failed to bind tenant database context", failure);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }
}
