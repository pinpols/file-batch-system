package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.FileChannelConfigMapper;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.FileChannelExcelImportStore;
import com.example.batch.console.support.InMemoryFileChannelExcelImportStore;
import com.example.batch.console.web.query.FileChannelQueryRequest;
import com.example.batch.console.web.request.FileChannelExcelApplyRequest;
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

class DefaultConsoleFileChannelExcelApplicationServiceTest {

    private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    private final ConsoleRequestMetadataResolver requestMetadataResolver = mock(ConsoleRequestMetadataResolver.class);
    private final FileChannelConfigMapper fileChannelConfigMapper = mock(FileChannelConfigMapper.class);
    private final ConfigChangeLogMapper configChangeLogMapper = mock(ConfigChangeLogMapper.class);
    private final FileChannelExcelImportStore importStore = new InMemoryFileChannelExcelImportStore();
    private DefaultConsoleFileChannelExcelApplicationService service;

    @BeforeEach
    void setUp() {
        service = new DefaultConsoleFileChannelExcelApplicationService(
                tenantGuard,
                requestMetadataResolver,
                fileChannelConfigMapper,
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
    void shouldExportChannelConfigsAsWorkbook() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", "t1");
        row.put("channel_code", "CH1");
        row.put("channel_name", "Channel 1");
        row.put("channel_type", "API");
        row.put("auth_type", "TOKEN");
        row.put("config_json", "{\"token\":\"t\"}");
        row.put("receipt_policy", "SYNC");
        row.put("timeout_seconds", 30);
        row.put("enabled", true);
        when(fileChannelConfigMapper.selectByQuery(any(), any(), any(), any(), any())).thenReturn(List.of(row));

        ResponseEntity<InputStreamResource> response = service.exportFileChannels(new FileChannelQueryRequest());
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        try (Workbook workbook = WorkbookFactory.create(response.getBody().getInputStream())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("file_channel_config");
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("README");
            assertThat(workbook.getSheetAt(2).getSheetName()).isEqualTo("DICT");
            assertThat(workbook.getSheetAt(3).getSheetName()).isEqualTo("VALIDATION");
            XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("channel_code");
            assertThat(header.getCell(1).getCellComment()).isNotNull();
            assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(sheet.getDataValidations()).hasSize(4);
            assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("CH1");
        }
    }

    @Test
    void shouldUploadPreviewAndApplyChannelWorkbook() throws Exception {
        byte[] xlsx = TestExcelFileBuilder.builder()
                .sheetName("file_channel_config")
                .headers(channelHeaders())
                .rows(List.of(validChannelRow()))
                .build();

        MockMultipartFile file = new MockMultipartFile("file", "channel.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        when(fileChannelConfigMapper.selectByUniqueKey("t1", "CH1")).thenReturn(null);

        var upload = service.upload(file);
        assertThat(upload.rowCount()).isEqualTo(1);

        var preview = service.preview(upload.uploadToken());
        assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(preview.invalidRows()).isZero();
        assertThat(preview.rows()).hasSize(1);
        assertThat(preview.rows().get(0).channelCode()).isEqualTo("CH1");

        var applyRequest = new FileChannelExcelApplyRequest();
        applyRequest.setReason("bulk maintenance");
        var apply = service.apply(upload.uploadToken(), applyRequest);
        assertThat(apply.insertedRows()).isEqualTo(1);
        assertThat(apply.updatedRows()).isZero();
        assertThat(apply.appliedRows()).isEqualTo(1);

        verify(fileChannelConfigMapper).selectByUniqueKey("t1", "CH1");
        verify(configChangeLogMapper).insertConfigChangeLog(any());
    }

    @Test
    void shouldMarkDuplicateChannelRowsAsInvalid() throws Exception {
        byte[] xlsx = TestExcelFileBuilder.builder()
                .sheetName("file_channel_config")
                .headers(channelHeaders())
                .rows(List.of(validChannelRow(), validChannelRow()))
                .build();

        MockMultipartFile file = new MockMultipartFile("file", "channel.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        when(fileChannelConfigMapper.selectByUniqueKey("t1", "CH1")).thenReturn(null);

        var upload = service.upload(file);
        var preview = service.preview(upload.uploadToken());

        assertThat(preview.invalidRows()).isEqualTo(1);
        assertThat(preview.issues()).hasSize(1);
        assertThat(preview.issues().get(0).messages().get(0)).contains("duplicate channel code in excel");
    }

    private List<String> channelHeaders() {
        return List.of(
                "tenant_id",
                "channel_code",
                "channel_name",
                "channel_type",
                "target_endpoint",
                "auth_type",
                "config_json",
                "receipt_policy",
                "timeout_seconds",
                "enabled"
        );
    }

    private Map<String, Object> validChannelRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", "t1");
        row.put("channel_code", "CH1");
        row.put("channel_name", "Channel 1");
        row.put("channel_type", "API");
        row.put("target_endpoint", "https://api.example.com/push");
        row.put("auth_type", "TOKEN");
        row.put("config_json", "{\"token\":\"t\"}");
        row.put("receipt_policy", "SYNC");
        row.put("timeout_seconds", 30);
        row.put("enabled", true);
        return row;
    }
}
