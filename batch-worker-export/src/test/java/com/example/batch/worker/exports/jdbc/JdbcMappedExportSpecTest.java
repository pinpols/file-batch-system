package com.example.batch.worker.exports.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JdbcMappedExportSpecTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldParseTopLevelSpec() {
    Map<String, Object> template =
        Map.of(
            "jdbc_mapped_export",
            Map.of(
                "schema", "biz",
                "batchTable", "exp_batch",
                "batchTenantColumn", "tenant_id",
                "batchNoColumn", "batch_no",
                "batchSelectColumns", List.of("id", "status"),
                "detailTable", "exp_detail",
                "detailFkColumn", "batch_id",
                "detailOrderByColumn", "line_no",
                "detailSelectColumns", List.of("id", "amount")));
    JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(template, objectMapper);
    assertThat(spec.schema()).isEqualTo("biz");
    assertThat(spec.batchTable()).isEqualTo("exp_batch");
    assertThat(spec.batchSelectColumns()).containsExactly("id", "status");
    assertThat(spec.detailSelectColumns()).containsExactly("id", "amount");
  }

  @Test
  void shouldRejectMissingSpec() {
    assertThatThrownBy(() -> JdbcMappedExportSpec.parse(Map.of(), objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jdbc_mapped_export spec missing");
  }
}
