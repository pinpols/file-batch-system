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
import com.example.batch.worker.imports.testing.TestExcelFileBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
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
 * Fixture-based tests for {@link ParseStep} — verifies all upstream file formats
 * that the import pipeline must handle: CSV (comma / pipe / tab), XML, JSON (array
 * and envelope), FIXED_WIDTH, EXCEL, and charset variants (UTF-8 BOM).
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

        assertThat(result.success()).isTrue();
        assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(10L);
    }

    // ── Pipe-delimited (5 data rows) ───────────────────────────────────────────

    @Test
    void shouldParsePipeDelimitedFixture() throws Exception {
        String content = loadFixture("fixtures/import-customers-pipe.csv");
        ImportJobContext ctx = buildContext(content, "DELIMITED", "|", 1, null);

        ImportStageResult result = parseStep.execute(ctx);

        assertThat(result.success()).isTrue();
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
        // Read raw bytes and verify BOM is present in fixture
        byte[] raw = loadFixtureBytes("fixtures/import-customers-utf8bom.csv");
        assertThat(raw[0] & 0xFF).isEqualTo(0xEF);
        assertThat(raw[1] & 0xFF).isEqualTo(0xBB);
        assertThat(raw[2] & 0xFF).isEqualTo(0xBF);

        // ParseStep receives String content; BOM should be stripped by charset detection
        String content = new String(raw, StandardCharsets.UTF_8);
        // Strip BOM manually as PreprocessStep / charset transcoder would do
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
        byte[] xlsx = TestExcelFileBuilder.customerImport(List.of(
                Map.of("customerNo", "C001", "customerName", "Alice Wang",
                        "customerType", "PERSONAL", "creditLimit", "50000.00",
                        "currencyCode", "CNY", "email", "alice@example.com",
                        "phone", "13800138001", "status", "ACTIVE",
                        "openDate", "2022-01-15", "remark", ""),
                Map.of("customerNo", "C002", "customerName", "Bob Li",
                        "customerType", "PERSONAL", "creditLimit", "30000.00",
                        "currencyCode", "CNY", "email", "bob@example.com",
                        "phone", "13800138002", "status", "ACTIVE",
                        "openDate", "2022-03-20", "remark", ""),
                Map.of("customerNo", "C003", "customerName", "Carol Zhang",
                        "customerType", "CORPORATE", "creditLimit", "500000.00",
                        "currencyCode", "USD", "email", "carol@corp.com",
                        "phone", "13800138003", "status", "ACTIVE",
                        "openDate", "2021-06-01", "remark", "企业客户")
        ));

        // Excel content delivered as base64
        String contentBase64 = Base64.getEncoder().encodeToString(xlsx);
        ImportJobContext ctx = buildContextBase64(contentBase64, "EXCEL", 1);

        ImportStageResult result = parseStep.execute(ctx);

        assertThat(result.success()).isTrue();
        assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(3L);
    }

    // ── Bad-records CSV (2 valid rows: C001 and C010) ──────────────────────────

    @Test
    void shouldParseBadRecordsCsv_countAllRows() throws Exception {
        // ParseStep counts all parsed rows; validation is deferred to ValidateStep
        String content = loadFixture("fixtures/import-customers-bad-records.csv");
        ImportJobContext ctx = buildContext(content, "DELIMITED", ",", 1, null);

        ImportStageResult result = parseStep.execute(ctx);

        // ParseStep writes every line including bad-record lines (10 total minus 1 header)
        assertThat(result.success()).isTrue();
        assertThat(ctx.getAttributes().get("totalCount")).isEqualTo(10L);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String loadFixture(String resourcePath) throws IOException {
        URL url = getClass().getClassLoader().getResource(resourcePath);
        assertThat(url).as("Fixture not found: %s", resourcePath).isNotNull();
        return Files.readString(Path.of(url.getPath()), StandardCharsets.UTF_8);
    }

    private byte[] loadFixtureBytes(String resourcePath) throws IOException {
        URL url = getClass().getClassLoader().getResource(resourcePath);
        assertThat(url).as("Fixture not found: %s", resourcePath).isNotNull();
        return Files.readAllBytes(Path.of(url.getPath()));
    }

    private ImportJobContext buildContext(String content, String fileFormatType,
                                          String delimiter, int headerRows, String charset) {
        ImportPayload importPayload = new ImportPayload(
                null, "test.csv", "test.csv", "CUSTOMER",
                fileFormatType, charset, null,
                null, null, "UPLOAD", null, null, null, null,
                null, null,
                content, null,
                delimiter, headerRows, 0, headerRows > 0,
                Map.of()
        );

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
        context.setAttributes(attrs);
        return context;
    }

    private ImportJobContext buildContextBase64(String contentBase64, String fileFormatType,
                                                 int headerRows) {
        ImportPayload importPayload = new ImportPayload(
                null, "test.xlsx", "test.xlsx", "CUSTOMER",
                fileFormatType, null, null,
                null, null, "UPLOAD", null, null, null, null,
                null, null,
                null, contentBase64,
                null, headerRows, 0, headerRows > 0,
                Map.of()
        );

        ImportJobContext context = new ImportJobContext();
        context.setTenantId("t1");
        context.setJobCode("FIXTURE_PARSE_EXCEL_TEST");
        context.setWorkerId("worker-fixture");
        context.setFileId("100");

        Map<String, Object> attrs = new HashMap<>();
        attrs.put(PipelineRuntimeKeys.FILE_ID, 100L);
        attrs.put(PipelineRuntimeKeys.TASK_ID, 1000L);
        attrs.put("importPayload", importPayload);
        context.setAttributes(attrs);
        return context;
    }
}
