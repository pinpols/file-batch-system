package io.github.pinpols.batch.worker.exports.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

@DisplayName("模板导出规格解析单测:游标列缺省值与嵌套配置覆盖语义")
class SqlTemplateExportSpecTest {

  @Test
  @DisplayName("未配置游标列时,缺省使用主键列")
  void parse_shouldDefaultCursorToId() {
    SqlTemplateExportSpec spec = SqlTemplateExportSpec.parse(
        Map.of(
            "default_query_sql",
            "select id, name from t where tenant_id = :tenantId and batch_no = :batchNo"),
        new ObjectMapper());

    assertThat(spec.cursorColumn()).isEqualTo("id");
  }

  @Test
  @DisplayName("查询参数模板里配置游标列时,按配置值生效")
  void parse_shouldReadCursorFromQueryParamSchema() {
    SqlTemplateExportSpec spec = SqlTemplateExportSpec.parse(
        Map.of(
            "default_query_sql",
            "select id, name, created_at from t where tenant_id = :tenantId and batch_no ="
                + " :batchNo",
            "query_param_schema",
            Map.of("sqlTemplateExport", Map.of("cursorColumn", "created_at"))),
        new ObjectMapper());

    assertThat(spec.cursorColumn()).isEqualTo("created_at");
  }

  @Test
  @DisplayName("数据库 jsonb 形态的查询参数模板同样能解析出游标列")
  void parse_shouldReadCursorFromPostgresJsonbQueryParamSchema() {
    PGobject queryParamSchema = new PGobject();
    Assertions.assertDoesNotThrow(() -> {
      queryParamSchema.setType("jsonb");
      queryParamSchema.setValue("{\"sqlTemplateExport\":{\"cursorColumn\":\"created_at\"}}");
    });

    SqlTemplateExportSpec spec = SqlTemplateExportSpec.parse(
        Map.of(
            "default_query_sql",
            "select id, name, created_at from t where tenant_id = :tenantId and batch_no ="
                + " :batchNo",
            "query_param_schema",
            queryParamSchema),
        new ObjectMapper());

    assertThat(spec.cursorColumn()).isEqualTo("created_at");
  }
}
