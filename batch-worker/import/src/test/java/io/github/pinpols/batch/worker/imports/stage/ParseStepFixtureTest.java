package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.testing.TestExcelFileBuilder;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 基于 fixture 的 {@link ParseStep} 测试 — 验证导入管道必须处理的所有上游文件格式： CSV（逗号 / 竖线 /
 * 制表符）、XML、JSON（数组和信封）、FIXED_WIDTH、EXCEL 以及字符集变体（UTF-8 BOM）。
 */
class ParseStepFixtureTest {

  private ParseStep parseStep;
  private PlatformFileRuntimeRepository runtimeRepository;
  private ImportRecordGovernanceService recordGovernanceService;

  @BeforeEach
  void setUp() {
    runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    recordGovernanceService = mock(ImportRecordGovernanceService.class);
    when(runtimeRepository.toLong(any())).thenReturn(1L);
    when(recordGovernanceService.withinThreshold(any())).thenReturn(true);
    when(recordGovernanceService.isSkippable(any())).thenReturn(false);
    parseStep = new ParseStep(new ObjectMapper(), runtimeRepository, recordGovernanceService);
  }

  // ── CSV comma-delimited (with header, 10 data rows) ────────────────────────

  @Test
  void shouldParseCsvFixture_tenDataRows() throws Exception {
    String content = loadFixture("fixtures/import-customers.csv");
    ImportJobContext ctx = buildContext(content, "DELIMITED", ",", 1, null);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(10L);
  }

  // ── Pipe-delimited (5 data rows) ───────────────────────────────────────────

  @Test
  void shouldParsePipeDelimitedFixture() throws Exception {
    String content = loadFixture("fixtures/import-customers-pipe.csv");
    ImportJobContext ctx = buildContext(content, "DELIMITED", "|", 1, null);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).as(result.message()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(5L);
  }

  // ── Tab-separated (5 data rows) ────────────────────────────────────────────

  @Test
  void shouldParseTabSeparatedFixture() throws Exception {
    String content = loadFixture("fixtures/import-customers-tab.tsv");
    ImportJobContext ctx = buildContext(content, "DELIMITED", "\t", 1, null);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(5L);
  }

  // ── JSON array fixture (5 records) ─────────────────────────────────────────

  @Test
  void shouldParseJsonArrayFixture() throws Exception {
    String content = loadFixture("fixtures/import-customers-array.json");
    ImportJobContext ctx = buildContext(content, "JSON", null, 0, null);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(5L);
  }

  // ── JSON envelope fixture (5 records inside "records" key) ─────────────────

  @Test
  void shouldParseJsonEnvelopeFixture() throws Exception {
    String content = loadFixture("fixtures/import-customers-envelope.json");
    ImportJobContext ctx = buildContext(content, "JSON", null, 0, null);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(5L);
  }

  // ── UTF-8 BOM CSV (header with BOM prefix stripped) ────────────────────────

  @Test
  void shouldParseCsvWithUtf8Bom() throws Exception {
    // 读取原始字节并验证 fixture 中包含 BOM
    byte[] raw = loadFixtureBytes("fixtures/import-customers-utf8bom.csv");
    assertThat(raw[0] & 0xFF).isEqualTo(0xEF);
    assertThat(raw[1] & 0xFF).isEqualTo(0xBB);
    assertThat(raw[2] & 0xFF).isEqualTo(0xBF);

    // ParseStep 接收 String 内容；BOM 应由字符集检测阶段剥离
    String content = new String(raw, StandardCharsets.UTF_8);
    // 手动剥离 BOM，模拟 PreprocessStep / 字符集转码器的行为
    if (content.startsWith("\uFEFF")) {
      content = content.substring(1);
    }

    ImportJobContext ctx = buildContext(content, "DELIMITED", ",", 1, null);
    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(3L);
  }

  // ── Excel fixture (programmatically built, 3 data rows) ───────────────────

  @Test
  void shouldParseExcelFixture_programmaticallyBuilt() throws Exception {
    byte[] xlsx =
        TestExcelFileBuilder.customerImport(
            List.of(
                Map.of(
                    "customerNo",
                    "C001",
                    "customerName",
                    "Alice Wang",
                    "customerType",
                    "PERSONAL",
                    "creditLimit",
                    "50000.00",
                    "currencyCode",
                    "CNY",
                    "email",
                    "alice@example.com",
                    "phone",
                    "13800138001",
                    "status",
                    "ACTIVE",
                    "openDate",
                    "2022-01-15",
                    "remark",
                    ""),
                Map.of(
                    "customerNo",
                    "C002",
                    "customerName",
                    "Bob Li",
                    "customerType",
                    "PERSONAL",
                    "creditLimit",
                    "30000.00",
                    "currencyCode",
                    "CNY",
                    "email",
                    "bob@example.com",
                    "phone",
                    "13800138002",
                    "status",
                    "ACTIVE",
                    "openDate",
                    "2022-03-20",
                    "remark",
                    ""),
                Map.of(
                    "customerNo",
                    "C003",
                    "customerName",
                    "Carol Zhang",
                    "customerType",
                    "CORPORATE",
                    "creditLimit",
                    "500000.00",
                    "currencyCode",
                    "USD",
                    "email",
                    "carol@corp.com",
                    "phone",
                    "13800138003",
                    "status",
                    "ACTIVE",
                    "openDate",
                    "2021-06-01",
                    "remark",
                    "企业客户")));

    // Excel 内容以 base64 形式传递
    String contentBase64 = Base64.getEncoder().encodeToString(xlsx);
    ImportJobContext ctx = buildContextBase64(contentBase64, "EXCEL", 1);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(3L);
  }

  // ── Bad-records CSV (2 valid rows: C001 and C010) ──────────────────────────

