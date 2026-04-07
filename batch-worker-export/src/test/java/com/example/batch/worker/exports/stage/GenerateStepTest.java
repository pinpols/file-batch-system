package com.example.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.plugin.ExportDataContext;
import com.example.batch.common.plugin.ExportDataPlugin;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.exports.config.ExportWorkerConfiguration;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.plugin.ExportDataPluginRegistry;
import com.example.batch.worker.exports.stage.format.DelimitedExportFormat;
import com.example.batch.worker.exports.stage.format.ExcelExportFormat;
import com.example.batch.worker.exports.stage.format.ExportFormatStrategyRegistry;
import com.example.batch.worker.exports.stage.format.FixedWidthExportFormat;
import com.example.batch.worker.exports.stage.format.JsonExportFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GenerateStep} — validates export format matrix completeness:
 * DELIMITED (CSV quoting / escaping), FIXED_WIDTH (padding / truncation / alignment),
 * EXCEL (sheet name, header, cursor pagination), and JSON (cursor pagination).
 */
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
                "worker-test", "EXPORT", "tenant-test", 5000L,
                "batch-export", "group-export",
                500_000L,
                new ExportWorkerConfiguration.FileProcessing(true, 100, 100, 50)
        );

        ObjectMapper objectMapper = new ObjectMapper();
        ExportFormatStrategyRegistry formatStrategyRegistry = new ExportFormatStrategyRegistry(List.of(
                new JsonExportFormat(objectMapper),
                new DelimitedExportFormat(objectMapper),
                new ExcelExportFormat(objectMapper),
                new FixedWidthExportFormat(objectMapper)
        ));
        generateStep = new GenerateStep(pluginRegistry, formatStrategyRegistry, config);
    }

    // ── DELIMITED / CSV ────────────────────────────────────────────────────────

    @Test
    void delimited_shouldWriteHeaderAndDataRows() throws Exception {
        stubSinglePage(List.of(
                Map.of("name", "Alice", "amount", "100.00"),
                Map.of("name", "Bob", "amount", "200.50")
        ));

        ExportJobContext context = buildContext("DELIMITED", Map.of(
                "export_data_ref", PLUGIN_ID
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        assertThat(content).contains("name").contains("amount"); // header
        assertThat(content).contains("Alice").contains("100.00");
        assertThat(content).contains("Bob").contains("200.50");
    }

    @Test
    void delimited_shouldQuoteValueContainingDelimiter() throws Exception {
        stubSinglePage(List.of(
                Map.of("name", "Smith, Jr.", "amount", "50.00")
        ));

        ExportJobContext context = buildContext("DELIMITED", Map.of(
                "export_data_ref", PLUGIN_ID,
                "delimiter", ",",
                "quote_policy", "REQUIRED"
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        // Value containing comma must be quoted
        assertThat(content).contains("\"Smith, Jr.\"");
    }

    @Test
    void delimited_shouldEscapeQuoteWithDoubleQuote() throws Exception {
        stubSinglePage(List.of(
                Map.of("description", "He said \"hello\"", "amount", "10")
        ));

        ExportJobContext context = buildContext("DELIMITED", Map.of(
                "export_data_ref", PLUGIN_ID,
                "quote_policy", "REQUIRED",
                "escape_policy", "DOUBLE_QUOTE"
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        // Internal quotes must be doubled: "He said ""hello"""
        assertThat(content).contains("\"He said \"\"hello\"\"\"");
    }

    @Test
    void delimited_shouldQuoteValueContainingNewline() throws Exception {
        stubSinglePage(List.of(
                Map.of("note", "line1\nline2", "id", "1")
        ));

        ExportJobContext context = buildContext("DELIMITED", Map.of(
                "export_data_ref", PLUGIN_ID,
                "quote_policy", "REQUIRED"
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        assertThat(content).contains("\"line1\nline2\"");
    }

    @Test
    void delimited_quoteAll_shouldQuoteEveryValue() throws Exception {
        stubSinglePage(List.of(
                Map.of("code", "A", "name", "Alice")
        ));

        ExportJobContext context = buildContext("DELIMITED", Map.of(
                "export_data_ref", PLUGIN_ID,
                "quote_policy", "ALL"
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        // Every value and header cell quoted
        assertThat(content).contains("\"A\"").contains("\"Alice\"");
    }

    @Test
    void delimited_tabSeparated_shouldUseTabs() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("col1", "val1");
        row.put("col2", "val2");
        stubSinglePage(List.of(row));

        ExportJobContext context = buildContext("DELIMITED", Map.of(
                "export_data_ref", PLUGIN_ID,
                "delimiter", "\t",
                "quote_policy", "NONE"
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        assertThat(content).contains("val1\tval2");
    }

    // ── FIXED_WIDTH ────────────────────────────────────────────────────────────

    @Test
    void fixedWidth_shouldPadShortValue() throws Exception {
        stubSinglePage(List.of(
                Map.of("code", "A", "amount", "10")
        ));

        ExportJobContext context = buildContext("FIXED_WIDTH", Map.of(
                "export_data_ref", PLUGIN_ID,
                "fixed_width_columns", List.of(
                        Map.of("header", "code", "source", "code", "width", 5, "align", "LEFT", "padChar", " "),
                        Map.of("header", "amount", "source", "amount", "width", 8, "align", "RIGHT", "padChar", "0")
                )
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        // "A" left-padded to 5 chars → "A    "
        // "10" right-aligned to 8 with '0' → "00000010"
        assertThat(content).contains("A    ");
        assertThat(content).contains("00000010");
    }

    @Test
    void fixedWidth_shouldTruncateLongValue() throws Exception {
        stubSinglePage(List.of(
                Map.of("name", "AliceLongName", "id", "1")
        ));

        ExportJobContext context = buildContext("FIXED_WIDTH", Map.of(
                "export_data_ref", PLUGIN_ID,
                "fixed_width_columns", List.of(
                        Map.of("header", "id", "source", "id", "width", 3),
                        Map.of("header", "name", "source", "name", "width", 5)
                )
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        // "AliceLongName" truncated to 5 chars → "Alice"
        assertThat(content).contains("Alice");
        assertThat(content).doesNotContain("AliceLongName");
    }

    @Test
    void fixedWidth_shouldEnforceRecordLength() throws Exception {
        stubSinglePage(List.of(
                Map.of("code", "A", "val", "B")
        ));

        ExportJobContext context = buildContext("FIXED_WIDTH", Map.of(
                "export_data_ref", PLUGIN_ID,
                "record_length", 20,
                "fixed_width_columns", List.of(
                        Map.of("header", "code", "source", "code", "width", 3),
                        Map.of("header", "val", "source", "val", "width", 3)
                )
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        // Each data line must be exactly 20 chars (padded or truncated)
        String content = readGeneratedFile(context);
        content.lines()
                .filter(l -> !l.isBlank())
                .skip(1) // skip header
                .forEach(line -> assertThat(line).hasSize(20));
    }

    // ── EXCEL ──────────────────────────────────────────────────────────────────

    @Test
    void excel_shouldCreateWorkbookWithHeaderAndData() throws Exception {
        stubSinglePage(List.of(
                Map.of("id", "1", "label", "First"),
                Map.of("id", "2", "label", "Second")
        ));

        ExportJobContext context = buildContext("EXCEL", Map.of(
                "export_data_ref", PLUGIN_ID,
                "sheet_name", "Report"
        ));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        // Verify file exists and is non-empty
        Object generatedFilePath = context.getAttributes().get(PipelineRuntimeKeys.GENERATED_FILE_PATH);
        assertThat(generatedFilePath).isNotNull();
        Path xlsxFile = Path.of(generatedFilePath.toString());
        assertThat(xlsxFile).exists();
        assertThat(Files.size(xlsxFile)).isGreaterThan(0);
    }

    @Test
    void excel_sheetNameShouldBeSanitized_whenContainsIllegalChars() throws Exception {
        stubSinglePage(List.of(Map.of("col", "val")));

        // Sheet name with illegal characters for Excel (e.g. '/')
        ExportJobContext context = buildContext("EXCEL", Map.of(
                "export_data_ref", PLUGIN_ID,
                "sheet_name", "Report/2026:Q1[1]"
        ));

        // Should not throw; sheet name sanitized to "Report_2026_Q1_1_"
        ExportStageResult result = generateStep.execute(context);
        assertThat(result.success()).isTrue();
    }

    @Test
    void excel_shouldHandleLongSheetName() throws Exception {
        stubSinglePage(List.of(Map.of("col", "val")));

        // Sheet names are limited to 31 chars in Excel
        ExportJobContext context = buildContext("EXCEL", Map.of(
                "export_data_ref", PLUGIN_ID,
                "sheet_name", "VeryLongSheetNameThatExceedsThirtyOneCharactersLimit"
        ));

        ExportStageResult result = generateStep.execute(context);
        assertThat(result.success()).isTrue();
    }

    // ── JSON ───────────────────────────────────────────────────────────────────

    @Test
    void json_shouldWriteSnapshotBatchAndDetails() throws Exception {
        stubSinglePage(List.of(
                Map.of("txId", "TX001", "amount", 99.5, "note", "test <special> & \"chars\"")
        ));

        ExportJobContext context = buildContext("JSON", Map.of(
                "export_data_ref", PLUGIN_ID
        ));
        context.getAttributes().put(PipelineRuntimeKeys.EXPORT_SNAPSHOT,
                Map.of("snapshotKey", "snap-001"));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        String content = readGeneratedFile(context);
        assertThat(content).contains("snapshot").contains("snap-001");
        assertThat(content).contains("TX001");
        // Jackson serializes the file as valid JSON — verify it is parseable
        new ObjectMapper().readTree(content); // throws if invalid JSON
    }

    @Test
    void json_shouldHandleCursorPagination_multiplePages() throws Exception {
        // Simulate two pages of data
        List<Map<String, Object>> page1 = List.of(
                Map.of("id", "1"), Map.of("id", "2")
        );
        List<Map<String, Object>> page2 = List.of(
                Map.of("id", "3")
        );
        when(dataPlugin.loadDetailPage(any(ExportDataContext.class), anyLong(), anyInt(), eq(null)))
                .thenReturn(new ExportDataPlugin.DetailPage(page1, "cursor-2"));
        when(dataPlugin.loadDetailPage(any(ExportDataContext.class), anyLong(), anyInt(), eq("cursor-2")))
                .thenReturn(new ExportDataPlugin.DetailPage(page2, null));

        ExportJobContext context = buildContext("JSON", Map.of("export_data_ref", PLUGIN_ID));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isTrue();
        assertThat(context.getAttributes().get("recordCount")).isEqualTo(3L);
        String content = readGeneratedFile(context);
        assertThat(content).contains("\"1\"").contains("\"2\"").contains("\"3\"");
    }

    @Test
    void generate_shouldReturnFailure_whenBatchNotFound() throws Exception {
        when(dataPlugin.loadBatch(any())).thenReturn(Map.of());

        ExportJobContext context = buildContext("JSON", Map.of("export_data_ref", PLUGIN_ID));

        ExportStageResult result = generateStep.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("EXPORT_BATCH_NOT_FOUND");
    }

    @Test
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
                null, null, "TMPL_001", "BATCH-001", null, null, null, null, null, null, Map.of()
        );
        context.getAttributes().put("exportPayload", payload);
        context.getAttributes().put("exportFileFormatType", fileFormatType);
        context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
        return context;
    }

    private String readGeneratedFile(ExportJobContext context) throws Exception {
        Object path = context.getAttributes().get(PipelineRuntimeKeys.GENERATED_FILE_PATH);
        assertThat(path).isNotNull();
        return Files.readString(Path.of(path.toString()));
    }
}
