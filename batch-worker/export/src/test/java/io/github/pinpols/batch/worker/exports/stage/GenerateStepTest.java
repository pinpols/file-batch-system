package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.utils.EncodingUtils;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineStageProgressRegistry;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.exports.config.ExportWorkerConfiguration;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportStage;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import io.github.pinpols.batch.worker.exports.plugin.ExportDataPluginRegistry;
import io.github.pinpols.batch.worker.exports.stage.format.DelimitedExportFormat;
import io.github.pinpols.batch.worker.exports.stage.format.ExcelExportFormat;
import io.github.pinpols.batch.worker.exports.stage.format.ExportFormatStrategyRegistry;
import io.github.pinpols.batch.worker.exports.stage.format.FixedWidthExportFormat;
import io.github.pinpols.batch.worker.exports.stage.format.GenerateCursorCodec;
import io.github.pinpols.batch.worker.exports.stage.format.JsonExportFormat;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

/**
 * Unit tests for {@link GenerateStep} — validates export format matrix completeness: DELIMITED (CSV
 * quoting / escaping), FIXED_WIDTH (padding / truncation / alignment), EXCEL (sheet name, header,
 * 游标分页）以及 JSON（游标分页）。
 */
@DisplayName("导出生成阶段单测:分隔符,定长,表格与 JSON 四种格式的落盘与边界语义")
class GenerateStepTest {

  private GenerateStep generateStep;
  private ExportDataPlugin dataPlugin;
  private ExportDataPluginRegistry pluginRegistry;

  private static final String PLUGIN_ID = "test.export.plugin";
  private static final Map<String, Object> BATCH = Map.of("id", 1L, "batchCode", "B001");

  @BeforeEach
  void setUp() throws Exception {
    dataPlugin = mock(ExportDataPlugin.class);
    when(dataPlugin.id()).thenReturn(PLUGIN_ID);
    when(dataPlugin.loadBatch(any())).thenReturn(BATCH);

    pluginRegistry = mock(ExportDataPluginRegistry.class);
    when(pluginRegistry.require(any())).thenReturn(dataPlugin);

    ExportWorkerConfiguration config = new ExportWorkerConfiguration(
        "worker-test",
        "EXPORT",
        "tenant-test",
        5000L,
        "batch-export",
        "group-export",
        null,
        500_000L,
        new ExportWorkerConfiguration.FileProcessing(true, 100, 100, 50));

    ObjectMapper objectMapper = new ObjectMapper();
    ExportFormatStrategyRegistry formatStrategyRegistry = new ExportFormatStrategyRegistry(List.of(
        new JsonExportFormat(objectMapper),
        new DelimitedExportFormat(objectMapper),
        new ExcelExportFormat(objectMapper),
        new FixedWidthExportFormat(objectMapper)));
    // 本类测非续跑 GENERATE 行为。checkpoint 默认值已在 P0 翻 true,故显式关闭 → positionStore 不会被调用,
    // 行为同未引入本特性;续跑路径由 GenerateStepCheckpointTest 覆盖。
    WorkerCheckpointProperties checkpointDisabled = new WorkerCheckpointProperties();
    checkpointDisabled.setEnabled(false);
    generateStep = new GenerateStep(
        pluginRegistry,
        formatStrategyRegistry,
        config,
        objectMapper,
        new GenerateRuntimeSupport(
            checkpointDisabled,
            mock(ProcessingPositionStore.class),
            new GenerateCursorCodec(),
            new PipelineStageProgressRegistry()));
  }

  @Test
  @DisplayName("回放能力用执行时同一 export_data_ref 选择插件并传入冻结模板")
  void replayCapability_usesSelectedPluginAndFrozenTemplateConfig() {
    Map<String, Object> frozenTemplateConfig = Map.of(
        "query_param_schema",
        Map.of("export_data_ref", PLUGIN_ID),
        "file_format_type",
        "DELIMITED");
    ReplayCapabilityDeclaration expected = ReplayCapabilityDeclaration.unknown(PLUGIN_ID);
    when(pluginRegistry.replayCapability(PLUGIN_ID, frozenTemplateConfig)).thenReturn(expected);

    ReplayCapabilityDeclaration actual = generateStep.replayCapability(frozenTemplateConfig);

    assertThat(actual).isEqualTo(expected);
    verify(pluginRegistry).replayCapability(PLUGIN_ID, frozenTemplateConfig);
  }

