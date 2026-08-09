package io.github.pinpols.batch.common.jdbc;

import java.sql.Connection;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;

/** AutoCloseable wrapper for Spring-managed DataSource connections. */
public final class DataSourceConnectionLease implements AutoCloseable {

  private final Connection connection;
  private final DataSource dataSource;

  private DataSourceConnectionLease(Connection connection, DataSource dataSource) {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  public static DataSourceConnectionLease acquire(DataSource dataSource) {
    DataSource source = Objects.requireNonNull(dataSource, "dataSource");
    return new DataSourceConnectionLease(DataSourceUtils.getConnection(source), source);
  }

  public Connection connection() {
    return connection;
  }

  @Override
  public void close() {
    DataSourceUtils.releaseConnection(connection, dataSource);
  }
}
