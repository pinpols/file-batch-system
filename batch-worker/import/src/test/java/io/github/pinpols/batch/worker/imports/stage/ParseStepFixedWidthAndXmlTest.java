package io.github.pinpols.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

/**
 * V5-P2-8 验证：ParseStep 在 FIXED_WIDTH / XML 两种文件格式上的解析正确性。 之前只覆盖 CSV / JSON，对应 parser 真实代码却存在
 * （FixedWidthFormatParser / XmlFormatParser），属验证缺口。
 */
@DisplayName("导入解析阶段定长与 XML 格式单测:字段布局,首尾行跳过与安全拒绝语义")
class ParseStepFixedWidthAndXmlTest {

  private ParseStep parseStep;

  @BeforeEach
  void setUp() {
    PlatformFileRecordRepository runtimeRepository = mock(PlatformFileRecordRepository.class);
    ImportRecordGovernanceService recordGovernanceService =
        mock(ImportRecordGovernanceService.class);
    when(recordGovernanceService.withinThreshold(any())).thenReturn(true);
    when(recordGovernanceService.isSkippable(any())).thenReturn(false);
    parseStep = new ParseStep(new ObjectMapper(), runtimeRepository, recordGovernanceService);
  }

  // ── 定长格式 FIXED_WIDTH ───────────────────────────────────────────────────

  /** 3 字段定长格式: customerNo(6) + customerName(20) + status(8)，trim 后落盘。 */
  @Test
  @DisplayName("定长格式按字段起始与长度切分,三条记录全部解析成明细")
  void shouldParseFixedWidth_threeFieldRecords() {
    String fixed =
        // 字段布局:customerNo | customerName | status
        "C00001Alice               ACTIVE  \n"
            + "C00002Bob                 INACTIVE\n"
            + "C00003Charlie             ACTIVE  \n";

    Map<String, Object> templateConfig = Map.of(
        "field_mappings",
        List.of(
            Map.of("source", "customerNo", "target", "customerNo", "start", 0, "length", 6),
            Map.of("source", "customerName", "target", "customerName", "start", 6, "length", 20),
            Map.of("source", "status", "target", "status", "start", 26, "length", 8)),
        "record_length",
        34,
        "jdbc_mapped_import",
        Map.of());

    ImportJobContext context = buildContext(fixed, "FIXED_WIDTH", templateConfig);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes()).containsEntry(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 3L);
    assertNdjsonRecordCount(context, 3);
    assertNdjsonContains(context, "C00001", "Alice", "ACTIVE");
    assertNdjsonContains(context, "C00003", "Charlie", "ACTIVE");
  }

  /** header_rows + footer_rows 用于跳过头尾。 */
  @Test
  @DisplayName("定长格式跳过声明数量的表头与表尾行,只解析数据行")
  void shouldParseFixedWidth_skippingHeaderAndFooter() {
    // 定长空格属于输入字节，保留逐行字符串，避免文本块处理尾随空白改变字段位置。
    String fixed = "HEADER LINE                       \n"
        + "C00001Alice               ACTIVE  \n"
        + "C00002Bob                 INACTIVE\n"
        + "FOOTER:total=2                    \n";

    Map<String, Object> templateConfig = Map.of(
        "field_mappings",
        List.of(
            Map.of("source", "customerNo", "target", "customerNo", "start", 0, "length", 6),
            Map.of("source", "customerName", "target", "customerName", "start", 6, "length", 20),
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
    assertThat(context.getAttributes()).containsEntry(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 2L);
    assertNdjsonRecordCount(context, 2);
  }

  /** PG jsonb 读取时 field_mappings 可能是 PGobject；运行时必须和 String/List 一样解析。 */
  @Test
  @DisplayName("字段映射以数据库 jsonb 形态给出时,同样按布局解析")
  void shouldParseFixedWidth_whenFieldMappingsIsPgJsonbObject() throws Exception {
    String fixed = "C00004Dana                ACTIVE  \n";
    PGobject fieldMappings = new PGobject();
    fieldMappings.setType("jsonb");
    fieldMappings.setValue("""
        [
          {"target": "customerNo", "start": 0, "length": 6},
          {"target": "customerName", "start": 6, "length": 20},
          {"target": "status", "start": 26, "length": 8}
        ]
        """.stripTrailing());

    Map<String, Object> templateConfig = Map.of(
        "field_mappings", fieldMappings, "record_length", 34, "jdbc_mapped_import", Map.of());

    ImportJobContext context = buildContext(fixed, "FIXED_WIDTH", templateConfig);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes()).containsEntry(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 1L);
    assertNdjsonRecordCount(context, 1);
    assertNdjsonContains(context, "C00004", "Dana", "ACTIVE");
  }

  // ── XML ────────────────────────────────────────────────────────────────────

  /** 标准 records 包裹结构: &lt;records&gt;&lt;record&gt;...&lt;/record&gt;...&lt;/records&gt; */
  @Test
  @DisplayName("XML 格式按记录元素取子节点,两条记录字段完整")
  void shouldParseXml_recordElementChildren() {
    String xml = """
        <?xml version='1.0' encoding='UTF-8'?>
        <records>
          <record>
            <customerNo>C001</customerNo>
            <customerName>Alice</customerName>
            <status>ACTIVE</status>
          </record>
          <record>
            <customerNo>C002</customerNo>
            <customerName>Bob</customerName>
            <status>INACTIVE</status>
          </record>
        </records>
        """.stripTrailing();

    Map<String, Object> templateConfig =
        Map.of("xmlRecordElement", "record", "jdbc_mapped_import", Map.of());

    ImportJobContext context = buildContext(xml, "XML", templateConfig);

    ImportStageResult result = parseStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes()).containsEntry(PipelineRuntimeKeys.IMPORT_TOTAL_COUNT, 2L);
    assertNdjsonRecordCount(context, 2);
    assertNdjsonContains(context, "C001", "Alice", "ACTIVE");
    assertNdjsonContains(context, "C002", "Bob", "INACTIVE");
  }

  /** XXE 防护：DOCTYPE / entity 被 XmlFormatParser 拒绝（disallow-doctype-decl=true）。 */
  @Test
  @DisplayName("XML 含外部实体声明时拒绝解析,返回解析失败而非崩溃")
  void shouldRejectXml_withDoctype_xxeProtection() {
    String xmlWithDoctype = "<?xml version='1.0'?>"
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
    ImportPayload importPayload = new ImportPayload(
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
    attrs.put(PipelineRuntimeKeys.IMPORT_PAYLOAD, importPayload);
    attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
    context.setAttributes(attrs);
    return context;
  }

  private void assertNdjsonRecordCount(ImportJobContext context, int expected) {
    Object path = context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH);
    assertThat(path).as("PARSED_RECORDS_PATH must be set on success").isNotNull();
    try {
      long lineCount =
          Files.lines(Path.of(path.toString())).filter(l -> !l.isBlank()).count();
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
