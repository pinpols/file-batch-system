package com.example.batch.worker.processes.sql;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.jdbc.JdbcMappedSqlValidator;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.Texts;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 从 {@code pipeline_step_definition.step_params} 解析出的配置驱动 SQL 加工规格。 */
public record SqlTransformComputeSpec(
    String implCode,
    String sourceSql,
    String targetSchema,
    String targetTable,
    WriteMode writeMode,
    StagingMode stagingMode,
    List<ColumnMapping> columns,
    List<String> conflictColumns,
    String watermarkColumn,
    Map<String, Object> params,
    List<ValidationRule> validations,
    EmptyResultPolicy emptyResultPolicy,
    int maxStagedRows) {

  /** P1-6:单批 staging 行数上限默认值。配置 SQL 写错或数据激增时,防止业务库 + staging 表资源耗尽。 */
  public static final int DEFAULT_MAX_STAGED_ROWS = 1_000_000;

  public enum WriteMode {
    INSERT,
    UPSERT,
    INSERT_IGNORE
  }

  public enum StagingMode {
    JSONB,
    DIRECT
  }

  public enum EmptyResultPolicy {
    FAIL,
    SUCCESS
  }

  public record ColumnMapping(String source, String target) {}

  /**
   * VALIDATE 阶段在 staging 上跑的数据质量规则。每条 checkSql 必须返回单行,带列 {@code pass BOOLEAN} 与可选 {@code message
   * TEXT};{@code pass=false} 即整体校验失败。框架隐式注入 {@code :batchKey} 命名参数。
   */
  public record ValidationRule(String name, String checkSql) {}

  /**
   * 内置保留参数名:{@code spec.params} 不可使用。{@link SqlTransformComputePlugin#buildSqlParams}
   * 将这些名字与平台运行时上下文绑定,避免被业务配置静默覆盖造成跨租户 / 错位 SQL 绑定。
   *
   * <p>{@code metadata_*} 前缀也保留,通过 {@code payload.metadata.&lt;key&gt;} 展开;{@code spec.params}
   * 同样不允许以该前缀开头,避免与 metadata 命名空间碰撞。
   */
  private static final Set<String> RESERVED_PARAMS =
      Set.of(
          "tenantId",
          "jobCode",
          "workerId",
          "highWaterMarkIn",
          "traceId",
          "stepCode",
          "batchKey",
          "targetSchema",
          "targetTable",
          "partitionNo",
          "partitionCount",
          "partitionKey",
          "bizDate");

  private static final String METADATA_PARAM_PREFIX = "metadata_";

  public static SqlTransformComputeSpec parse(
      Map<String, Object> stepParams, ObjectMapper objectMapper) {
    Map<String, Object> root = extractSpecRoot(stepParams, objectMapper);
    if (root.isEmpty()) {
      throw new IllegalArgumentException(
          "sqlTransformCompute spec missing in pipeline_step_definition.step_params");
    }

    String sourceSql = required(root, "sourceSql", "source_sql", "querySql", "query_sql");
    String targetSchema = text(firstNonNull(root.get("targetSchema"), root.get("schema")));
    if (!Texts.hasText(targetSchema)) {
      targetSchema = "biz";
    }
    String targetTable = required(root, "targetTable", "target_table", "table");
    WriteMode writeMode =
        parseWriteMode(firstNonNull(root.get("writeMode"), root.get("write_mode")));
    StagingMode stagingMode =
        parseStagingMode(firstNonNull(root.get("stagingMode"), root.get("staging_mode")));
    List<ColumnMapping> columns = parseColumns(root.get("columns"));
    if (columns.isEmpty()) {
      throw new IllegalArgumentException("sqlTransformCompute.columns is required");
    }
    List<String> conflictColumns =
        parseStringList(firstNonNull(root.get("conflictColumns"), root.get("conflict_columns")));
    String watermarkColumn =
        text(firstNonNull(root.get("watermarkColumn"), root.get("watermark_column")));
    Map<String, Object> params = parseMap(firstNonNull(root.get("params"), root.get("sqlParams")));
    List<ValidationRule> validations = parseValidations(root.get("validations"));
    EmptyResultPolicy emptyResultPolicy =
        parseEmptyResultPolicy(
            firstNonNull(root.get("emptyResultPolicy"), root.get("empty_result_policy")));
    int maxStagedRows =
        parseMaxStagedRows(firstNonNull(root.get("maxStagedRows"), root.get("max_staged_rows")));

    SqlTransformComputeSpec spec =
        new SqlTransformComputeSpec(
            SqlTransformComputePlugin.PLUGIN_ID,
            sourceSql,
            targetSchema,
            targetTable,
            writeMode,
            stagingMode,
            columns,
            conflictColumns,
            watermarkColumn,
            params,
            validations,
            emptyResultPolicy,
            maxStagedRows);
    spec.validateIdentifiers();
    return spec;
  }

  private static int parseMaxStagedRows(Object raw) {
    if (raw == null) {
      return DEFAULT_MAX_STAGED_ROWS;
    }
    int value;
    if (raw instanceof Number n) {
      value = n.intValue();
    } else {
      String text = String.valueOf(raw).trim();
      if (text.isEmpty()) {
        return DEFAULT_MAX_STAGED_ROWS;
      }
      try {
        value = Integer.parseInt(text);
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
            "sqlTransformCompute.maxStagedRows must be a positive integer, got: " + text);
      }
    }
    if (value <= 0) {
      throw new IllegalArgumentException(
          "sqlTransformCompute.maxStagedRows must be > 0, got: " + value);
    }
    return value;
  }

  private static List<ValidationRule> parseValidations(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<ValidationRule> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof Map<?, ?> m) {
        String name = text(m.get("name"));
        String checkSql = text(firstNonNull(m.get("checkSql"), m.get("check_sql")));
        if (Texts.hasText(name) && Texts.hasText(checkSql)) {
          out.add(new ValidationRule(name, checkSql));
        }
      }
    }
    return List.copyOf(out);
  }

  public void validateIdentifiers() {
    JdbcMappedSqlValidator.requireIdentifier(targetSchema, "targetSchema");
    JdbcMappedSqlValidator.requireIdentifier(targetTable, "targetTable");
    for (ColumnMapping column : columns) {
      JdbcMappedSqlValidator.requireIdentifier(column.source(), "source column");
      JdbcMappedSqlValidator.requireIdentifier(column.target(), "target column");
    }
    for (String conflictColumn : conflictColumns) {
      JdbcMappedSqlValidator.requireIdentifier(conflictColumn, "conflictColumn");
      if (columns.stream().noneMatch(column -> column.target().equals(conflictColumn))) {
        throw new IllegalArgumentException(
            "sqlTransformCompute.conflictColumns must appear in target columns: " + conflictColumn);
      }
    }
    // PROCESS at-least-once delivery (commit-后-report-丢、reclaim 重发) 要求所有 writeMode
    // 都必须可幂等重放;空 conflictColumns 配 raw INSERT 会在 publish 重放时双写 target 行。
    // 因此对所有 writeMode 强制要求 conflictColumns。INSERT 路径在 buildPublishSql 中按 DO NOTHING
    // 语义生成 ON CONFLICT 子句,语义 = at-least-once 安全的 append-only。
    if (conflictColumns.isEmpty()) {
      throw new IllegalArgumentException(
          "sqlTransformCompute.conflictColumns is required for "
              + writeMode
              + " (PROCESS retries are at-least-once; conflictColumns 不能为空,否则重放会双写 target)");
    }
    if (Texts.hasText(watermarkColumn)) {
      JdbcMappedSqlValidator.requireIdentifier(watermarkColumn, "watermarkColumn");
    }
    if (stagingMode == StagingMode.DIRECT) {
      if (!validations.isEmpty()) {
        throw new IllegalArgumentException(
            "sqlTransformCompute.validations are not supported when stagingMode=DIRECT"
                + " (DIRECT does not write batch.process_staging)");
      }
      if (emptyResultPolicy != EmptyResultPolicy.SUCCESS) {
        throw new IllegalArgumentException(
            "sqlTransformCompute.emptyResultPolicy must be SUCCESS when stagingMode=DIRECT"
                + " (DIRECT avoids a pre-count scan)");
      }
    }
    Set<String> reserved = new LinkedHashSet<>(RESERVED_PARAMS);
    for (String paramName : params.keySet()) {
      if (reserved.contains(paramName)) {
        throw new IllegalArgumentException(
            "sqlTransformCompute.params contains reserved parameter: " + paramName);
      }
      if (paramName != null && paramName.startsWith(METADATA_PARAM_PREFIX)) {
        throw new IllegalArgumentException(
            "sqlTransformCompute.params must not start with reserved prefix '"
                + METADATA_PARAM_PREFIX
                + "' (this namespace is for payload.metadata expansion): "
                + paramName);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> extractSpecRoot(
      Map<String, Object> stepParams, ObjectMapper objectMapper) {
    if (stepParams == null || stepParams.isEmpty()) {
      return Map.of();
    }
    Object nested =
        firstNonNull(
            stepParams.get("sqlTransformCompute"), stepParams.get("sql_transform_compute"));
    if (nested instanceof Map<?, ?> m) {
      return copyMap(m);
    }
    if (nested instanceof String text && Texts.hasText(text)) {
      try {
        return objectMapper.readValue(text, Map.class);
      } catch (Exception ignored) {
        SwallowedExceptionLogger.warn(SqlTransformComputeSpec.class, "catch:Exception", ignored);

        return Map.of();
      }
    }
    return new LinkedHashMap<>(stepParams);
  }

  private static WriteMode parseWriteMode(Object raw) {
    String value = text(raw);
    if (!Texts.hasText(value)) {
      return WriteMode.INSERT;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    try {
      return WriteMode.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      // P1-A2: 非法 enum 值不能让裸 IllegalArgumentException 被外层 catch(Exception) 包成 INFRA_ERROR，
      // 改抛 BizException(INVALID_ARGUMENT) 让 stage 路径识别为配置错误并保留 i18n 错误码。
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.process.invalid_write_mode", value);
    }
  }

  private static EmptyResultPolicy parseEmptyResultPolicy(Object raw) {
    String value = text(raw);
    if (!Texts.hasText(value)) {
      return EmptyResultPolicy.SUCCESS;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    try {
      return EmptyResultPolicy.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.process.invalid_empty_result_policy", value);
    }
  }

  private static StagingMode parseStagingMode(Object raw) {
    String value = text(raw);
    if (!Texts.hasText(value)) {
      return StagingMode.JSONB;
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    try {
      return StagingMode.valueOf(normalized);
    } catch (IllegalArgumentException ex) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.process.invalid_staging_mode", value);
    }
  }

  private static List<ColumnMapping> parseColumns(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<ColumnMapping> out = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof String name && Texts.hasText(name)) {
        String column = name.trim();
        out.add(new ColumnMapping(column, column));
      } else if (item instanceof Map<?, ?> m) {
        String source = text(firstNonNull(m.get("source"), m.get("from")));
        String target = text(firstNonNull(m.get("target"), m.get("to")));
        if (Texts.hasText(source) && Texts.hasText(target)) {
          out.add(new ColumnMapping(source, target));
        }
      }
    }
    return List.copyOf(out);
  }

  private static List<String> parseStringList(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object item : list) {
      String value = text(item);
      if (Texts.hasText(value)) {
        out.add(value);
      }
    }
    return List.copyOf(out);
  }

  private static Map<String, Object> parseMap(Object raw) {
    if (raw instanceof Map<?, ?> m) {
      return copyMap(m);
    }
    return Map.of();
  }

  private static Map<String, Object> copyMap(Map<?, ?> raw) {
    Map<String, Object> out = new LinkedHashMap<>();
    raw.forEach((key, value) -> out.put(String.valueOf(key), value));
    return out;
  }

  private static String required(Map<String, Object> root, String... keys) {
    String value = text(firstNonNull(values(root, keys)));
    if (!Texts.hasText(value)) {
      throw new IllegalArgumentException("sqlTransformCompute." + keys[0] + " is required");
    }
    return value;
  }

  private static Object[] values(Map<String, Object> root, String[] keys) {
    Object[] values = new Object[keys.length];
    for (int i = 0; i < keys.length; i++) {
      values[i] = root.get(keys[i]);
    }
    return values;
  }

  private static Object firstNonNull(Object... values) {
    for (Object value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private static String text(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value).trim();
    return Texts.hasText(text) ? text : null;
  }
}
