package io.github.pinpols.batch.worker.imports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import java.io.BufferedWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link JsonFormatParser} 单元测试:覆盖根数组、records 信封、单对象、空输入与 null 元素。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JSON 格式解析单测:根数组、信封对象、单对象、空输入与 null 元素语义")
class JsonFormatParserTest {

  @Mock
  private ImportRecordGovernanceService governanceService;

  private JsonFormatParser parser;

  @BeforeEach
  void setUp() {
    lenient().when(governanceService.isSkippable(anyString())).thenReturn(true);
    parser = new JsonFormatParser(new ParseSupport(new ObjectMapper(), governanceService));
  }

  @Test
  @DisplayName("根为数组时逐条输出记录并跳过 null 元素")
  void shouldParseEachElement_whenRootIsArrayWithNullElement() throws Exception {
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    long count = parse(ctx, "[{\"a\":1},null,{\"a\":2}]", sink);

    assertThat(count).isEqualTo(2L);
    assertThat(lines(sink)).containsExactly("{\"a\":1}", "{\"a\":2}");
  }

  @Test
  @DisplayName("根为 records 在前的信封对象时流式解析数组并跳过后续兄弟字段")
  void shouldStreamRecords_whenEnvelopeStartsWithRecords() throws Exception {
    StringWriter sink = new StringWriter();

    long count = parse(
        context(),
        "{\"records\":[{\"a\":1},{\"a\":2}],\"meta\":{\"v\":[1,2]},\"version\":3}",
        sink);

    assertThat(count).isEqualTo(2L);
    assertThat(lines(sink)).containsExactly("{\"a\":1}", "{\"a\":2}");
  }

  @Test
  @DisplayName("records 不在首位的信封对象回退树模型解析并跳过 null 元素")
  void shouldFallBackToTree_whenRecordsIsNotFirstField() throws Exception {
    StringWriter sink = new StringWriter();

    long count = parse(context(), "{\"meta\":1,\"records\":[{\"a\":1},null,{\"a\":2}]}", sink);

    assertThat(count).isEqualTo(2L);
    assertThat(lines(sink)).containsExactly("{\"a\":1}", "{\"a\":2}");
  }

  @Test
  @DisplayName("根为不含 records 数组的单个对象时作为一条记录输出")
  void shouldParseSingleRecord_whenRootIsPlainObject() throws Exception {
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    long count = parse(ctx, "{\"a\":1,\"b\":\"x\"}", sink);

    assertThat(count).isEqualTo(1L);
    assertThat(lines(sink)).containsExactly("{\"a\":1,\"b\":\"x\"}");
    assertThat(ctx.getAttributes()).containsEntry("schemaFields", List.of("a", "b"));
  }

  @Test
  @DisplayName("records 首位但不是数组时回退树模型,既有行为是按当前标量值登记坏行")
  void shouldRecordBadRecord_whenRecordsFieldIsNotArray() throws Exception {
    StringWriter sink = new StringWriter();

    long count = parse(context(), "{\"records\":5,\"a\":1}", sink);

    assertThat(count).isEqualTo(1L);
    assertThat(sink.toString()).isEmpty();
    verify(governanceService).recordSkippedRecord(any(), any());
  }

  @Test
  @DisplayName("空对象与空输入均返回零条记录")
  void shouldReturnZero_whenInputIsEmptyObjectOrBlank() throws Exception {
    StringWriter emptyObject = new StringWriter();
    StringWriter emptyText = new StringWriter();
    StringWriter blank = new StringWriter();

    assertThat(parse(context(), "{}", emptyObject)).isZero();
    assertThat(parse(context(), "", emptyText)).isZero();
    assertThat(parse(context(), "   ", blank)).isZero();
    assertThat(emptyObject.toString()).isEmpty();
    assertThat(emptyText.toString()).isEmpty();
  }

  @Test
  @DisplayName("根为 null 字面量时返回零条记录")
  void shouldReturnZero_whenRootIsNullLiteral() throws Exception {
    StringWriter sink = new StringWriter();

    assertThat(parse(context(), "null", sink)).isZero();
    assertThat(sink.toString()).isEmpty();
  }

  @Test
  @DisplayName("记录无法转换为对象时登记坏行并继续后续记录")
  void shouldRecordBadRecord_whenElementIsNotObject() throws Exception {
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    long count = parse(ctx, "[{\"a\":1},5,{\"a\":2}]", sink);

    assertThat(count).isEqualTo(3L);
    assertThat(lines(sink)).containsExactly("{\"a\":1}", "{\"a\":2}");
    verify(governanceService).recordSkippedRecord(any(), any());
  }

  private long parse(ImportJobContext ctx, String json, StringWriter sink) throws Exception {
    BufferedWriter writer = new BufferedWriter(sink);
    long count = parser.parse(ctx, FormatParseRequest.fromText(json, null, null, true), writer);
    writer.flush();
    return count;
  }

  private static List<String> lines(StringWriter sink) {
    return sink.toString().lines().toList();
  }

  private ImportJobContext context() {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId("t1");
    ctx.setWorkerId("w1");
    ctx.setFileId("1");
    return ctx;
  }
}
