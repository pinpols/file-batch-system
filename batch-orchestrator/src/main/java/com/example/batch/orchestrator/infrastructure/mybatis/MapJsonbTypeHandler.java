package com.example.batch.orchestrator.infrastructure.mybatis;

import com.example.batch.common.utils.JsonUtils;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;

/**
 * MyBatis TypeHandler：把 PG {@code JSONB} 列（mapper xml 里通过 {@code ::text} 转字符串选出）映射为 {@code
 * Map<String, Object>}。读路径走 Jackson 反序列化；写路径需在 mapper xml 中显式 {@code cast(#{field,jdbcType=VARCHAR,
 * typeHandler=...} as jsonb)} 触发本 typeHandler 的 setNonNullParameter（用 Jackson 序列化 → setString）。
 *
 * <p>用途：JobDefinition 的 {@code param_schema} / {@code default_params} 等 {@code Map<String, Object>}
 * JSONB 列迁 MyBatis 后，无法依赖 Spring Data Relational 的 ConverterFactory，必须手写 typeHandler。
 *
 * <p>非映射类型（如 {@code Set} / {@code List}）请单独写专用 typeHandler；本类只接受 Map。
 */
@MappedJdbcTypes(JdbcType.VARCHAR)
public class MapJsonbTypeHandler extends BaseTypeHandler<Map<String, Object>> {

  @Override
  public void setNonNullParameter(
      PreparedStatement ps, int i, Map<String, Object> parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setString(i, JsonUtils.toJson(parameter));
  }

  @Override
  public Map<String, Object> getNullableResult(ResultSet rs, String columnName)
      throws SQLException {
    return parse(rs.getString(columnName));
  }

  @Override
  public Map<String, Object> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return parse(rs.getString(columnIndex));
  }

  @Override
  public Map<String, Object> getNullableResult(CallableStatement cs, int columnIndex)
      throws SQLException {
    return parse(cs.getString(columnIndex));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parse(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      Object parsed = JsonUtils.fromJson(json, Object.class);
      if (parsed instanceof Map<?, ?> map) {
        return new LinkedHashMap<>((Map<String, Object>) map);
      }
      return null;
    } catch (RuntimeException ex) {
      // JSONB 列里出现格式错乱（历史脏数据 / 手工改库）→ 不让单条记录读取打崩整个查询
      return null;
    }
  }
}
