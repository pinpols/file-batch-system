package com.example.batch.worker.imports.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.exception.WorkerConfigException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class JdbcMappedImportSpecTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldParseTopLevelJdbcMappedImport() {
    Map<String, Object> template =
        Map.of(
            "jdbc_mapped_import",
            Map.of(
                "schema",
                "biz",
                "table",
                "imp_orders",
                "tenantColumn",
                "tenant_id",
                "columnMappings",
                List.of(Map.of("from", "col_a", "to", "col_a")),
                "conflictColumns",
                List.of("id")));
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);
    assertThat(spec.schema()).isEqualTo("biz");
    assertThat(spec.table()).isEqualTo("imp_orders");
    assertThat(spec.tenantColumn()).isEqualTo("tenant_id");
    assertThat(spec.columnMappings()).hasSize(1);
    assertThat(spec.conflictColumns()).containsExactly("id");
  }

  @Test
  void shouldRejectMissingSpec() {
    assertThatThrownBy(() -> JdbcMappedImportSpec.parse(Map.of(), objectMapper))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("jdbc_mapped_import spec missing");
  }

  @Test
  void shouldParseJdbcMappedImportWhenQueryParamSchemaIsPgJsonObject() throws Exception {
    Map<String, Object> qps =
        Map.of(
            "jdbcMappedImport",
            Map.of(
                "schema",
                "biz",
                "table",
                "customer_account",
                "tenantColumn",
                "tenant_id",
                "columnMappings",
                List.of(Map.of("from", "customerNo", "to", "customer_no")),
                "conflictColumns",
                List.of("tenant_id", "customer_no")));
    PGobject pg = new PGobject();
    pg.setType("jsonb");
    pg.setValue(objectMapper.writeValueAsString(qps));
    Map<String, Object> template = Map.of("query_param_schema", pg);
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);
    assertThat(spec.table()).isEqualTo("customer_account");
    assertThat(spec.columnMappings()).hasSize(1);
  }
}
