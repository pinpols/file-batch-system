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
    assertThat(spec.columns())
        .extracting(SqlTransformComputeSpec.ColumnMapping::source)
        .containsExactly("tenant_id", "account_id", "amount");
    assertThat(spec.conflictColumns()).containsExactly("tenant_id", "account_id");
    assertThat(spec.watermarkColumn()).isEqualTo("event_id");
    assertThat(spec.validations()).isEmpty();
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
}
