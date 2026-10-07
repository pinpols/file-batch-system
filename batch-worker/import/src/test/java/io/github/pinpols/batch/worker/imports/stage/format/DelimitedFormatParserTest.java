package io.github.pinpols.batch.worker.imports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link DelimitedFormatParser} 单元测试:聚焦无表头时按 field_mappings 顺序位置绑定(及历史 schema 回退)。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("分隔符格式解析单测:无表头按映射顺序绑定与有表头必填校验语义")
class DelimitedFormatParserTest {

  @Mock
  private ImportRecordGovernanceService governanceService;

  private DelimitedFormatParser parser;

  @BeforeEach
  void setUp() {
    parser = new DelimitedFormatParser(new ParseSupport(new ObjectMapper(), governanceService));
  }

  @Test
  @DisplayName("无表头文件按字段映射顺序绑定列,不回退到示例结构")
  void shouldBindColumnsByMappingOrder_whenHeaderless() throws Exception {
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
  @DisplayName("无表头且无字段映射时,回退到默认结构解析")
  void shouldFallBackToDefaultSchema_whenNoFieldMappings() throws Exception {
    String csv = "C001,Alice,PERSONAL,ID001,13800000001,alice@example.com,ACTIVE\n";
    Map<String, Object> tpl = Map.of("jdbc_mapped_import", Map.of());
    StringWriter sink = new StringWriter();

    long count = parser.parse(context(), request(csv, tpl), new BufferedWriter(sink));

    assertThat(count).isEqualTo(1L);
    assertThat(sink.toString()).contains("customerNo").contains("C001");
  }

  @Test
  @DisplayName("表头缺少必填列时解析阶段立即失败,并提示表头缺列")
  void shouldFailFast_whenRequiredColumnMissingFromHeader() {
    String csv = "customerNo,customerName\nC001,Alice\n";
    Map<String, Object> tpl = Map.of(
        "header_rows",
        1,
        "field_mappings",
        List.of(
            Map.of("name", "customerNo", "required", true),
            // email 必填,但文件表头里没有 → PARSE 期 fail-fast
            Map.of("name", "email", "required", true)));
    StringWriter sink = new StringWriter();

    assertThatThrownBy(() -> parser.parse(context(), request(csv, tpl), new BufferedWriter(sink)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("delimited_header_missing");
  }

  @Test
  @DisplayName("非必填列在表头缺失时,允许解析通过")
  void shouldAllowMissingColumn_whenColumnOptional() throws Exception {
    String csv = "customerNo\nC001\n";
    Map<String, Object> tpl = Map.of(
        "header_rows",
        1,
        "field_mappings",
        List.of(
            Map.of("name", "customerNo", "required", true),
            // email 非必填 → 表头缺失允许
            Map.of("name", "email")));
    StringWriter sink = new StringWriter();

    long count = parser.parse(context(), request(csv, tpl), new BufferedWriter(sink));

    assertThat(count).isEqualTo(1L);
  }

  @Test
  @DisplayName("表头大小写与下划线写法差异不影响必填列匹配")
  void shouldMatchHeaderIgnoringCaseAndUnderscore_whenRequiredColumnDeclared() throws Exception {
    // 文件表头 CUSTOMER_NO / Customer_Name 与 field_mappings 的 customerNo / customerName 仅写法不同
    String csv = "CUSTOMER_NO,Customer_Name\nC001,Alice\n";
    Map<String, Object> tpl = Map.of(
        "header_rows",
        1,
        "field_mappings",
        List.of(
            Map.of("name", "customerNo", "required", true),
            Map.of("name", "customerName", "required", true)));
    StringWriter sink = new StringWriter();

    long count = parser.parse(context(), request(csv, tpl), new BufferedWriter(sink));

    assertThat(count).isEqualTo(1L);
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
