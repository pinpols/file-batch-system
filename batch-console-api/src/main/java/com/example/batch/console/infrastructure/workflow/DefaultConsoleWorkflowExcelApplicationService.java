package com.example.batch.console.infrastructure.workflow;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.workflow.ConsoleWorkflowExcelApplicationService;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import com.example.batch.console.infrastructure.excel.WorkflowExcelWorkbookWriter;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import java.io.ByteArrayInputStream;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** 工作流定义 Excel 模板与导出服务。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleWorkflowExcelApplicationService
    implements ConsoleWorkflowExcelApplicationService {

  private final ConsoleTenantGuard tenantGuard;
  private final WorkflowDefinitionMapper workflowDefinitionMapper;
  private final WorkflowExcelWorkbookWriter workbookWriter;
  private final BatchDateTimeSupport dateTimeSupport;

  @Override
  public ResponseEntity<InputStreamResource> exportWorkflowExcel(
      WorkflowDefinitionQueryRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    WorkflowDefinitionQuery exportQuery =
        WorkflowDefinitionQuery.builder()
            .tenantId(tenantId)
            .workflowCode(request.getWorkflowCode())
            .workflowName(request.getWorkflowName())
            .workflowType(request.getWorkflowType())
            .version(request.getVersion())
            .enabled(request.getEnabled())
            .build();
    List<WorkflowDefinitionEntity> definitions =
        workflowDefinitionMapper.selectByQuery(exportQuery);
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(tenantId, definitions);
    String fileName =
        "workflow-maintenance-" + tenantId + "-" + dateTimeSupport.currentFileTimestamp() + ".xlsx";
    return excelResponse(workbookBytes, fileName);
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    return excelResponse(
        workbookWriter.writeMaintenanceWorkbook("template", List.of()),
        "workflow-maintenance-template.xlsx");
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
