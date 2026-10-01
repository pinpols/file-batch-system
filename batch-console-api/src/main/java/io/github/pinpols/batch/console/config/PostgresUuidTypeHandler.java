package io.github.pinpols.batch.console.config;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/** 映射 PostgreSQL UUID，避免 SQL 谓词把 UUID 转为 VARCHAR。 */
public class PostgresUuidTypeHandler extends BaseTypeHandler<UUID> {
  @Override
  public void setNonNullParameter(
      PreparedStatement statement, int index, UUID value, JdbcType jdbcType) throws SQLException {
    statement.setObject(index, value);
  }

  @Override
  public UUID getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
    return (UUID) resultSet.getObject(columnName);
  }

  @Override
  public UUID getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
    return (UUID) resultSet.getObject(columnIndex);
  }

  @Override
  public UUID getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
    return (UUID) statement.getObject(columnIndex);
  }
}
