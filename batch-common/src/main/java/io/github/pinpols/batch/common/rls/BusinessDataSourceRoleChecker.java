package io.github.pinpols.batch.common.rls;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/** 校验所有业务数据源均使用可被 PostgreSQL RLS 约束的运行账号。 */
public final class BusinessDataSourceRoleChecker {

  private static final String ROLE_SQL =
      "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user";

  private final DataSource businessDataSource;

  public BusinessDataSourceRoleChecker(DataSource businessDataSource) {
    this.businessDataSource = businessDataSource;
  }

  public Result check() throws SQLException {
    List<String> unsafeRoles = new ArrayList<>();
    if (businessDataSource instanceof AbstractRoutingDataSource routing) {
      Map<DataSource, String> shards = distinctShards(routing);
      boolean labelShards = shards.size() > 1;
      for (Map.Entry<DataSource, String> entry : shards.entrySet()) {
        inspect(entry.getKey(), labelShards ? entry.getValue() : null, unsafeRoles);
      }
    } else {
      inspect(businessDataSource, null, unsafeRoles);
    }
    return new Result(List.copyOf(unsafeRoles));
  }

  private static void inspect(DataSource dataSource, String shard, List<String> unsafeRoles)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(ROLE_SQL);
        ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        throw new SQLException("当前 PostgreSQL 运行账号不存在于 pg_roles");
      }
      String role = resultSet.getString(1);
      boolean superuser = resultSet.getBoolean(2);
      boolean bypassRls = resultSet.getBoolean(3);
      if (superuser || bypassRls) {
        String prefix = EmptyChecks.isNull(shard) ? "" : shard + ":";
        unsafeRoles.add(
            prefix + role + "(superuser=" + superuser + ",bypassrls=" + bypassRls + ")");
      }
    }
  }

  private static Map<DataSource, String> distinctShards(AbstractRoutingDataSource routing) {
    Map<DataSource, String> shards = new LinkedHashMap<>();
    routing
        .getResolvedDataSources()
        .forEach((key, dataSource) ->
            shards.merge(dataSource, String.valueOf(key), (left, right) -> left + "," + right));
    DataSource defaultDataSource = routing.getResolvedDefaultDataSource();
    if (!EmptyChecks.isNull(defaultDataSource)) {
      shards.putIfAbsent(defaultDataSource, "default");
    }
    return shards;
  }

  public record Result(List<String> unsafeRoles) {
    public boolean isClean() {
      return EmptyChecks.isEmpty(unsafeRoles);
    }
  }
}
