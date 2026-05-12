package com.example.batch.console.infrastructure.job;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.job.ConsoleJobDefinitionExcelApplicationService;
import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import com.example.batch.console.infrastructure.excel.JobDefinitionExcelWorkbookWriter;
import com.example.batch.console.mapper.JobDefinitionMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.query.JobDefinitionQueryRequest;
import java.io.ByteArrayInputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** 作业定义 Excel 模板与导出服务。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobDefinitionExcelApplicationService
    implements ConsoleJobDefinitionExcelApplicationService {

  private final ConsoleTenantGuard tenantGuard;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final JobDefinitionExcelWorkbookWriter workbookWriter;
  private final BatchDateTimeSupport dateTimeSupport;

  @Override
  public ResponseEntity<InputStreamResource> exportJobDefinitions(
      JobDefinitionQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    JobDefinitionQuery exportQuery =
        JobDefinitionQuery.builder()
            .tenantId(tenantId)
            .jobCode(request.getJobCode())
            .jobName(request.getJobName())
            .jobType(request.getJobType())
            .workerGroup(request.getWorkerGroup())
            .queueCode(request.getQueueCode())
            .scheduleType(request.getScheduleType())
            .enabled(request.getEnabled())
            .build();
    List<JobDefinitionEntity> rows = jobDefinitionMapper.selectByQuery(exportQuery);
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(rows);
    String fileName =
        "job-definition-maintenance-"
            + tenantId
            + "-"
            + dateTimeSupport.currentFileTimestamp()
            + ".xlsx";
    return excelResponse(workbookBytes, fileName);
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    return excelResponse(
        workbookWriter.writeMaintenanceWorkbook(List.of()),
        "job-definition-maintenance-template.xlsx");
  }

  private ResponseEntity<InputStreamResource> excelResponse(byte[] workbookBytes, String fileName) {
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(fileName).build().toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(new InputStreamResource(new ByteArrayInputStream(workbookBytes)));
  }
}
