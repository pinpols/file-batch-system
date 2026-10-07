package io.github.pinpols.batch.worker.imports.stage.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.testing.TestExcelFileBuilder;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService.BadRecordCommand;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService.SourceLocator;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link ExcelFormatParser} 单元测试:覆盖 .xls fail-fast、物理行号/列定位、表头校验、表头直通、preview、按名选 sheet。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("表格格式解析单测:旧二进制格式拒绝,表头映射与工作表选择及坏行定位语义")
class ExcelFormatParserTest {

  @Mock
  private ImportRecordGovernanceService governanceService;

  private ExcelFormatParser parser;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    parser = new ExcelFormatParser(new ParseSupport(objectMapper, governanceService));
  }

  // ── Item 1: 二进制 .xls (OLE2/HSSF) fail-fast ──────────────────────────────

  @Test
  @DisplayName("旧版二进制表格格式被拒绝,并给出明确的不支持提示")
  void shouldRejectLegacyBinaryXls_withClearError() throws Exception {
    // arrange: 用 HSSF 生成真二进制 .xls(OLE2)字节
    byte[] xlsBytes = buildHssfBytes();
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    // act + assert
    assertThatThrownBy(() -> parser.parse(ctx, request(xlsBytes, null), writer(sink)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("excel_binary_unsupported");
  }

  @Test
  @DisplayName("超大载荷改用私有落地文件路径处理,超限时抛异常")
  void shouldUseFileBackedPath_whenPayloadOversized() {
    byte[] oversizedPayload = new byte[16 * 1024 * 1024 + 1];

    assertThatThrownBy(() ->
            parser.parse(context(), request(oversizedPayload, null), writer(new StringWriter())))
        .isInstanceOf(Exception.class);
  }

  // ── Item 4: 无 field_mappings → 按 Excel 实际表头直通 ────────────────────────

  @Test
  @DisplayName("无字段映射时直通实际表头作字段名,不套用固定结构")
  void shouldPassThroughActualHeaders_whenNoFieldMappings() throws Exception {
    byte[] xlsx = TestExcelFileBuilder.builder()
        .headers(List.of("订单号", "金额"))
        .row(List.of("O-1", "100"))
        .build();
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    long count = parser.parse(ctx, request(xlsx, null), writer(sink));

    assertThat(count).isEqualTo(1L);
    // 直通:NDJSON 用实际表头名作字段名,而非硬编码 customerNo/...
    assertThat(sink.toString()).contains("订单号").contains("金额").contains("O-1");
    assertThat(sink.toString()).doesNotContain("customerNo");
  }

  // ── Item 3: 表头存在性校验,缺列 fail-fast ──────────────────────────────────

  @Test
  @DisplayName("映射的来源列在表头缺失时立即失败,并提示表头缺列")
  void shouldFailFast_whenMappedSourceHeaderMissing() {
    byte[] xlsx = TestExcelFileBuilder.builder()
        .headers(List.of("订单号", "金额"))
        .row(List.of("O-1", "100"))
        .build();
    // field_mappings 配了一个不存在的来源列「客户编号」
    Map<String, Object> tpl = Map.of(
        "field_mappings",
        List.of(
            Map.of("source", "订单号", "target", "orderNo"),
            Map.of("source", "客户编号", "target", "customerNo")));
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    assertThatThrownBy(() -> parser.parse(ctx, request(xlsx, tpl), writer(sink)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("excel_header_missing");
  }

  @Test
  @DisplayName("映射列全部存在时,按映射投影出目标字段")
  void shouldProject_whenMappedHeadersAllPresent() throws Exception {
    byte[] xlsx = TestExcelFileBuilder.builder()
        .headers(List.of("订单号", "金额"))
        .row(List.of("O-1", "100"))
        .build();
    Map<String, Object> tpl =
        Map.of("field_mappings", List.of(Map.of("source", "订单号", "target", "orderNo")));
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    long count = parser.parse(ctx, request(xlsx, tpl), writer(sink));

    assertThat(count).isEqualTo(1L);
    assertThat(sink.toString()).contains("orderNo").contains("O-1");
  }

  // ── Item 3: preview_rows 抽样早停 ──────────────────────────────────────────

  @Test
  @DisplayName("配置预览行数时,只解析前若干行")
  void shouldStopAfterPreviewRows() throws Exception {
    TestExcelFileBuilder b = TestExcelFileBuilder.builder().headers(List.of("订单号"));
    for (int i = 0; i < 50; i++) {
      b.row(List.of("O-" + i));
    }
    byte[] xlsx = b.build();
    Map<String, Object> tpl = Map.of("preview_rows", 5);
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    long count = parser.parse(ctx, request(xlsx, tpl), writer(sink));

    assertThat(count).isEqualTo(5L);
  }

  // ── Item 5: 按名选 sheet ───────────────────────────────────────────────────

  @Test
  @DisplayName("按配置的工作表名解析对应工作表")
  void shouldSelectSheetByName() throws Exception {
    byte[] xlsx = TestExcelFileBuilder.builder()
        .sheetName("数据页")
        .headers(List.of("订单号"))
        .row(List.of("O-1"))
        .build();
    Map<String, Object> tpl = Map.of("excel_sheet_name", "数据页");
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    long count = parser.parse(ctx, request(xlsx, tpl), writer(sink));

    assertThat(count).isEqualTo(1L);
  }

  @Test
  @DisplayName("指定工作表不存在时立即失败,并提示工作表未找到")
  void shouldFailFast_whenNamedSheetNotFound() {
    byte[] xlsx = TestExcelFileBuilder.builder()
        .sheetName("数据页")
        .headers(List.of("订单号"))
        .row(List.of("O-1"))
        .build();
    Map<String, Object> tpl = Map.of("excel_sheet_name", "不存在的页");
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    assertThatThrownBy(() -> parser.parse(ctx, request(xlsx, tpl), writer(sink)))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("excel_sheet_not_found");
  }

  // ── Item 2: 坏行携带物理行号 + 列名 ─────────────────────────────────────────

  @Test
  @DisplayName("坏行按物理行号与列名定位,并回传位置信息")
  void shouldCarryPhysicalRowNumber_onBadRow() throws Exception {
    // 表头(物理行1)+ good(物理行2)+ boom(物理行3);boom 记录序列化时抛异常 → 走 endRow 坏行 catch。
    byte[] xlsx = TestExcelFileBuilder.builder()
        .headers(List.of("订单号"))
        .row(List.of("good"))
        .row(List.of("boom"))
        .build();
    // 坏行可跳过,使 recordParseError 走 recordSkippedRecord(不抛)
    when(governanceService.isSkippable(any())).thenReturn(true);
    when(governanceService.shouldFailOnSkip(any())).thenReturn(false);
    // 用一个序列化到含 "boom" 的行时抛异常的 ObjectMapper,精确把 boom 行打成坏行
    ExcelFormatParser failingParser =
        new ExcelFormatParser(new ParseSupport(new BoomOnValueMapper("boom"), governanceService));
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    failingParser.parse(ctx, request(xlsx, null), writer(sink));

    ArgumentCaptor<BadRecordCommand> captor = ArgumentCaptor.forClass(BadRecordCommand.class);
    verify(governanceService).recordSkippedRecord(any(), captor.capture());
    SourceLocator locator = captor.getValue().locator();
    // 物理行号 1-based:表头(行1)+ good(行2)+ boom(行3) → 坏行物理行号 = 3
    assertThat(locator.rowNum()).isEqualTo(3L);
    assertThat(locator.column()).isEqualTo("订单号");
  }

  @Test
  @DisplayName("全部行解析正常时,不触发坏记录治理回调")
  void shouldNotInvokeGovernance_onCleanParse() throws Exception {
    byte[] xlsx =
        TestExcelFileBuilder.builder().headers(List.of("a")).row(List.of("x")).build();
    ImportJobContext ctx = context();
    StringWriter sink = new StringWriter();

    parser.parse(ctx, request(xlsx, null), writer(sink));

    verify(governanceService, never()).recordSkippedRecord(any(), any());
    verify(governanceService, never())
        .recordSkippedRecord(any(), any(), anyLong(), any(), any(), any());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private ImportJobContext context() {
    ImportJobContext ctx = new ImportJobContext();
    ctx.setTenantId("t1");
    ctx.setWorkerId("w1");
    ctx.setFileId("1");
    return ctx;
  }

  private FormatParseRequest request(byte[] bytes, Object templateConfig) {
    return new FormatParseRequest(null, bytes, null, templateConfig, true);
  }

  private BufferedWriter writer(Writer w) {
    return new BufferedWriter(w);
  }

  private byte[] buildHssfBytes() throws Exception {
    try (HSSFWorkbook wb = new HSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      wb.createSheet("s").createRow(0).createCell(0).setCellValue("x");
      wb.write(out);
      return out.toByteArray();
    }
  }

  /** 序列化值的字符串表示含指定 marker 时抛 IOException,用来把含 marker 的那条记录精确打成坏行。 */
  private static final class BoomOnValueMapper extends ObjectMapper {
    private final transient String marker;

    BoomOnValueMapper(String marker) {
      this.marker = marker;
    }

    @Override
    public void writeValue(JsonGenerator g, Object value) throws IOException {
      if (value != null && String.valueOf(value).contains(marker)) {
        throw new IOException("boom serializing " + marker);
      }
      super.writeValue(g, value);
    }
  }
}
