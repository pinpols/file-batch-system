package io.github.pinpols.batch.worker.processes.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SQL 转换语句构建器:暂存、发布、冲突与水位 SQL")
class SqlTransformComputeSqlBuilderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("构建暂存 SQL 时映射目标列并引用源列")
  void shouldBuildStagingInsertSql() {
    String sql = SqlTransformComputeSqlBuilder.buildStagingInsertSql(spec("UPSERT", "JSONB", true));

    assertThat(sql)
        .contains("INSERT INTO batch.process_staging")
        .contains("'account_id', base.\"source_account\"")
        .contains("'amount', base.\"source_amount\"")
        .contains(":batchKey", "select source_account, source_amount from biz.source_rows");
  }

  @Test
  @DisplayName("JSONB 发布语句反序列化目标记录并生成 UPSERT")
  void shouldBuildJsonbPublishSql() {
    String sql = SqlTransformComputeSqlBuilder.buildPublishSql(spec("UPSERT", "JSONB", true));

    assertThat(sql)
        .contains("INSERT INTO \"biz\".\"daily_summary\"")
        .contains("jsonb_populate_record(NULL::\"biz\".\"daily_summary\", payload)")
        .contains("ORDER BY (rec).\"account_id\"")
        .contains("ON CONFLICT (\"account_id\") DO UPDATE SET \"amount\" = EXCLUDED.\"amount\"");
  }

  @Test
  @DisplayName("DIRECT 发布语句引用源字段并为追加写入生成 DO NOTHING")
  void shouldBuildDirectInsertSql() {
    String sql =
        SqlTransformComputeSqlBuilder.buildDirectPublishSql(spec("INSERT", "DIRECT", false));

    assertThat(sql)
        .contains("base.\"source_account\", base.\"source_amount\"")
        .contains("ORDER BY base.\"source_account\"")
        .contains("ON CONFLICT (\"account_id\") DO NOTHING");
  }

  @Test
  @DisplayName("DIRECT 水位发布 SQL 汇总发布数量和最高水位")
  void shouldBuildDirectPublishMetricsSql() {
    String sql =
        SqlTransformComputeSqlBuilder.buildDirectPublishMetricsSql(spec("UPSERT", "DIRECT", true));

    assertThat(sql)
        .contains("WITH published AS (")
        .contains("RETURNING \"event_id\" AS high_water_mark")
        .contains("count(*) AS published_rows, max(high_water_mark) AS high_water_mark");
  }

  @Test
  @DisplayName("冲突列也是唯一目标列时 UPSERT 退化为 DO NOTHING")
  void shouldAvoidEmptyUpdateClause() {
    SqlTransformComputeSpec spec = specWithColumns(
        "UPSERT",
        List.of(Map.of("source", "account_id", "target", "account_id")),
        List.of("account_id"));

    assertThat(SqlTransformComputeSqlBuilder.buildDirectPublishSql(spec))
        .contains("ON CONFLICT (\"account_id\") DO NOTHING")
        .doesNotContain("DO UPDATE SET");
  }

  @Test
  @DisplayName("缺少冲突目标映射時阻止生成不稳定排序 SQL")
  void shouldRejectConflictColumnWithoutMapping() {
    SqlTransformComputeSpec invalid = new SqlTransformComputeSpec(
        SqlTransformComputePlugin.PLUGIN_ID,
        "select source_account from biz.source_rows",
        "biz",
        "daily_summary",
        SqlTransformComputeSpec.WriteMode.INSERT,
        SqlTransformComputeSpec.StagingMode.DIRECT,
        List.of(new SqlTransformComputeSpec.ColumnMapping("source_account", "account_id")),
        List.of("missing_column"),
        null,
        Map.of(),
        List.of(),
        SqlTransformComputeSpec.EmptyResultPolicy.SUCCESS,
        SqlTransformComputeSpec.DEFAULT_MAX_STAGED_ROWS);

    assertThatThrownBy(() -> SqlTransformComputeSqlBuilder.buildDirectPublishSql(invalid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflictColumns must appear in target columns");
  }

  private SqlTransformComputeSpec spec(String writeMode, String stagingMode, boolean watermark) {
    return specWithColumns(
        writeMode,
        List.of(
            Map.of("source", "source_account", "target", "account_id"),
            Map.of("source", "source_amount", "target", "amount")),
        List.of("account_id"),
        stagingMode,
        watermark);
  }

  private SqlTransformComputeSpec specWithColumns(
      String writeMode, List<Map<String, String>> columns, List<String> conflictColumns) {
    return specWithColumns(writeMode, columns, conflictColumns, "DIRECT", false);
  }

  private SqlTransformComputeSpec specWithColumns(
      String writeMode,
      List<Map<String, String>> columns,
      List<String> conflictColumns,
      String stagingMode,
      boolean watermark) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("sourceSql", "select source_account, source_amount from biz.source_rows");
    values.put("targetSchema", "biz");
    values.put("targetTable", "daily_summary");
    values.put("writeMode", writeMode);
    values.put("stagingMode", stagingMode);
    values.put("columns", columns);
    values.put("conflictColumns", conflictColumns);
    if (watermark) {
      values.put("watermarkColumn", "event_id");
    }
    return SqlTransformComputeSpec.parse(Map.of("sqlTransformCompute", values), objectMapper);
  }
}
