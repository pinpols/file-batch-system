package com.example.batch.console.infrastructure.workflow;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.application.workflow.ConsolePipelineDefinitionExcelApplicationService;
import com.example.batch.console.infrastructure.excel.PipelineExcelWorkbookWriter;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/** 流水线定义 Excel 模板与导出服务。 */
@Service
@RequiredArgsConstructor
public class DefaultConsolePipelineDefinitionExcelApplicationService
    implements ConsolePipelineDefinitionExcelApplicationService {

  private static final String COL_JOB_CODE = "job_code";
  private static final String COL_VERSION = "version";

  private final ConsoleTenantGuard tenantGuard;
  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final PipelineExcelWorkbookWriter workbookWriter;
  private final BatchDateTimeSupport dateTimeSupport;

  @Override
  public ResponseEntity<InputStreamResource> exportPipelineDefinitions(
      String tenantId, String jobCode, String pipelineType, Boolean enabled) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> pipelines =
        pipelineDefinitionMapper.selectByQuery(
            resolvedTenantId, jobCode, pipelineType, enabled, null);
    List<Map<String, Object>> allSteps = new ArrayList<>();
    for (Map<String, Object> pipeline : pipelines) {
      Long pipelineId = ((Number) pipeline.get("id")).longValue();
      List<Map<String, Object>> steps =
          pipelineStepDefinitionMapper.selectByPipelineDefinitionId(pipelineId);
      String pipelineJobCode = String.valueOf(pipeline.get(COL_JOB_CODE));
      String pipelineVersion = String.valueOf(pipeline.get(COL_VERSION));
      for (Map<String, Object> step : steps) {
        Map<String, Object> enriched = new LinkedHashMap<>(step);
        enriched.put(COL_JOB_CODE, pipelineJobCode);
        enriched.put(COL_VERSION, pipelineVersion);
        allSteps.add(enriched);
      }
    }
    byte[] workbookBytes = workbookWriter.writeMaintenanceWorkbook(pipelines, allSteps);
    String fileName =
        "pipeline-definition-"
            + resolvedTenantId
            + "-"
            + dateTimeSupport.currentFileTimestamp()
            + ".xlsx";
    return excelResponse(workbookBytes, fileName);
  }

  @Override
  public ResponseEntity<InputStreamResource> downloadTemplate() {
    return excelResponse(
        workbookWriter.writeMaintenanceWorkbook(List.of(), List.of()),
        "pipeline-definition-template.xlsx");
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
