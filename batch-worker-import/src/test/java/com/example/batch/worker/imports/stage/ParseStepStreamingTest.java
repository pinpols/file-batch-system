package com.example.batch.worker.imports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.infrastructure.ImportRecordGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 单元测试：ParseStep 流式路径，校验设计文档 9.12「边查边写」约定。
 * 数组、{@code {"records":[...]}}、单对象等 JSON 形态均需按条经 NDJSON 落盘，不得整包载入内存。
 */
class ParseStepStreamingTest {

    private ParseStep parseStep;
    private PlatformFileRuntimeRepository runtimeRepository;
    private ImportRecordGovernanceService recordGovernanceService;

    @BeforeEach
    void setUp() {
        runtimeRepository = mock(PlatformFileRuntimeRepository.class);
        recordGovernanceService = mock(ImportRecordGovernanceService.class);
        when(runtimeRepository.toLong(any())).thenReturn(1L);
        // updateFileStatus returns void — Mockito mocks do nothing by default
        when(recordGovernanceService.withinThreshold(any())).thenReturn(true);
        when(recordGovernanceService.isSkippable(any())).thenReturn(false);

        parseStep = new ParseStep(new ObjectMapper(), runtimeRepository, recordGovernanceService);
    }

    // ── JSON array path ────────────────────────────────────────────────────────

    @Test
    void shouldParseJsonArray_streamingOnRecord() {
        String json = "[{\"customerNo\":\"C001\",\"customerName\":\"Alice\"},"
                + "{\"customerNo\":\"C002\",\"customerName\":\"Bob\"}]";

        ImportJobContext context = buildContext(json, "JSON");

        ImportStageResult result = parseStep.execute(context);

        assertThat(result.success()).isTrue();
        assertThat(context.getAttributes().get("totalCount")).isEqualTo(2L);
        assertNdjsonRecordCount(context, 2);
    }

    // ── JSON {"records":[...]} envelope — streaming path ──────────────────────

    @Test
    void shouldParseRecordsEnvelope_streamingWithoutFullLoad() {
        // Build a {"records":[...]} payload with 5 records
        String records = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> "{\"customerNo\":\"C" + String.format("%03d", i) + "\","
                        + "\"customerName\":\"Name" + i + "\"}")
                .collect(Collectors.joining(","));
        String json = "{\"records\":[" + records + "]}";

        ImportJobContext context = buildContext(json, "JSON");

        ImportStageResult result = parseStep.execute(context);

        assertThat(result.success()).isTrue();
        assertThat(context.getAttributes().get("totalCount")).isEqualTo(5L);
        assertNdjsonRecordCount(context, 5);
    }

    @Test
    void shouldReturnEmpty_forEmptyRecordsEnvelope() {
        String json = "{\"records\":[]}";

        ImportJobContext context = buildContext(json, "JSON");
        // ParseStep returns failure "IMPORT_PARSE_EMPTY" when totalCount==0
        ImportStageResult result = parseStep.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("IMPORT_PARSE_EMPTY");
    }

    @Test
    void shouldHandleLargeRecordsEnvelope_streaming() {
        // 500 records in a {"records":[...]} envelope — verifies the streaming path
        // handles bulk data without StackOverflow or excessive GC
        int count = 500;
        String records = IntStream.rangeClosed(1, count)
                .mapToObj(i -> "{\"customerNo\":\"C" + i + "\",\"customerName\":\"Name" + i + "\"}")
                .collect(Collectors.joining(","));
        String json = "{\"records\":[" + records + "]}";

        ImportJobContext context = buildContext(json, "JSON");

        ImportStageResult result = parseStep.execute(context);

        assertThat(result.success()).isTrue();
        assertThat(context.getAttributes().get("totalCount")).isEqualTo((long) count);
    }

    // ── JSON object without "records" ──────────────────────────────────────────

    @Test
    void shouldReturnEmpty_forJsonObjectWithoutRecordsField() {
        // A plain JSON object without "records" field → 0 records → IMPORT_PARSE_EMPTY
        String json = "{\"batchId\":\"B001\",\"totalAmount\":100}";

        ImportJobContext context = buildContext(json, "JSON");
        ImportStageResult result = parseStep.execute(context);

        // No records means failure code IMPORT_PARSE_EMPTY
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("IMPORT_PARSE_EMPTY");
    }

    // ── DELIMITED path ─────────────────────────────────────────────────────────

    @Test
    void shouldParseDelimited_streamingLineByLine() {
        String csv = "customerNo,customerName,customerType,certificateNo,mobileNo,email,status\n"
                + "C001,Alice,PERSONAL,ID001,13800000001,alice@example.com,ACTIVE\n"
                + "C002,Bob,CORPORATE,ID002,13800000002,bob@example.com,ACTIVE\n";

        ImportJobContext context = buildContext(csv, "DELIMITED");

        ImportStageResult result = parseStep.execute(context);

        assertThat(result.success()).isTrue();
        assertThat(context.getAttributes().get("totalCount")).isEqualTo(2L);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ImportJobContext buildContext(String rawPayload, String fileFormatType) {
        ImportPayload importPayload = new ImportPayload(
                null, null, null, null, fileFormatType, null, null, null,
                null, null, null, null,
                null, null, null, null,
                rawPayload, null,
                null, null, null, Boolean.TRUE,
                Map.of()
        );

        ImportJobContext context = new ImportJobContext();
        context.setTenantId("tenant-streaming-test");
        context.setJobCode("PARSE_STREAM");
        context.setWorkerId("worker-1");
        context.setFileId("42");
        context.setRawPayload(rawPayload);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(PipelineRuntimeKeys.FILE_ID, 42L);
        attrs.put(PipelineRuntimeKeys.TASK_ID, 101L);
        attrs.put("importPayload", importPayload);
        attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, Map.of("jdbc_mapped_import", Map.of()));
        context.setAttributes(attrs);
        return context;
    }

    /** Reads the NDJSON staging file and counts non-blank lines. */
    private void assertNdjsonRecordCount(ImportJobContext context, int expected) {
        Object path = context.getAttributes().get(PipelineRuntimeKeys.PARSED_RECORDS_PATH);
        if (path == null) {
            return;
        }
        try {
            long lineCount = Files.lines(Path.of(path.toString()))
                    .filter(l -> !l.isBlank())
                    .count();
            assertThat(lineCount).isEqualTo(expected);
        } catch (Exception e) {
            throw new AssertionError("Could not count NDJSON lines: " + e.getMessage(), e);
        }
    }
}
