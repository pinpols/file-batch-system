package io.github.pinpols.batch.orchestrator.infrastructure.mybatis;

import io.github.pinpols.batch.orchestrator.domain.value.JsonbString;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

/**
 * MyBatis TypeHandler：把 PG {@code JSONB} 列（mapper xml 里通过 {@code ::text} 转字符串选出）映射为 {@link
 * JsonbString} 包装类型。
 *
 * <p>用途：worker_registry / 其他含 JSONB 列的运行态表迁 MyBatis 后，无法依赖 Spring Data Relational 的
 * ConverterFactory， 必须手写 typeHandler。读路径 {@code rs.getString} → {@link JsonbString#of}；写路径在 mapper
 * xml 里直接 {@code cast(#{field.value} as jsonb)}，不走本 typeHandler 的 setNonNullParameter。
 */
@MappedTypes(JsonbString.class)
public class JsonbStringTypeHandler extends BaseTypeHandler<JsonbString> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, JsonbString parameter, JdbcType jdbcType) throws SQLException {
    // 写路径在 mapper xml 内用 cast(#{...} as jsonb)，本方法理论上不被调用；保留回退。
    ps.setString(i, parameter.getValue());
  }

  @Override
  public JsonbString getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return JsonbString.of(rs.getString(columnName));
  }

  @Override
  public JsonbString getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return JsonbString.of(rs.getString(columnIndex));
  }

  @Override
  public JsonbString getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return JsonbString.of(cs.getString(columnIndex));
  }
}
