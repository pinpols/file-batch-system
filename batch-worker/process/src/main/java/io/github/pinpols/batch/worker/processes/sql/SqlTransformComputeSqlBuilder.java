package io.github.pinpols.batch.worker.processes.sql;

import io.github.pinpols.batch.common.jdbc.JdbcMappedSqlValidator;
import io.github.pinpols.batch.common.utils.Texts;
import java.util.stream.Collectors;

/** 构建 SQL 转换插件在暂存和发布阶段使用的语句。 */
final class SqlTransformComputeSqlBuilder {

  private static final String ON_CONFLICT_PREFIX = " ON CONFLICT (";

  private SqlTransformComputeSqlBuilder() {}

  /** 构建 COMPUTE 插入语句，并再次校验映射标识符。 */
  static String buildStagingInsertSql(SqlTransformComputeSpec spec) {
    String jsonbBuild = spec.columns().stream()
        .map(column -> "'"
            + JdbcMappedSqlValidator.requireIdentifier(column.target(), "column.target")
            + "', base."
            + JdbcMappedSqlValidator.quotePg(column.source()))
        .collect(Collectors.joining(", "));
    return """
    INSERT INTO %s (batch_key, tenant_id, target_schema, target_table, payload)
    SELECT :batchKey, :tenantId, :targetSchema, :targetTable, jsonb_build_object(%s)
    FROM (
    %s
    ) base
    """.formatted(SqlTransformComputeConstants.STAGING_TABLE, jsonbBuild, spec.sourceSql());
  }

  /** 构建 WAP 发布语句；冲突处理确保提交重试具备幂等性。 */
  static String buildPublishSql(SqlTransformComputeSpec spec) {
    String sql = """
        INSERT INTO %s (%s)
        SELECT %s
        FROM (
            SELECT jsonb_populate_record(NULL::%s, payload) AS rec
            FROM %s
            WHERE batch_key = :batchKey
              AND tenant_id = :tenantId
              AND target_schema = :targetSchema
              AND target_table = :targetTable
        ) staged
        ORDER BY %s
        """.formatted(
            targetName(spec),
            targetColumnList(spec),
            jsonbRecordSelectColumns(spec),
            targetName(spec),
            SqlTransformComputeConstants.STAGING_TABLE,
            conflictOrderByColumns(spec, false));
    return appendConflictClause(sql, spec);
  }

  /** 构建不使用 JSONB 暂存表的 DIRECT 发布语句。 */
  static String buildDirectPublishSql(SqlTransformComputeSpec spec) {
    String sql = """
        INSERT INTO %s (%s)
        SELECT %s
        FROM (
        %s
        ) base
        ORDER BY %s
        """.formatted(
            targetName(spec),
            targetColumnList(spec),
            directSourceSelectColumns(spec),
            spec.sourceSql(),
            conflictOrderByColumns(spec, true));
    return appendConflictClause(sql, spec);
  }

  /** 通过一条 DIRECT 写入语句返回发布行数和水位值。 */
  static String buildDirectPublishMetricsSql(SqlTransformComputeSpec spec) {
    String watermarkColumn = JdbcMappedSqlValidator.quotePg(spec.watermarkColumn());
    return """
    WITH published AS (
    %s
    RETURNING %s AS high_water_mark
    )
    SELECT count(*) AS published_rows, max(high_water_mark) AS high_water_mark
    FROM published
    """.formatted(buildDirectPublishSql(spec), watermarkColumn);
  }

  private static String targetName(SqlTransformComputeSpec spec) {
    return JdbcMappedSqlValidator.quotePg(spec.targetSchema())
        + "."
        + JdbcMappedSqlValidator.quotePg(spec.targetTable());
  }

  private static String targetColumnList(SqlTransformComputeSpec spec) {
    return spec.columns().stream()
        .map(SqlTransformComputeSpec.ColumnMapping::target)
        .map(JdbcMappedSqlValidator::quotePg)
        .collect(Collectors.joining(", "));
  }

  private static String jsonbRecordSelectColumns(SqlTransformComputeSpec spec) {
    return spec.columns().stream()
        .map(column -> "(rec)." + JdbcMappedSqlValidator.quotePg(column.target()))
        .collect(Collectors.joining(", "));
  }

  private static String directSourceSelectColumns(SqlTransformComputeSpec spec) {
    return spec.columns().stream()
        .map(column -> "base." + JdbcMappedSqlValidator.quotePg(column.source()))
        .collect(Collectors.joining(", "));
  }

  private static String conflictOrderByColumns(SqlTransformComputeSpec spec, boolean direct) {
    return spec.conflictColumns().stream()
        .map(target -> conflictOrderByExpression(spec, target, direct))
        .collect(Collectors.joining(", "));
  }

  private static String conflictOrderByExpression(
      SqlTransformComputeSpec spec, String target, boolean direct) {
    SqlTransformComputeSpec.ColumnMapping mapping = spec.columns().stream()
        .filter(column -> column.target().equals(target))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(
            "sqlTransformCompute.conflictColumns must appear in target columns: " + target));
    if (direct) {
      return "base." + JdbcMappedSqlValidator.quotePg(mapping.source());
    }
    return "(rec)." + JdbcMappedSqlValidator.quotePg(mapping.target());
  }

  private static String appendConflictClause(String sql, SqlTransformComputeSpec spec) {
    // 提交成功但报告丢失时，Worker 可能重试；因此每种写入模式都必须保留冲突处理。
    String conflictColumns = spec.conflictColumns().stream()
        .map(JdbcMappedSqlValidator::quotePg)
        .collect(Collectors.joining(", "));
    if (spec.writeMode() == SqlTransformComputeSpec.WriteMode.INSERT
        || spec.writeMode() == SqlTransformComputeSpec.WriteMode.INSERT_IGNORE) {
      return sql + ON_CONFLICT_PREFIX + conflictColumns + ") DO NOTHING";
    }
    String update = spec.columns().stream()
        .map(SqlTransformComputeSpec.ColumnMapping::target)
        .filter(column -> !spec.conflictColumns().contains(column))
        .map(column -> {
          String quoted = JdbcMappedSqlValidator.quotePg(column);
          return quoted + " = EXCLUDED." + quoted;
        })
        .collect(Collectors.joining(", "));
    if (!Texts.hasText(update)) {
      return sql + ON_CONFLICT_PREFIX + conflictColumns + ") DO NOTHING";
    }
    return sql + ON_CONFLICT_PREFIX + conflictColumns + ") DO UPDATE SET " + update;
  }
}
