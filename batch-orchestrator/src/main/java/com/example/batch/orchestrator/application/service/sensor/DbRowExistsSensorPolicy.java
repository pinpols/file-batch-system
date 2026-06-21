package com.example.batch.orchestrator.application.service.sensor;

import com.example.batch.common.enums.SensorType;
import com.example.batch.common.rls.RlsTenantSessionSupport;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.config.SensorProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB_ROW_EXISTS sensor：用 readonly SELECT 探测业务库某行是否出现。
 *
 * <p>sensor_spec：
 *
 * <pre>{@code
 * {
 *   "schema": "biz",                                       // 必填，且必须在 batch.sensor.db-allowed-schemas
 *   "sql":    "SELECT 1 AS hit FROM biz.signal WHERE biz_date = :bizDate AND status = 'READY' LIMIT 1",
 *   "params": { "bizDate": "$.workflowRun.bizDate" }       // 可选，支持 $.workflowRun.<key> 引用
 * }
 * }</pre>
 *
 * <p>output：{@code rowFound / firstRowJson}。无行 → notYet；命中 → matched。
 *
 * <p>SQL 校验：{@link SensorSqlValidator}（SELECT-only + schema 白名单 + 禁 *）。
 *
 * <p>当前实现复用 orchestrator 主库 DataSource；未来 ADR 可拓展 routing datasource 切到真正业务库。
 */
@Slf4j
@Component
public class DbRowExistsSensorPolicy implements SensorPolicy {

  private final NamedParameterJdbcTemplate jdbc;
  private final DataSource dataSource;
  private final SensorProperties props;
  private final ObjectMapper objectMapper;

  public DbRowExistsSensorPolicy(
      NamedParameterJdbcTemplate jdbc,
      DataSource dataSource,
      SensorProperties props,
      ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.dataSource = dataSource;
    this.props = props;
    this.objectMapper = objectMapper;
  }

  @Override
  public SensorType type() {
    return SensorType.DB_ROW_EXISTS;
  }

  @Override
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public SensorProbeResult probe(SensorContext ctx) {
    Map<String, Object> spec = ctx.sensorSpec();
    String schema = SensorSpecs.string(spec, "schema");
    String sql = SensorSpecs.string(spec, "sql");
    if (!Texts.hasText(schema) || !Texts.hasText(sql)) {
      return SensorProbeResult.error(
          "error.workflow.sensor_spec_invalid", List.of("DB_ROW_EXISTS", "schema/sql required"));
    }
    if (!props.getDbAllowedSchemas().contains(schema.toLowerCase())) {
      return SensorProbeResult.error(
          "error.workflow.sensor_spec_invalid",
          List.of("DB_ROW_EXISTS", "schema not in allowlist: " + schema));
    }
    try {
      SensorSqlValidator.validate(sql, props.getDbAllowedSchemas());
    } catch (IllegalArgumentException e) {
      return SensorProbeResult.error(
          "error.workflow.sensor_spec_invalid", List.of("DB_ROW_EXISTS", e.getMessage()));
    }

    Map<String, Object> params = resolveParams(spec, ctx);

    try {
      RlsTenantSessionSupport.applyIfPresent(dataSource);
      List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
      if (rows.isEmpty()) {
        return SensorProbeResult.notYet();
      }
      Map<String, Object> output = new LinkedHashMap<>();
      output.put("rowFound", true);
      output.put("firstRowJson", writeJson(rows.get(0)));
      return SensorProbeResult.matched(output);
    } catch (Exception e) {
      log.warn("DB_ROW_EXISTS probe error tenant={} err={}", ctx.tenantId(), e.toString());
      return SensorProbeResult.error(
          "error.workflow.sensor_probe_failed", List.of("DB_ROW_EXISTS", e.getMessage()));
    }
  }

  /** 处理 {@code $.workflowRun.<key>} 占位符；非占位值原样透传。 */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> resolveParams(Map<String, Object> spec, SensorContext ctx) {
    Object raw = spec.get("params");
    if (!(raw instanceof Map)) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    Map<String, Object> vars = ctx.workflowRunVars() == null ? Map.of() : ctx.workflowRunVars();
    for (Map.Entry<String, Object> entry : ((Map<String, Object>) raw).entrySet()) {
      Object v = entry.getValue();
      if (v instanceof String s && s.startsWith("$.workflowRun.")) {
        String key = s.substring("$.workflowRun.".length());
        result.put(entry.getKey(), vars.get(key));
      } else {
        result.put(entry.getKey(), v);
      }
    }
    return result;
  }

  private String writeJson(Map<String, Object> row) {
    try {
      return objectMapper.writeValueAsString(row);
    } catch (JsonProcessingException e) {
      return row.toString();
    }
  }
}
