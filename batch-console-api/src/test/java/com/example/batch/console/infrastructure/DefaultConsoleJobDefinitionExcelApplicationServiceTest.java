package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.infrastructure.excel.JobDefinitionExcelWorkbookWriter;
import com.example.batch.console.infrastructure.job.DefaultConsoleJobDefinitionExcelApplicationService;
import com.example.batch.console.mapper.BatchWindowMapper;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.mapper.ResourceQueueMapper;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.InMemoryJobDefinitionExcelImportStore;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import com.example.batch.console.web.request.job.JobDefinitionExcelApplyRequest;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

class DefaultConsoleJobDefinitionExcelApplicationServiceTest {

  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final JobDefinitionMapper jobDefinitionMapper = mock(JobDefinitionMapper.class);
  private final ConfigChangeLogMapper configChangeLogMapper = mock(ConfigChangeLogMapper.class);
  private final InMemoryJobDefinitionExcelImportStore importStore =
      new InMemoryJobDefinitionExcelImportStore();
  private final ResourceQueueMapper resourceQueueMapper = mock(ResourceQueueMapper.class);
  private final BatchWindowMapper batchWindowMapper = mock(BatchWindowMapper.class);
  private final BusinessCalendarMapper businessCalendarMapper = mock(BusinessCalendarMapper.class);
  private DefaultConsoleJobDefinitionExcelApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new DefaultConsoleJobDefinitionExcelApplicationService(
            tenantGuard,
            requestMetadataResolver,
            jobDefinitionMapper,
            configChangeLogMapper,
            importStore,
            resourceQueueMapper,
            batchWindowMapper,
            businessCalendarMapper,
            new JobDefinitionExcelWorkbookWriter());
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "u1", "idem-1", "127.0.0.1"));
    when(tenantGuard.resolveTenant(any())).thenReturn("t1");
    doNothing().when(tenantGuard).assertTenantAllowed(anyString());
  }

  @Test
  void shouldExportJobDefinitionsAsWorkbook() throws Exception {
    JobDefinitionEntity entity = new JobDefinitionEntity();
    entity.setTenantId("t1");
    entity.setJobCode("JOB1");
    entity.setJobName("Job 1");
    entity.setJobType("GENERAL");
    entity.setQueueCode("default");
    entity.setWorkerGroup("wg1");
    entity.setScheduleType("CRON");
    entity.setScheduleExpr("0 0 * * *");
    entity.setCalendarCode("cal1");
    entity.setWindowCode("window1");
    entity.setRetryPolicy("FIXED");
    entity.setRetryMaxCount(3);
    entity.setTimeoutSeconds(30);
    entity.setShardStrategy("NONE");
    entity.setExecutionHandler("handler");
    entity.setParamSchema("{\"type\":\"object\"}");
    entity.setDefaultParams("{\"x\":1}");
    entity.setEnabled(true);
    entity.setDescription("desc");
    when(jobDefinitionMapper.selectByQuery(any())).thenReturn(List.of(entity));

    ResponseEntity<InputStreamResource> response =
        service.exportJobDefinitions(new JobDefinitionQueryRequest());
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

    try (Workbook workbook = WorkbookFactory.create(response.getBody().getInputStream())) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
      assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("job_definition");
      assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("说明");
      assertThat(workbook.getSheetAt(2).getSheetName()).isEqualTo("字典");
      assertThat(workbook.getSheetAt(3).getSheetName()).isEqualTo("校验");
      XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
      Row header = sheet.getRow(0);
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("job_code");
      assertThat(header.getCell(1).getCellComment()).isNotNull();
      assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
      assertThat(sheet.getDataValidations()).hasSize(5);
      assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("JOB1");
    }
  }

  @Test
  void shouldUploadPreviewAndApplyJobDefinitionWorkbook() throws Exception {
    byte[] xlsx = buildWorkbook("GENERAL");
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "job.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsx);

    JobDefinitionEntity existing = new JobDefinitionEntity();
    existing.setTenantId("t1");
    existing.setJobCode("JOB1");
    existing.setJobName("Job 1");
    existing.setJobType("GENERAL");
    existing.setQueueCode("default");
    existing.setWorkerGroup("wg1");
    existing.setScheduleType("CRON");
    existing.setScheduleExpr("0 0 * * *");
    existing.setCalendarCode("cal1");
    existing.setWindowCode("window1");
    existing.setRetryPolicy("FIXED");
    existing.setRetryMaxCount(3);
    existing.setTimeoutSeconds(30);
    existing.setShardStrategy("NONE");
    existing.setExecutionHandler("handler");
    existing.setParamSchema("{\"type\":\"object\"}");
    existing.setDefaultParams("{\"x\":1}");
    existing.setEnabled(true);
    existing.setDescription("desc");

    when(jobDefinitionMapper.selectByUniqueKey("t1", "JOB1")).thenReturn(existing, existing);

    var upload = service.upload(file);
    assertThat(upload.rowCount()).isEqualTo(1);

    var preview = service.preview(upload.uploadToken());
    assertThat(preview.totalRows()).isEqualTo(1);
    assertThat(preview.invalidRows()).isZero();
    assertThat(preview.rows()).hasSize(1);
    assertThat(preview.rows().get(0).jobCode()).isEqualTo("JOB1");

    JobDefinitionExcelApplyRequest request = new JobDefinitionExcelApplyRequest();
    request.setReason("bulk maintenance");
    var apply = service.apply(upload.uploadToken(), request);
    assertThat(apply.appliedRows()).isEqualTo(1);
    assertThat(apply.updatedRows()).isEqualTo(1);

    verify(jobDefinitionMapper, times(3)).selectByUniqueKey("t1", "JOB1");
    verify(jobDefinitionMapper).updateJobDefinitionMaintenance(any());
    verify(configChangeLogMapper).insertConfigChangeLog(any());
  }

  @Test
  void shouldMarkReadOnlyJobDefinitionFieldsAsInvalid() throws Exception {
    byte[] xlsx = buildWorkbook("SPECIAL");
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "job.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsx);

    JobDefinitionEntity existing = new JobDefinitionEntity();
    existing.setTenantId("t1");
    existing.setJobCode("JOB1");
    existing.setJobName("Job 1");
    existing.setJobType("GENERAL");
    existing.setQueueCode("default");
    existing.setWorkerGroup("wg1");
    existing.setScheduleType("CRON");
    existing.setScheduleExpr("0 0 * * *");
    existing.setCalendarCode("cal1");
    existing.setWindowCode("window1");
    existing.setRetryPolicy("FIXED");
    existing.setRetryMaxCount(3);
    existing.setTimeoutSeconds(30);
    existing.setShardStrategy("NONE");
    existing.setExecutionHandler("handler");
    existing.setParamSchema("{\"type\":\"object\"}");
    existing.setDefaultParams("{\"x\":1}");
    existing.setEnabled(true);
    existing.setDescription("desc");

    when(jobDefinitionMapper.selectByUniqueKey("t1", "JOB1")).thenReturn(existing);

    var upload = service.upload(file);
    var preview = service.preview(upload.uploadToken());

    assertThat(preview.invalidRows()).isEqualTo(1);
    assertThat(preview.issues()).hasSize(1);
    assertThat(preview.issues().get(0).messages().get(0)).contains("read-only");
  }

  private byte[] buildWorkbook(String jobType) throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("job_definition");
      writeRow(
          sheet,
          0,
          List.of(
              "tenant_id",
              "job_code",
              "job_name",
              "job_type",
              "queue_code",
              "worker_group",
              "schedule_type",
              "schedule_expr",
              "calendar_code",
              "window_code",
              "retry_policy",
              "retry_max_count",
              "timeout_seconds",
              "shard_strategy",
              "execution_handler",
              "param_schema",
              "default_params",
              "enabled",
              "description"));
      writeRow(
          sheet,
          1,
          List.of(
              "t1",
              "JOB1",
              "Job 1 updated",
              jobType,
              "default",
              "wg1",
              "CRON",
              "0 0 * * *",
              "cal1",
              "window1",
              "FIXED",
              3,
              30,
              "NONE",
              "handler",
              "{\"type\":\"object\"}",
              "{\"x\":1}",
              true,
              "desc updated"));
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private void writeRow(Sheet sheet, int rowIndex, List<Object> values) {
    Row row = sheet.createRow(rowIndex);
    for (int i = 0; i < values.size(); i++) {
      Object value = values.get(i);
      if (value instanceof Number number) {
        row.createCell(i).setCellValue(number.doubleValue());
      } else if (value instanceof Boolean bool) {
        row.createCell(i).setCellValue(bool);
      } else {
        row.createCell(i).setCellValue(String.valueOf(value));
      }
    }
  }
}
