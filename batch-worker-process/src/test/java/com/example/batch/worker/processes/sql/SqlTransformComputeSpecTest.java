package com.example.batch.worker.processes.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlTransformComputeSpecTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void parse_acceptsNestedSqlTransformComputeSpec() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, account_id, amount from biz.order_event where tenant_id ="
                    + " :tenantId",
                "targetSchema",
                "biz",
                "targetTable",
                "daily_summary",
                "writeMode",
                "UPSERT",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "account_id", "target", "account_id"),
                    Map.of("source", "amount", "target", "total_amount")),
                "conflictColumns",
                List.of("tenant_id", "account_id"),
                "watermarkColumn",
                "event_id"));

    SqlTransformComputeSpec spec = SqlTransformComputeSpec.parse(stepParams, objectMapper);

    assertThat(spec.implCode()).isEqualTo(SqlTransformComputePlugin.PLUGIN_ID);
    assertThat(spec.targetSchema()).isEqualTo("biz");
    assertThat(spec.targetTable()).isEqualTo("daily_summary");
    assertThat(spec.writeMode()).isEqualTo(SqlTransformComputeSpec.WriteMode.UPSERT);
    assertThat(spec.stagingMode()).isEqualTo(SqlTransformComputeSpec.StagingMode.JSONB);
    assertThat(spec.columns())
        .extracting(SqlTransformComputeSpec.ColumnMapping::source)
        .containsExactly("tenant_id", "account_id", "amount");
    assertThat(spec.conflictColumns()).containsExactly("tenant_id", "account_id");
    assertThat(spec.watermarkColumn()).isEqualTo("event_id");
    assertThat(spec.validations()).isEmpty();
    assertThat(spec.emptyResultPolicy())
        .isEqualTo(SqlTransformComputeSpec.EmptyResultPolicy.SUCCESS);
  }

  @Test
  void parse_acceptsValidationRules() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src",
                "targetTable",
                "daily_summary",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id"),
                "validations",
                List.of(
                    Map.of(
                        "name",
                        "amount_non_negative",
                        "checkSql",
                        "select bool_and((payload->>'amount')::numeric >= 0) AS pass,"
                            + " 'has negative amounts' AS message"
                            + " from batch.process_staging where batch_key = :batchKey"))));

    SqlTransformComputeSpec spec = SqlTransformComputeSpec.parse(stepParams, objectMapper);

    assertThat(spec.validations()).hasSize(1);
    assertThat(spec.validations().get(0).name()).isEqualTo("amount_non_negative");
    assertThat(spec.validations().get(0).checkSql()).contains("bool_and");
  }

  @Test
  void parse_rejectsMissingColumns() {
    Map<String, Object> stepParams =
        Map.of(
            "sourceSql",
            "select tenant_id from biz.order_event",
            "targetSchema",
            "biz",
            "targetTable",
            "daily_summary");

    assertThatThrownBy(() -> SqlTransformComputeSpec.parse(stepParams, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("columns");
  }

  @Test
  void parse_rejectsParamsOverridingReservedRuntimeNames() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src where tenant_id = :tenantId",
                "targetTable",
                "daily_summary",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id"),
                "params",
                Map.of("tenantId", "evil-tenant")));

    assertThatThrownBy(() -> SqlTransformComputeSpec.parse(stepParams, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved parameter");
  }

  @Test
  void parse_rejectsParamsOverridingBizDate() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src where biz_date = cast(:bizDate as date)",
                "targetTable",
                "daily_summary",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id"),
                "params",
                Map.of("bizDate", "1970-01-01")));

    assertThatThrownBy(() -> SqlTransformComputeSpec.parse(stepParams, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bizDate");
  }

  @Test
  void parse_rejectsParamsUsingMetadataPrefix() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src where x = :metadata_customer",
                "targetTable",
                "daily_summary",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id"),
                "params",
                Map.of("metadata_customer", "X")));

    assertThatThrownBy(() -> SqlTransformComputeSpec.parse(stepParams, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("metadata_");
  }

  @Test
  void parse_acceptsEmptyResultPolicySuccess() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src",
                "targetTable",
                "daily_summary",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id"),
                "emptyResultPolicy",
                "SUCCESS"));

    SqlTransformComputeSpec spec = SqlTransformComputeSpec.parse(stepParams, objectMapper);

    assertThat(spec.emptyResultPolicy())
        .isEqualTo(SqlTransformComputeSpec.EmptyResultPolicy.SUCCESS);
  }

  @Test
  void parse_defaultsMaxStagedRows() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src",
                "targetTable",
                "summary",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id")));

    SqlTransformComputeSpec spec = SqlTransformComputeSpec.parse(stepParams, objectMapper);

    assertThat(spec.maxStagedRows()).isEqualTo(SqlTransformComputeSpec.DEFAULT_MAX_STAGED_ROWS);
  }

  @Test
  void parse_acceptsCustomMaxStagedRows() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src",
                "targetTable",
                "summary",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id"),
                "maxStagedRows",
                500));

    SqlTransformComputeSpec spec = SqlTransformComputeSpec.parse(stepParams, objectMapper);

    assertThat(spec.maxStagedRows()).isEqualTo(500);
  }

  @Test
  void parse_acceptsDirectStagingMode() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src",
                "targetTable",
                "summary",
                "stagingMode",
                "DIRECT",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id")));

    SqlTransformComputeSpec spec = SqlTransformComputeSpec.parse(stepParams, objectMapper);

    assertThat(spec.stagingMode()).isEqualTo(SqlTransformComputeSpec.StagingMode.DIRECT);
  }

  @Test
  void parse_rejectsDirectModeWithStagingValidations() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src",
                "targetTable",
                "summary",
                "stagingMode",
                "DIRECT",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id"),
                "validations",
                List.of(
                    Map.of(
                        "name",
                        "staged_rows_present",
                        "checkSql",
                        "select count(*) > 0 as pass from batch.process_staging"))));

    assertThatThrownBy(() -> SqlTransformComputeSpec.parse(stepParams, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("validations are not supported")
        .hasMessageContaining("stagingMode=DIRECT");
  }

  @Test
  void parse_rejectsDirectModeWithFailEmptyResultPolicy() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src",
                "targetTable",
                "summary",
                "stagingMode",
                "DIRECT",
                "emptyResultPolicy",
                "FAIL",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount")),
                "conflictColumns",
                List.of("tenant_id")));

    assertThatThrownBy(() -> SqlTransformComputeSpec.parse(stepParams, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("emptyResultPolicy must be SUCCESS")
        .hasMessageContaining("stagingMode=DIRECT");
  }

  @Test
  void parse_rejectsMissingConflictColumnsForInsertMode() {
    // PROCESS at-least-once 安全:即使 writeMode=INSERT 也必须提供 conflictColumns
    // (否则重放双写)。验证空 conflictColumns 一律被拒,不再因 writeMode 网开一面。
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id, amount from biz.src",
                "targetTable",
                "daily_summary",
                "writeMode",
                "INSERT",
                "columns",
                List.of(
                    Map.of("source", "tenant_id", "target", "tenant_id"),
                    Map.of("source", "amount", "target", "amount"))));

    assertThatThrownBy(() -> SqlTransformComputeSpec.parse(stepParams, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("conflictColumns is required");
  }

  @Test
  void parse_rejectsInvalidMaxStagedRows() {
    Map<String, Object> stepParams =
        Map.of(
            "sqlTransformCompute",
            Map.of(
                "sourceSql",
                "select tenant_id from biz.src",
                "targetTable",
                "summary",
                "columns",
                List.of(Map.of("source", "tenant_id", "target", "tenant_id")),
                "conflictColumns",
                List.of("tenant_id"),
                "maxStagedRows",
                0));

    assertThatThrownBy(() -> SqlTransformComputeSpec.parse(stepParams, objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxStagedRows");
  }
}
