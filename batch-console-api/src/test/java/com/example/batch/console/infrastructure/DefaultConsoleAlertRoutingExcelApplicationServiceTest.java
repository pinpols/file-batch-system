package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.infrastructure.monitor.DefaultConsoleAlertRoutingExcelApplicationService;
import com.example.batch.console.mapper.AlertRoutingConfigMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ExcelImportStore;
import com.example.batch.console.support.excel.InMemoryExcelImportStore;
import com.example.batch.console.support.web.ConsoleRequestMetadata;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.query.AlertRoutingQueryRequest;
import com.example.batch.console.web.request.excel.ExcelApplyRequest;
import com.example.batch.testing.TestExcelFileBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

class DefaultConsoleAlertRoutingExcelApplicationServiceTest {

  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final AlertRoutingConfigMapper alertRoutingConfigMapper =
      mock(AlertRoutingConfigMapper.class);
  private final ConfigChangeLogMapper configChangeLogMapper = mock(ConfigChangeLogMapper.class);
  private final ExcelImportStore importStore = new InMemoryExcelImportStore();
  private DefaultConsoleAlertRoutingExcelApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new DefaultConsoleAlertRoutingExcelApplicationService(
            tenantGuard,
            requestMetadataResolver,
            importStore,
            alertRoutingConfigMapper,
            configChangeLogMapper);
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "u1", "idem-1", "127.0.0.1"));
    when(tenantGuard.resolveTenant(any())).thenReturn("t1");
    doNothing().when(tenantGuard).assertTenantAllowed(anyString());
  }

  @Test
  void shouldExportAlertRoutingsAsWorkbook() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("route_code", "RT1");
    row.put("route_name", "Route 1");
    row.put("team", "ops");
    row.put("alert_group", "batch");
    row.put("severity", "ERROR");
    row.put("receiver", "slack-ops");
    row.put("group_by", "job_code");
    row.put("group_wait_seconds", 30);
    row.put("group_interval_seconds", 300);
    row.put("repeat_interval_seconds", 3600);
    row.put("enabled", true);
    row.put("description", "test route");
    when(alertRoutingConfigMapper.selectByQuery(any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(row));

    ResponseEntity<InputStreamResource> response =
        service.exportAlertRoutings(new AlertRoutingQueryRequest());
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

    try (Workbook workbook = WorkbookFactory.create(response.getBody().getInputStream())) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
      assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("alert_routing_config");
      assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("说明");
      assertThat(workbook.getSheetAt(2).getSheetName()).isEqualTo("字典");
      assertThat(workbook.getSheetAt(3).getSheetName()).isEqualTo("校验");
      XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
      Row header = sheet.getRow(0);
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("route_code");
      assertThat(header.getCell(1).getCellComment()).isNotNull();
      assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
      assertThat(sheet.getDataValidations()).hasSize(2);
      assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("RT1");
    }
  }

  @Test
  void shouldDownloadEmptyTemplate() throws Exception {
    ResponseEntity<InputStreamResource> response = service.downloadTemplate();
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

    try (Workbook workbook = WorkbookFactory.create(response.getBody().getInputStream())) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
      Sheet sheet = workbook.getSheetAt(0);
      assertThat(sheet.getSheetName()).isEqualTo("alert_routing_config");
      assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("tenant_id");
      assertThat(sheet.getLastRowNum()).isZero();
    }
  }

  @Test
  void shouldUploadPreviewAndApplyRoutingWorkbook() throws Exception {
    byte[] xlsx =
        TestExcelFileBuilder.builder()
            .sheetName("alert_routing_config")
            .headers(routingHeaders())
            .rows(List.of(validRoutingRow()))
            .build();

    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "routing.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsx);

    when(alertRoutingConfigMapper.selectByUniqueKey("t1", "RT1")).thenReturn(null);

    var upload = service.upload(file);
    assertThat(upload.rowCount()).isEqualTo(1);

    var preview = service.preview(upload.uploadToken());
    assertThat(preview.totalRows()).isEqualTo(1);
    assertThat(preview.invalidRows()).isZero();
    assertThat(preview.rows()).hasSize(1);
    assertThat(preview.rows().get(0).routeCode()).isEqualTo("RT1");

    var applyRequest = new ExcelApplyRequest();
    applyRequest.setReason("bulk maintenance");
    var apply = service.apply(upload.uploadToken(), applyRequest);
    assertThat(apply.insertedRows()).isEqualTo(1);
    assertThat(apply.updatedRows()).isZero();
    assertThat(apply.appliedRows()).isEqualTo(1);

    verify(alertRoutingConfigMapper).selectByUniqueKey("t1", "RT1");
    verify(configChangeLogMapper).insertConfigChangeLog(any());
  }

  @Test
  void shouldMarkDuplicateRouteCodesAsInvalid() throws Exception {
    byte[] xlsx =
        TestExcelFileBuilder.builder()
            .sheetName("alert_routing_config")
            .headers(routingHeaders())
            .rows(List.of(validRoutingRow(), validRoutingRow()))
            .build();

    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "routing.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsx);

    var upload = service.upload(file);
    var preview = service.preview(upload.uploadToken());

    assertThat(preview.invalidRows()).isEqualTo(1);
    assertThat(preview.issues()).hasSize(1);
    assertThat(preview.issues().get(0).messages().get(0)).contains("duplicate key in excel");
  }

  private List<String> routingHeaders() {
    return List.of(
        "tenant_id",
        "route_code",
        "route_name",
        "team",
        "alert_group",
        "severity",
        "receiver",
        "group_by",
        "group_wait_seconds",
        "group_interval_seconds",
        "repeat_interval_seconds",
        "enabled",
        "description");
  }

  private Map<String, Object> validRoutingRow() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tenant_id", "t1");
    row.put("route_code", "RT1");
    row.put("route_name", "Route 1");
    row.put("team", "ops");
    row.put("alert_group", "batch");
    row.put("severity", "ERROR");
    row.put("receiver", "slack-ops");
    row.put("group_by", "job_code");
    row.put("group_wait_seconds", 30);
    row.put("group_interval_seconds", 300);
    row.put("repeat_interval_seconds", 3600);
    row.put("enabled", true);
    row.put("description", "test route");
    return row;
  }
}