  @Test
  @DisplayName("正式 SQL 模板导出仍调用数据插件并生成文件")
  void shouldGenerateSqlTemplate_whenNotDryRun() throws Exception {
    stubSinglePage(List.of(Map.of("id", 1)));
    ExportJobContext context =
        buildContext("JSON", Map.of("export_data_ref", WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    verify(pluginRegistry).require(WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE);
    verify(dataPlugin).loadDetailPage(any(ExportDataContext.class), anyLong(), anyInt(), eq(null));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sql_template_export", "SQL_TEMPLATE_EXPORT"})
  @DisplayName("演练阻止 SQL 模板插件执行，插件标识不区分大小写")
  void shouldRejectSqlTemplateDryRun_regardlessOfPluginIdCase(String pluginId) {
    ExportJobContext context = buildContext("JSON", Map.of("export_data_ref", pluginId));
    context.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, true);

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.code()).isEqualTo("EXPORT_DRY_RUN_SQL_UNAVAILABLE");
    verify(pluginRegistry, Mockito.never()).require(any());
  }

  // ── DELIMITED / CSV ────────────────────────────────────────────────────────

  @Test
  @DisplayName("分隔符格式:写出表头与全部数据行")
  void delimited_shouldWriteHeaderAndDataRows() throws Exception {
    stubSinglePage(List.of(
        Map.of("name", "Alice", "amount", "100.00"), Map.of("name", "Bob", "amount", "200.50")));

    ExportJobContext context = buildContext("DELIMITED", Map.of("export_data_ref", PLUGIN_ID));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    assertThat(content).contains("name").contains("amount"); // header
    assertThat(content).contains("Alice").contains("100.00");
    assertThat(content).contains("Bob").contains("200.50");
    Path generated = Path.of(
        context.getAttributes().get(PipelineRuntimeKeys.GENERATED_FILE_PATH).toString());
    assertThat(Files.getPosixFilePermissions(generated))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    assertThat(Files.getPosixFilePermissions(generated.getParent()))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
  }

  @Test
  @DisplayName("值里含分隔符时按引用策略加双引号包裹")
  void delimited_shouldQuoteValueContainingDelimiter() throws Exception {
    stubSinglePage(List.of(Map.of("name", "Smith, Jr.", "amount", "50.00")));

    ExportJobContext context = buildContext(
        "DELIMITED",
        Map.of(
            "export_data_ref", PLUGIN_ID,
            "delimiter", ",",
            "quote_policy", "REQUIRED"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    // 包含逗号的值必须加引号。
    assertThat(content).contains("\"Smith, Jr.\"");
  }

  @Test
  @DisplayName("值内双引号按加倍转义,不破坏列边界")
  void delimited_shouldEscapeQuoteWithDoubleQuote() throws Exception {
    stubSinglePage(List.of(Map.of("description", "He said \"hello\"", "amount", "10")));

    ExportJobContext context = buildContext(
        "DELIMITED",
        Map.of(
            "export_data_ref", PLUGIN_ID,
            "quote_policy", "REQUIRED",
            "escape_policy", "DOUBLE_QUOTE"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    // 值内部的引号必须重复转义："He said ""hello"""。
    assertThat(content).contains("\"He said \"\"hello\"\"\"");
  }

  @Test
  @DisplayName("值里含换行时整体加引号包裹")
  void delimited_shouldQuoteValueContainingNewline() throws Exception {
    stubSinglePage(List.of(Map.of("note", "line1\nline2", "id", "1")));

    ExportJobContext context =
        buildContext("DELIMITED", Map.of("export_data_ref", PLUGIN_ID, "quote_policy", "REQUIRED"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    assertThat(content).contains("\"line1\nline2\"");
  }

  @Test
  @DisplayName("开启字节序标记时文件以标记开头,编码信息同时写入上下文")
  void delimited_utf8WithBom_shouldWriteBomPrefix() throws Exception {
    stubSinglePage(List.of(Map.of("name", "客户", "amount", "100.00")));

    ExportJobContext context =
        buildContext("DELIMITED", Map.of("export_data_ref", PLUGIN_ID, "with_bom", true));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    byte[] bytes = readGeneratedFileBytes(context);
    assertThat(bytes).startsWith(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
    assertThat(new String(bytes, StandardCharsets.UTF_8)).contains("客户");
    assertThat(context.getAttributes())
        .containsEntry("exportCharset", "UTF-8")
        .containsEntry("exportWithBom", true);
  }

  @Test
  @DisplayName("指定 GBK 与回车换行时:内容按目标编码可解码,行尾统一为回车换行")
  void delimited_gbkCrlf_shouldWriteConfiguredCharsetAndLineSeparator() throws Exception {
    stubSinglePage(List.of(Map.of("name", "客户", "amount", "100.00")));

    ExportJobContext context = buildContext(
        "DELIMITED",
        Map.of(
            "export_data_ref", PLUGIN_ID,
            "target_charset", "GBK",
            "line_separator", "CRLF"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    byte[] bytes = readGeneratedFileBytes(context);
    // 文件头必须是真的 GBK BOM 无关（GBK 无 BOM 约定），内容按 GBK 解码正确
    String decoded = new String(bytes, EncodingUtils.GBK);
    assertThat(decoded).contains("客户");
    // CRLF：去掉 \r\n 后不应再残留裸 \n
    assertThat(decoded).contains("\r\n");
    assertThat(decoded.replace("\r\n", "")).doesNotContain("\n");
    assertThat(context.getAttributes())
        .containsEntry("exportCharset", "GBK")
        .containsEntry("exportLineSeparator", "\r\n");
  }

  @Test
  @DisplayName("目标编码无法表示字符时失败,并回报行号,列名与编码")
  void delimited_gbkUnmappableCharacter_shouldReportRowAndColumn() throws Exception {
    stubSinglePage(List.of(Map.of("name", "客户😀", "amount", "100.00")));

    ExportJobContext context =
        buildContext("DELIMITED", Map.of("export_data_ref", PLUGIN_ID, "target_charset", "GBK"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_GENERATE_FAILED");
    assertThat(result.message()).contains("EXPORT_GENERATE_UNMAPPABLE_CHARACTER");
    assertThat(result.message()).contains("data-row-1");
    assertThat(result.message()).contains("column=detail.name");
    assertThat(result.message()).contains("charset=GBK");
  }

  @Test
  @DisplayName("目标编码不受支持时返回配置非法,不产出文件")
  void unsupportedTargetCharset_shouldReturnConfigInvalid() throws Exception {
    stubSinglePage(List.of(Map.of("name", "x")));

    ExportJobContext context =
        buildContext("DELIMITED", Map.of("export_data_ref", PLUGIN_ID, "target_charset", "UTF-16"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_GENERATE_CONFIG_INVALID");
  }

  @Test
  @DisplayName("行分隔符归一化:空值,转义写法与别名都映射到对应字符")
  void normalizeExportLineSeparator_shouldAcceptEscapesAndAliases() {
    assertThat(GenerateStep.normalizeExportLineSeparator(null)).isEqualTo("\n");
    assertThat(GenerateStep.normalizeExportLineSeparator("\\n")).isEqualTo("\n");
    assertThat(GenerateStep.normalizeExportLineSeparator("LF")).isEqualTo("\n");
    assertThat(GenerateStep.normalizeExportLineSeparator("\\r\\n")).isEqualTo("\r\n");
    assertThat(GenerateStep.normalizeExportLineSeparator("CRLF")).isEqualTo("\r\n");
    assertThat(GenerateStep.normalizeExportLineSeparator("\r\n")).isEqualTo("\r\n");
    assertThat(GenerateStep.normalizeExportLineSeparator("\r")).isEqualTo("\r");
  }

  @Test
  @DisplayName("全引用策略下每个单元格都加引号,表头也不例外")
  void delimited_quoteAll_shouldQuoteEveryValue() throws Exception {
    stubSinglePage(List.of(Map.of("code", "A", "name", "Alice")));

    ExportJobContext context =
        buildContext("DELIMITED", Map.of("export_data_ref", PLUGIN_ID, "quote_policy", "ALL"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    // 所有值和表头单元格都必须加引号。
    assertThat(content).contains("\"A\"").contains("\"Alice\"");
  }

  @Test
  @DisplayName("以制表符作分隔符时,相邻列用制表符拼接")
  void delimited_tabSeparated_shouldUseTabs() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("col1", "val1");
    row.put("col2", "val2");
    stubSinglePage(List.of(row));

    ExportJobContext context = buildContext(
        "DELIMITED",
        Map.of(
            "export_data_ref", PLUGIN_ID,
            "delimiter", "\t",
            "quote_policy", "NONE"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    assertThat(content).contains("val1\tval2");
  }

  // ── FIXED_WIDTH ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("定长格式:左对齐右侧补空格,右对齐左侧补零")
  void fixedWidth_shouldPadShortValue() throws Exception {
    stubSinglePage(List.of(Map.of("code", "A", "amount", "10")));

    ExportJobContext context = buildContext(
        "FIXED_WIDTH",
        Map.of(
            "export_data_ref",
            PLUGIN_ID,
            "fixed_width_columns",
            List.of(
                Map.of(
                    "header", "code", "source", "code", "width", 5, "align", "LEFT", "padChar",
                    " "),
                Map.of(
                    "header", "amount", "source", "amount", "width", 8, "align", "RIGHT", "padChar",
                    "0"))));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    // "A" 左侧补空格至 5 个字符："A    "。
    // "10" 右对齐并用字符 '0' 补足至 8 位："00000010"。
    assertThat(content).contains("A    ").contains("00000010");
  }

  @Test
  @DisplayName("定长格式:超宽值按列宽截断")
  void fixedWidth_shouldTruncateLongValue() throws Exception {
    stubSinglePage(List.of(Map.of("name", "AliceLongName", "id", "1")));

    ExportJobContext context = buildContext(
        "FIXED_WIDTH",
        Map.of(
            "export_data_ref",
            PLUGIN_ID,
            "fixed_width_columns",
            List.of(
                Map.of("header", "id", "source", "id", "width", 3),
                Map.of("header", "name", "source", "name", "width", 5))));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    // "AliceLongName" 截断为 5 个字符："Alice"。
    assertThat(content).contains("Alice").doesNotContain("AliceLongName");
  }

  @Test
  @DisplayName("定长格式:每条记录补齐或截断到声明长度")
  void fixedWidth_shouldEnforceRecordLength() throws Exception {
    stubSinglePage(List.of(Map.of("code", "A", "val", "B")));

    ExportJobContext context = buildContext(
        "FIXED_WIDTH",
        Map.of(
            "export_data_ref",
            PLUGIN_ID,
            "record_length",
            20,
            "fixed_width_columns",
            List.of(
                Map.of("header", "code", "source", "code", "width", 3),
                Map.of("header", "val", "source", "val", "width", 3))));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    // 每行数据必须恰好为 20 个字符（不足时补齐，超出时截断）。
    String content = readGeneratedFile(context);
    content
        .lines()
        .filter(l -> !l.isBlank())
        .skip(1) // skip header
        .forEach(line -> assertThat(line).hasSize(20));
  }

  // ── EXCEL ──────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("表格格式:生成非空工作簿文件并回填生成路径")
  void excel_shouldCreateWorkbookWithHeaderAndData() throws Exception {
    stubSinglePage(
        List.of(Map.of("id", "1", "label", "First"), Map.of("id", "2", "label", "Second")));

    ExportJobContext context =
        buildContext("EXCEL", Map.of("export_data_ref", PLUGIN_ID, "sheet_name", "Report"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    // 校验文件存在且内容非空。
    Object generatedFilePath = context.getAttributes().get(PipelineRuntimeKeys.GENERATED_FILE_PATH);
    assertThat(generatedFilePath).isNotNull();
    Path xlsxFile = Path.of(generatedFilePath.toString());
    assertThat(xlsxFile).exists();
    assertThat(Files.size(xlsxFile)).isGreaterThan(0);
  }

  @Test
  @DisplayName("工作表名含非法字符时自动替换,生成过程不失败")
  void excel_sheetNameShouldBeSanitized_whenContainsIllegalChars() throws Exception {
    stubSinglePage(List.of(Map.of("col", "val")));

    // 包含 Excel 非法字符（例如 '/'）的工作表名称。
    ExportJobContext context = buildContext(
        "EXCEL", Map.of("export_data_ref", PLUGIN_ID, "sheet_name", "Report/2026:Q1[1]"));

    // 不应抛出异常；工作表名称应清理为 "Report_2026_Q1_1_"。
    ExportStageResult result = generateStep.execute(context);
    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("工作表名超长时截断处理,生成过程不失败")
  void excel_shouldHandleLongSheetName() throws Exception {
    stubSinglePage(List.of(Map.of("col", "val")));

    // Excel 工作表名称最多为 31 个字符。
    ExportJobContext context = buildContext(
        "EXCEL",
        Map.of(
            "export_data_ref",
            PLUGIN_ID,
            "sheet_name",
            "VeryLongSheetNameThatExceedsThirtyOneCharactersLimit"));

    ExportStageResult result = generateStep.execute(context);
    assertThat(result.success()).isTrue();
  }

  // ── JSON ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("JSON 格式:写出快照,批次与明细,整体仍是合法结构")
  void json_shouldWriteSnapshotBatchAndDetails() throws Exception {
    stubSinglePage(
        List.of(Map.of("txId", "TX001", "amount", 99.5, "note", "test <special> & \"chars\"")));

    ExportJobContext context = buildContext("JSON", Map.of("export_data_ref", PLUGIN_ID));
    context
        .getAttributes()
        .put(PipelineRuntimeKeys.EXPORT_SNAPSHOT, Map.of("snapshotKey", "snap-001"));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    String content = readGeneratedFile(context);
    assertThat(content).contains("snapshot").contains("snap-001");
    assertThat(content).contains("TX001");
    // Jackson 应将文件序列化为有效 JSON；校验结果可以正常解析。
    new ObjectMapper().readTree(content); // throws if invalid JSON
  }

  @Test
  @DisplayName("JSON 格式分页:多页游标拉取后记录数累加正确,明细齐全")
  void json_shouldHandleCursorPagination_multiplePages() throws Exception {
    // 模拟两页数据。
    List<Map<String, Object>> page1 = List.of(Map.of("id", "1"), Map.of("id", "2"));
    List<Map<String, Object>> page2 = List.of(Map.of("id", "3"));
    when(dataPlugin.loadDetailPage(any(ExportDataContext.class), anyLong(), anyInt(), eq(null)))
        .thenReturn(new ExportDataPlugin.DetailPage(page1, "cursor-2"));
    when(dataPlugin.loadDetailPage(
            any(ExportDataContext.class), anyLong(), anyInt(), eq("cursor-2")))
        .thenReturn(new ExportDataPlugin.DetailPage(page2, null));

    ExportJobContext context = buildContext("JSON", Map.of("export_data_ref", PLUGIN_ID));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes()).containsEntry(PipelineRuntimeKeys.RECORD_COUNT, 3L);
    String content = readGeneratedFile(context);
    assertThat(content).contains("\"1\"").contains("\"2\"").contains("\"3\"");
  }

  @Test
  @DisplayName("批次查不到时失败,并返回批次不存在")
  void generate_shouldReturnFailure_whenBatchNotFound() throws Exception {
    when(dataPlugin.loadBatch(any())).thenReturn(Map.of());

    ExportJobContext context = buildContext("JSON", Map.of("export_data_ref", PLUGIN_ID));

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("EXPORT_BATCH_NOT_FOUND");
  }

  @Test
  @DisplayName("缺少导出载荷时失败,阶段仍标记为生成阶段")
  void generate_shouldReturnFailure_whenPayloadMissing() {
    ExportJobContext context = new ExportJobContext();
    context.setTenantId("t1");
    context.setJobCode("JOB");

    ExportStageResult result = generateStep.execute(context);

    assertThat(result.success()).isFalse();
    assertThat(result.stage()).isEqualTo(ExportStage.GENERATE);
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private void stubSinglePage(List<Map<String, Object>> rows) throws Exception {
    when(dataPlugin.loadDetailPage(any(ExportDataContext.class), anyLong(), anyInt(), eq(null)))
        .thenReturn(new ExportDataPlugin.DetailPage(rows, null));
  }

  private ExportJobContext buildContext(String fileFormatType, Map<String, Object> templateConfig) {
    ExportJobContext context = new ExportJobContext();
    context.setTenantId("tenant-gen-test");
    context.setJobCode("GEN_JOB");
    context.setWorkerId("worker-1");

    ExportPayload payload = new ExportPayload(
        null, null, "TMPL_001", "BATCH-001", null, null, null, null, null, null, Map.of());
    context.getAttributes().put("exportPayload", payload);
    context.getAttributes().put(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, fileFormatType);
    context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
    return context;
  }

  private String readGeneratedFile(ExportJobContext context) throws Exception {
    Object path = context.getAttributes().get(PipelineRuntimeKeys.GENERATED_FILE_PATH);
    assertThat(path).isNotNull();
    return Files.readString(Path.of(path.toString()));
  }

  private byte[] readGeneratedFileBytes(ExportJobContext context) throws Exception {
    Object path = context.getAttributes().get(PipelineRuntimeKeys.GENERATED_FILE_PATH);
    assertThat(path).isNotNull();
    return Files.readAllBytes(Path.of(path.toString()));
  }
}
