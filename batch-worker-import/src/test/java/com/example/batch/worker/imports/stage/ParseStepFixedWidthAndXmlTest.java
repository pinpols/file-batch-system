package com.example.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

/**
 * V5-P2-8 验证：ParseStep 在 FIXED_WIDTH / XML 两种文件格式上的解析正确性。 之前只覆盖 CSV / JSON，对应 parser 真实代码却存在
 * （FixedWidthFormatParser / XmlFormatParser），属验证缺口。
 */
class ParseStepFixedWidthAndXmlTest {

  private ParseStep parseStep;

  @BeforeEach
  void setUp() {
    PlatformFileRuntimeRepository runtimeRepository = mock(PlatformFileRuntimeRepository.class);
    ImportRecordGovernanceService recordGovernanceService =
        mock(ImportRecordGovernanceService.class);
    when(runtimeRepository.toLong(any())).thenReturn(1L);
    when(recordGovernanceService.withinThreshold(any())).thenReturn(true);
    when(recordGovernanceService.isSkippable(any())).thenReturn(false);
    parseStep = new ParseStep(new ObjectMapper(), runtimeRepository, recordGovernanceService);
  }

  // ── 定长格式 FIXED_WIDTH ───────────────────────────────────────────────────

  /** 3 字段定长格式: customerNo(6) + customerName(20) + status(8)，trim 后落盘。 */
  @Test
  void shouldParseFixedWidth_threeFieldRecords() {
    String fixed =
        // 字段布局:customerNo | customerName | status
        "C00001Alice               ACTIVE  \n"
            + "C00002Bob                 INACTIVE\n"
            + "C00003Charlie             ACTIVE  \n";

    Map<String, Object> templateConfig =
        Map.of(
            "field_mappings",
            List.of(
                Map.of("source", "customerNo", "target", "customerNo", "start", 0, "length", 6),
                Map.of(
                    "source", "customerName", "target", "customerName", "start", 6, "length", 20),
                Map.of("source", "status", "target", "status", "start", 26, "length", 8)),
            "record_length",
            34,
            "jdbc_mapped_import",
            Map.of());

    ImportJobContext context = buildContext(fixed, "FIXED_WIDTH", templateConfig);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get("totalCount")).isEqualTo(3L);
    assertNdjsonRecordCount(context, 3);
    assertNdjsonContains(context, "C00001", "Alice", "ACTIVE");
    assertNdjsonContains(context, "C00003", "Charlie", "ACTIVE");
  }

  /** header_rows + footer_rows 用于跳过头尾。 */
  @Test
  void shouldParseFixedWidth_skippingHeaderAndFooter() {
    String fixed =
        "HEADER LINE                       \n" // header_rows=1 跳
            + "C00001Alice               ACTIVE  \n"
            + "C00002Bob                 INACTIVE\n"
            + "FOOTER:total=2                    \n"; // footer_rows=1 跳

    Map<String, Object> templateConfig =
        Map.of(
            "field_mappings",
            List.of(
                Map.of("source", "customerNo", "target", "customerNo", "start", 0, "length", 6),
                Map.of(
                    "source", "customerName", "target", "customerName", "start", 6, "length", 20),
                Map.of("source", "status", "target", "status", "start", 26, "length", 8)),
            "record_length",
            34,
            "header_rows",
            1,
            "footer_rows",
            1,
            "jdbc_mapped_import",
            Map.of());

    ImportJobContext context = buildContext(fixed, "FIXED_WIDTH", templateConfig);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get("totalCount")).isEqualTo(2L);
    assertNdjsonRecordCount(context, 2);
  }

  /** PG jsonb 读取时 field_mappings 可能是 PGobject；运行时必须和 String/List 一样解析。 */
  @Test
  void shouldParseFixedWidth_whenFieldMappingsIsPgJsonbObject() throws Exception {
    String fixed = "C00004Dana                ACTIVE  \n";
    PGobject fieldMappings = new PGobject();
    fieldMappings.setType("jsonb");
    fieldMappings.setValue(
        "["
            + "{\"target\":\"customerNo\",\"start\":0,\"length\":6},"
            + "{\"target\":\"customerName\",\"start\":6,\"length\":20},"
            + "{\"target\":\"status\",\"start\":26,\"length\":8}"
            + "]");

    Map<String, Object> templateConfig =
        Map.of(
            "field_mappings", fieldMappings, "record_length", 34, "jdbc_mapped_import", Map.of());

    ImportJobContext context = buildContext(fixed, "FIXED_WIDTH", templateConfig);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get("totalCount")).isEqualTo(1L);
    assertNdjsonRecordCount(context, 1);
    assertNdjsonContains(context, "C00004", "Dana", "ACTIVE");
  }

  // ── XML ────────────────────────────────────────────────────────────────────

  /** 标准 records 包裹结构: &lt;records&gt;&lt;record&gt;...&lt;/record&gt;...&lt;/records&gt; */
  @Test
  void shouldParseXml_recordElementChildren() {
    String xml =
        "<?xml version='1.0' encoding='UTF-8'?>"
            + "<records>"
            + "  <record>"
            + "    <customerNo>C001</customerNo>"
            + "    <customerName>Alice</customerName>"
            + "    <status>ACTIVE</status>"
            + "  </record>"
            + "  <record>"
            + "    <customerNo>C002</customerNo>"
            + "    <customerName>Bob</customerName>"
            + "    <status>INACTIVE</status>"
            + "  </record>"
            + "</records>";

    Map<String, Object> templateConfig =
        Map.of("xmlRecordElement", "record", "jdbc_mapped_import", Map.of());

    ImportJobContext context = buildContext(xml, "XML", templateConfig);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get("totalCount")).isEqualTo(2L);
    assertNdjsonRecordCount(context, 2);
    assertNdjsonContains(context, "C001", "Alice", "ACTIVE");
    assertNdjsonContains(context, "C002", "Bob", "INACTIVE");
  }

  /** XXE 防护：DOCTYPE / entity 被 XmlFormatParser 拒绝（disallow-doctype-decl=true）。 */
  @Test
  void shouldRejectXml_withDoctype_xxeProtection() {
    String xmlWithDoctype =
        "<?xml version='1.0'?>"
            + "<!DOCTYPE foo SYSTEM '/etc/passwd'>"
            + "<records><record><customerNo>C001</customerNo></record></records>";

    Map<String, Object> templateConfig =
        Map.of("xmlRecordElement", "record", "jdbc_mapped_import", Map.of());

    ImportJobContext context = buildContext(xmlWithDoctype, "XML", templateConfig);

    ImportStageResult result = parseStep.execute(context);

    // XmlFormatParser 抛出 + ParseStep 捕获 → 返回失败结果（不要崩进程，要友好失败）
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("IMPORT_PARSE_FAILED");
  }

  // ── 辅助方法 ───────────────────────────────────────────────────────────────

  private ImportJobContext buildContext(
      String rawPayload, String fileFormatType, Map<String, Object> templateConfig) {
    ImportPayload importPayload =
        new ImportPayload(
            null,
            null,
            null,
            null,
            fileFormatType,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            rawPayload,
            null,
            null,
            null,
            null,
            Boolean.TRUE,
            Map.of());

    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-fixed-xml-test");
    context.setJobCode("PARSE_FIXED_XML");
    context.setWorkerId("worker-1");
    context.setFileId("99");
    context.setRawPayload(rawPayload);
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.FILE_ID, 99L);
    attrs.put(PipelineRuntimeKeys.TASK_ID, 200L);
    attrs.put("importPayload", importPayload);
    attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
    context.setAttributes(attrs);
    return context;
  }

  private void assertNdjsonRecordCount(ImportJobContext context, int expected) {
    Object path = context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH);
    assertThat(path).as("PARSED_RECORDS_PATH must be set on success").isNotNull();
    try {
      long lineCount = Files.lines(Path.of(path.toString())).filter(l -> !l.isBlank()).count();
      assertThat(lineCount).isEqualTo(expected);
    } catch (Exception e) {
      throw new AssertionError("Could not count NDJSON lines: " + e.getMessage(), e);
    }
  }

  private void assertNdjsonContains(ImportJobContext context, String... expectedSubstrings) {
    Object path = context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH);
    try {
      String content = Files.readString(Path.of(path.toString()));
      for (String s : expectedSubstrings) {
        assertThat(content).contains(s);
      }
    } catch (Exception e) {
      throw new AssertionError("Could not read NDJSON: " + e.getMessage(), e);
    }
  }
}
