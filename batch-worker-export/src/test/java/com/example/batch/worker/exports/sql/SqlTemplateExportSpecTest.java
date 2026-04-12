package com.example.batch.worker.exports.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlTemplateExportSpecTest {

  @Test
  void parse_shouldDefaultCursorToId() {
    SqlTemplateExportSpec spec =
        SqlTemplateExportSpec.parse(
            Map.of(
                "default_query_sql",
                "select id, name from t where tenant_id = :tenantId and batch_no = :batchNo"),
            new ObjectMapper());

    assertThat(spec.cursorColumn()).isEqualTo("id");
  }

  @Test
  void parse_shouldReadCursorFromQueryParamSchema() {
    SqlTemplateExportSpec spec =
        SqlTemplateExportSpec.parse(
            Map.of(
                "default_query_sql",
                "select id, name from t where tenant_id = :tenantId and batch_no = :batchNo",
                "query_param_schema",
                Map.of("sqlTemplateExport", Map.of("cursorColumn", "created_at"))),
            new ObjectMapper());

    assertThat(spec.cursorColumn()).isEqualTo("created_at");
  }
}
