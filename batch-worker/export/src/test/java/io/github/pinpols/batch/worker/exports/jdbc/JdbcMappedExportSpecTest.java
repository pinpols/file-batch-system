package io.github.pinpols.batch.worker.exports.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JDBC 映射导出规格解析单测:列缺省推断与显式配置优先级语义")
class JdbcMappedExportSpecTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("解析顶层配置:库名,批次表与两侧查询列都正确取出")
  void shouldParseTopLevelSpec() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("缺少规格配置时抛参数非法,并提示规格缺失")
  void shouldRejectMissingSpec() {
    assertThatThrownBy(() -> JdbcMappedExportSpec.parse(Map.of(), objectMapper))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("jdbc_mapped_export spec missing");
  }

  @Test
  @DisplayName("未显式给出明细列时,从字段映射推断并按去重保序")
  void shouldInferDetailSelectColumnsFromFieldMappingsWhenOmitted() {
    Map<String, Object> template = Map.of(
        "field_mappings",
            List.of(
                Map.of("name", "customerNo", "sourceColumn", "customer_no"),
                Map.of("name", "customerName", "sourceColumn", "customer_name"),
                // 重复 sourceColumn 去重保序;无 sourceColumn 的项跳过
                Map.of("name", "customerNoAgain", "sourceColumn", "customer_no"),
                Map.of("name", "header_only")),
        "jdbc_mapped_export",
            Map.of(
                "schema", "biz",
                "batchTable", "exp_batch",
                "batchTenantColumn", "tenant_id",
                "batchNoColumn", "batch_no",
                "batchSelectColumns", List.of("id", "status"),
                "detailTable", "exp_detail",
                "detailFkColumn", "batch_id",
                "detailOrderByColumn", "line_no"));
    JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(template, objectMapper);
    assertThat(spec.detailSelectColumns()).containsExactly("customer_no", "customer_name");
  }

  @Test
  @DisplayName("字段映射与显式明细列同时存在时,显式配置优先")
  void shouldPreferExplicitDetailColumns_whenFieldMappingsAlsoPresent() {
    Map<String, Object> template = Map.of(
        "field_mappings", List.of(Map.of("name", "x", "sourceColumn", "col_x")),
        "jdbc_mapped_export",
            Map.of(
                "schema", "biz",
                "batchTable", "exp_batch",
                "batchTenantColumn", "tenant_id",
                "batchNoColumn", "batch_no",
                "batchSelectColumns", List.of("id"),
                "detailTable", "exp_detail",
                "detailFkColumn", "batch_id",
                "detailOrderByColumn", "line_no",
                "detailSelectColumns", List.of("id", "amount")));
    JdbcMappedExportSpec spec = JdbcMappedExportSpec.parse(template, objectMapper);
    assertThat(spec.detailSelectColumns()).containsExactly("id", "amount");
  }
}
