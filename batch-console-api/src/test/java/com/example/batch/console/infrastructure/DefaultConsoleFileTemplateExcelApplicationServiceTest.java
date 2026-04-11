package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileTemplateConfigMapper;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.FileTemplateExcelImportStore;
import com.example.batch.console.support.InMemoryFileTemplateExcelImportStore;
import com.example.batch.console.web.query.FileTemplateQueryRequest;
import com.example.batch.console.web.request.FileTemplateExcelApplyRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import com.example.batch.testing.TestExcelFileBuilder;

class DefaultConsoleFileTemplateExcelApplicationServiceTest {

    private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = mock(ConsoleRequestMetadataResolver.class);
    private final FileTemplateConfigMapper fileTemplateConfigMapper = mock(FileTemplateConfigMapper.class);
    private final ConfigChangeLogMapper configChangeLogMapper = mock(ConfigChangeLogMapper.class);
    private final FileTemplateExcelImportStore importStore = new InMemoryFileTemplateExcelImportStore();
    private DefaultConsoleFileTemplateExcelApplicationService service;

    @BeforeEach
    void setUp() {
        service = new DefaultConsoleFileTemplateExcelApplicationService(
                tenantGuard,
                requestMetadataResolver,
                fileTemplateConfigMapper,
                configChangeLogMapper,
                importStore
        );
        when(requestMetadataResolver.current()).thenReturn(new ConsoleRequestMetadata(
                "req-1",
                "trace-1",
                "t1",
                "u1",
                "idem-1",
                "127.0.0.1"
        ));
        when(tenantGuard.resolveTenant(any())).thenReturn("t1");
        doNothing().when(tenantGuard).assertTenantAllowed(anyString());
    }

    @Test
    void shouldExportTemplateConfigsAsWorkbook() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", "t1");
        row.put("template_code", "TPL1");
        row.put("template_name", "Template 1");
        row.put("template_type", "IMPORT");
        row.put("file_format_type", "EXCEL");
        row.put("checksum_type", "NONE");
        row.put("compress_type", "NONE");
        row.put("encrypt_type", "NONE");
        row.put("enabled", true);
        row.put("version", 1);
        when(fileTemplateConfigMapper.selectByQuery(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(row));

        ResponseEntity<InputStreamResource> response = service.exportFileTemplates(new FileTemplateQueryRequest());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        try (Workbook workbook = WorkbookFactory.create(response.getBody().getInputStream())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("file_template_config");
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("README");
            assertThat(workbook.getSheetAt(2).getSheetName()).isEqualTo("DICT");
            assertThat(workbook.getSheetAt(3).getSheetName()).isEqualTo("VALIDATION");
            XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("template_code");
            assertThat(header.getCell(1).getCellComment()).isNotNull();
            assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(sheet.getDataValidations()).hasSize(13);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("TPL1");
        }
    }

    @Test
    void shouldUploadPreviewAndApplyTemplateWorkbook() throws Exception {
        byte[] xlsx = TestExcelFileBuilder.builder()
                .sheetName("file_template_config")
                .headers(templateHeaders())
                .rows(List.of(validTemplateRow()))
                .build();

        MockMultipartFile file = new MockMultipartFile("file", "template.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        when(fileTemplateConfigMapper.selectByUniqueKey("t1", "TPL1", 1)).thenReturn(null);

        var upload = service.upload(file);
        assertThat(upload.rowCount()).isEqualTo(1);

        var preview = service.preview(upload.uploadToken());
        assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(preview.invalidRows()).isZero();
        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).templateCode()).isEqualTo("TPL1");

        var applyRequest = new FileTemplateExcelApplyRequest();
        applyRequest.setReason("bulk maintenance");
        var apply = service.apply(upload.uploadToken(), applyRequest);
        assertThat(apply.insertedRows()).isEqualTo(1);
        assertThat(apply.updatedRows()).isZero();
        assertThat(apply.appliedRows()).isEqualTo(1);

        verify(fileTemplateConfigMapper).selectByUniqueKey("t1", "TPL1", 1);
        verify(configChangeLogMapper).insertConfigChangeLog(any());
    }

    @Test
    void shouldRejectTemplateWorkbookWhenHeaderMissing() {
        byte[] xlsx = TestExcelFileBuilder.builder()
                .sheetName("file_template_config")
                .row(List.of("t1", "TPL1", "Template 1"))
                .build();

        MockMultipartFile file = new MockMultipartFile("file", "template.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        assertThatThrownBy(() -> service.upload(file))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("excel header missing");
    }

    private List<String> templateHeaders() {
        return List.of(
                "tenant_id",
                "template_code",
                "template_name",
                "template_type",
                "biz_type",
                "file_format_type",
                "charset",
                "target_charset",
                "with_bom",
                "line_separator",
                "delimiter",
                "quote_char",
                "escape_char",
                "record_length",
                "header_rows",
                "footer_rows",
                "header_template",
                "trailer_template",
                "checksum_type",
                "compress_type",
                "encrypt_type",
                "naming_rule",
                "field_mappings",
                "validation_rule_set",
                "default_query_code",
                "default_query_sql",
                "query_param_schema",
                "streaming_enabled",
                "page_size",
                "fetch_size",
                "chunk_size",
                "preview_masking_enabled",
                "error_line_masking_enabled",
                "log_masking_enabled",
                "content_encryption_enabled",
                "encryption_key_ref",
                "download_requires_approval",
                "masking_rule_set",
                "enabled",
                "version",
                "description"
        );
    }

    private Map<String, Object> validTemplateRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", "t1");
        row.put("template_code", "TPL1");
        row.put("template_name", "Template 1");
        row.put("template_type", "IMPORT");
        row.put("file_format_type", "EXCEL");
        row.put("checksum_type", "NONE");
        row.put("compress_type", "NONE");
        row.put("encrypt_type", "NONE");
        row.put("streaming_enabled", true);
        row.put("page_size", 1000);
        row.put("fetch_size", 1000);
        row.put("chunk_size", 500);
        row.put("enabled", true);
        row.put("version", 1);
        return row;
    }
}
