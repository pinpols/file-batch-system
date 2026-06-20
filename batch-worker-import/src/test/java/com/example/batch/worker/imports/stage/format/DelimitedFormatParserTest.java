package com.example.batch.worker.imports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link DelimitedFormatParser} 单元测试:聚焦无表头时按 field_mappings 顺序位置绑定(及历史 schema 回退)。 */
@ExtendWith(MockitoExtension.class)
class DelimitedFormatParserTest {

  @Mock private ImportRecordGovernanceService governanceService;

  private DelimitedFormatParser parser;

  @BeforeEach
  void setUp() {
    parser = new DelimitedFormatParser(new ParseSupport(new ObjectMapper(), governanceService));
  }

  @Test
  void headerlessBindsColumnsByFieldMappingsOrder() throws Exception {
    String csv = "T001,100.00\n";
    Map<String, Object> tpl =
        Map.of("field_mappings", List.of(Map.of("name", "txnNo"), Map.of("name", "amount")));
    StringWriter sink = new StringWriter();

    long count = parser.parse(context(), request(csv, tpl), new BufferedWriter(sink));

    assertThat(count).isEqualTo(1L);
    assertThat(sink.toString())
        .contains("txnNo")
        .contains("amount")
        .contains("T001")
        // 不再回退硬编码 customer 示例 schema
        .doesNotContain("customerNo");
  }

  @Test
  void headerlessFallsBackToDefaultSchemaWhenNoFieldMappings() throws Exception {
    String csv = "C001,Alice,PERSONAL,ID001,13800000001,alice@example.com,ACTIVE\n";
    Map<String, Object> tpl = Map.of("jdbc_mapped_import", Map.of());
    StringWriter sink = new StringWriter();

    long count = parser.parse(context(), request(csv, tpl), new BufferedWriter(sink));

    assertThat(count).isEqualTo(1L);
    assertThat(sink.toString()).contains("customerNo").contains("C001");
  }

  private ImportJobContext context() {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId("t1");
    ctx.setWorkerId("w1");
    ctx.setFileId("1");
    return ctx;
  }

  private FormatParseRequest request(String csv, Object templateConfig) {
    // importPayload=null → withHeader 取 headerRows(0) → 走无表头位置绑定路径
    return new FormatParseRequest(csv, null, null, templateConfig, true);
  }
}
