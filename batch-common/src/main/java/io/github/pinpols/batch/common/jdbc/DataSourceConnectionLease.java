package io.github.pinpols.batch.common.jdbc;

import java.sql.Connection;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** Spring 管理的 DataSource 连接的 AutoCloseable 包装器。 */
public final class DataSourceConnectionLease implements AutoCloseable {

  private final Connection connection;
  private final DataSource dataSource;

  private DataSourceConnectionLease(Connection connection, DataSource dataSource) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public static DataSourceConnectionLease acquire(DataSource dataSource) {
    DataSource source = Objects.requireNonNull(dataSource, "dataSource");
    Connection connection = DataSourceUtils.getConnection(source);
    return new DataSourceConnectionLease(connection, source);
  }

  public Connection connection() {
    return connection;
  }

  @Override
  public void close() {
    DataSourceUtils.releaseConnection(connection, dataSource);
  }
}