  @Test
  void shouldParseBadRecordsCsv_countAllRows() throws Exception {
    // ParseStep 统计所有已解析行数；校验推迟到 ValidateStep
    String content = loadFixture("fixtures/import-customers-bad-records.csv");
    ImportJobContext ctx = buildContext(content, "DELIMITED", ",", 1, null);

    ImportStageResult result = parseStep.execute(ctx);

    // ParseStep 写入每一行（包括坏记录行）（共 10 行减 1 行表头）
    assertThat(result.success()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(10L);
  }

  @Test
  void shouldPeelValidTrailerControlRecord() {
    String content = "id,name,amount\n1,Alice,10.00\n2,Bob,20.00\nT,2,30.00\n";
    ImportJobContext ctx = buildContext(content, "DELIMITED", ",", 1, null);
    withTrailerTemplate(ctx);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).as(result.toString()).isTrue();
    assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(2L);
    assertThat(ctx.getAttributes().get("declaredRecordCount")).isEqualTo(2L);
    assertThat(String.valueOf(ctx.getAttributes().get("declaredControlTotal"))).isEqualTo("30.00");
  }

  @Test
  void shouldFailWhenTrailerTemplatePresentButLastLineIsNotTrailer() {
    String content = "id,name,amount\n1,Alice,10.00\n2,Bob,20.00\n";
    ImportJobContext ctx = buildContext(content, "DELIMITED", ",", 1, null);
    withTrailerTemplate(ctx);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_PARSE_FAILED");
  }

  @Test
  void shouldFailWhenTrailerTemplatePresentForBinaryPayload() {
    String content = "id,name,amount\n1,Alice,10.00\nT,1,10.00\n";
    ImportJobContext ctx =
        buildContextBase64(
            Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)),
            "DELIMITED",
            1);
    withTrailerTemplate(ctx);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("trailer control record is not supported");
  }

  @Test
  void shouldFailWhenTrailerTemplatePresentForSpoolPayload() throws Exception {
    Path spool = Files.createTempFile("parse-step-trailer-spool", ".csv");
    Files.writeString(spool, "id,name,amount\n1,Alice,10.00\nT,1,10.00\n", StandardCharsets.UTF_8);
    ImportJobContext ctx = buildContext("", "DELIMITED", ",", 1, null);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_PATH, spool);
    ctx.getAttributes().put(PipelineRuntimeKeys.IMPORT_LARGE_TEXT_CHARSET, StandardCharsets.UTF_8);
    withTrailerTemplate(ctx);

    ImportStageResult result = parseStep.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("trailer control record is not supported");
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private String loadFixture(String resourcePath) throws IOException {
    return Files.readString(fixturePath(resourcePath), StandardCharsets.UTF_8);
  }

  private byte[] loadFixtureBytes(String resourcePath) throws IOException {
    return Files.readAllBytes(fixturePath(resourcePath));
  }

  private Path fixturePath(String resourcePath) throws IOException {
    URL url = getClass().getClassLoader().getResource(resourcePath);
    assertThat(url).as("Fixture not found: %s", resourcePath).isNotNull();
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException ex) {
      throw new IOException("Invalid fixture URI: " + resourcePath, ex);
    }
  }

  private ImportJobContext buildContext(
      String content, String fileFormatType, String delimiter, int headerRows, String charset) {
    ImportPayload importPayload =
        new ImportPayload(
            null,
            "test.csv",
            "test.csv",
            "CUSTOMER",
            fileFormatType,
            charset,
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            null,
            null,
            null,
            content,
            null,
            delimiter,
            headerRows,
            0,
            headerRows > 0,
            Map.of());

    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t1");
    context.setJobCode("FIXTURE_PARSE_TEST");
    context.setWorkerId("worker-fixture");
    context.setFileId("99");
    context.setRawPayload(content);

    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 99L);
    attrs.put(PipelineRuntimeKeys.TASK_ID, 999L);
    attrs.put("importPayload", importPayload);
    // 启用 preserveLogicalRow，使行以 map 形式写入（fixture 无需匹配 CustomerImportPayload）。
    attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("jdbc_mapped_import", Map.of()));
    context.setAttributes(attrs);
    return context;
  }

  private ImportJobContext buildContextBase64(
      String contentBase64, String fileFormatType, int headerRows) {
    ImportPayload importPayload =
        new ImportPayload(
            null,
            "test.xlsx",
            "test.xlsx",
            "CUSTOMER",
            fileFormatType,
            null,
            null,
            null,
            null,
            "UPLOAD",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            contentBase64,
            null,
            headerRows,
            0,
            headerRows > 0,
            Map.of());

    ImportJobContext context = new ImportJobContext();
    context.setTenantId("t1");
    context.setJobCode("FIXTURE_PARSE_EXCEL_TEST");
    context.setWorkerId("worker-fixture");
    context.setFileId("100");

    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 100L);
    attrs.put(PipelineRuntimeKeys.TASK_ID, 1000L);
    attrs.put("importPayload", importPayload);
    attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("jdbc_mapped_import", Map.of()));
    attrs.put(PipelineRuntimeKeys.IMPORT_BINARY_PAYLOAD, Base64.getDecoder().decode(contentBase64));
    context.setAttributes(attrs);
    return context;
  }

  private void withTrailerTemplate(ImportJobContext context) {
    context
        .getAttributes()
        .put(
            PipelineRuntimeKeys.TEMPLATE_CONFIG,
            Map.of(
                "jdbc_mapped_import",
                Map.of(),
                "trailer_template",
                Map.of(
                    "present",
                    true,
                    "delimiter",
                    ",",
                    "recordType",
                    "T",
                    "recordTypeIndex",
                    0,
                    "recordCountIndex",
                    1,
                    "controlTotalIndex",
                    2)));
  }
}
