package io.github.pinpols.batch.worker.imports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** {@link FixedWidthFormatParser} 单元测试:覆盖正常行、短行、空行、表头/表尾与字段溢出。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("定长格式解析单测:正常行、短行、空行、表头表尾与字段溢出语义")
class FixedWidthFormatParserTest {

  @Mock
  private ImportRecordGovernanceService governanceService;

  private FixedWidthFormatParser parser;

  @BeforeEach
  void setUp() {
    lenient().when(governanceService.isSkippable(anyString())).thenReturn(true);
    parser = new FixedWidthFormatParser(new ParseSupport(new ObjectMapper(), governanceService));
  }

  @Test
  @DisplayName("正常行按起始位置与长度切片并去除空白")
  void shouldSliceFields_whenLineIsComplete() throws Exception {
    StringWriter sink = new StringWriter();

    long count = parse(template(0, 0, 0), "T001 100\nT002 200\n", sink);

    assertThat(count).isEqualTo(2L);
    assertThat(sink.toString().lines().toList())
        .containsExactly("{\"no\":\"T001\",\"amt\":\"100\"}", "{\"no\":\"T002\",\"amt\":\"200\"}");
  }

  @Test
  @DisplayName("空行不计入记录序号")
  void shouldSkipBlankLines_whenCountingRecords() throws Exception {
    StringWriter sink = new StringWriter();

    long count = parse(template(0, 0, 0), "T001 100\n\n   \nT002 200\n", sink);

    assertThat(count).isEqualTo(2L);
    assertThat(sink.toString().lines().toList()).hasSize(2);
  }

  @Test
  @DisplayName("短于 record_length 的行登记坏行并继续,记录序号照常递增")
  void shouldRecordBadRecord_whenLineShorterThanRecordLength() throws Exception {
    StringWriter sink = new StringWriter();

    long count = parse(template(0, 0, 8), "T001 100\nT002\nT003 300\n", sink);

    assertThat(count).isEqualTo(3L);
    assertThat(sink.toString().lines().toList()).hasSize(2);
    verify(governanceService, times(1)).recordSkippedRecord(any(), any());
  }

  @Test
  @DisplayName("字段越过行尾时登记坏行而不写出该行")
  void shouldRecordBadRecord_whenFieldOverflowsLine() throws Exception {
    StringWriter sink = new StringWriter();

    long count = parse(template(0, 0, 0), "T001 100\nT002\n", sink);

    assertThat(count).isEqualTo(2L);
    assertThat(sink.toString().lines().toList()).hasSize(1);
    verify(governanceService, times(1)).recordSkippedRecord(any(), any());
  }

  @Test
  @DisplayName("按 header_rows 与 footer_rows 跳过表头和表尾")
  void shouldSkipHeaderAndFooter_whenRowsConfigured() throws Exception {
    StringWriter sink = new StringWriter();

    long count = parse(template(1, 1, 0), "HEADER\nT001 100\nT002 200\nFOOTER\n", sink);

    assertThat(count).isEqualTo(2L);
    assertThat(sink.toString()).contains("T001").contains("T002").doesNotContain("FOOTER");
  }

  @Test
  @DisplayName("没有可用字段映射时抛出非法状态异常")
  void shouldFail_whenFieldMappingsMissing() {
    assertThatThrownBy(() -> parse(Map.of(), "T001 100\n", new StringWriter()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("field_mappings");
  }

  @Test
  @DisplayName("空文本直接返回零且不读取字段映射")
  void shouldReturnZero_whenNoText() throws Exception {
    StringWriter sink = new StringWriter();

    assertThat(parse(Map.of(), "", sink)).isZero();
    verify(governanceService, never()).recordSkippedRecord(any(), any());
  }

  private static Map<String, Object> template(int header, int footer, int recordLength) {
    return Map.of(
        "header_rows",
        header,
        "footer_rows",
        footer,
        "record_length",
        recordLength,
        "field_mappings",
        List.of(
            Map.of("target", "no", "start", 0, "length", 5),
            Map.of("target", "amt", "start", 5, "length", 3)));
  }

  private long parse(Map<String, Object> template, String text, StringWriter sink)
      throws Exception {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId("t1");
    ctx.setWorkerId("w1");
    ctx.setFileId("1");
    BufferedWriter writer = new BufferedWriter(sink);
    long count = parser.parse(ctx, FormatParseRequest.fromText(text, null, template, true), writer);
    writer.flush();
    return count;
  }
}
