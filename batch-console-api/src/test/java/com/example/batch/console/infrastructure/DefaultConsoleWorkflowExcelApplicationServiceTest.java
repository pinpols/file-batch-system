package com.example.batch.console.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.WorkflowDefinitionMapper;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.InMemoryWorkflowExcelImportStore;
import com.example.batch.console.web.query.WorkflowDefinitionQueryRequest;
import com.example.batch.console.web.request.WorkflowExcelApplyRequest;
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

class DefaultConsoleWorkflowExcelApplicationServiceTest {

  private final ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final WorkflowDefinitionMapper workflowDefinitionMapper =
      mock(WorkflowDefinitionMapper.class);
  private final WorkflowNodeMapper workflowNodeMapper = mock(WorkflowNodeMapper.class);
  private final WorkflowEdgeMapper workflowEdgeMapper = mock(WorkflowEdgeMapper.class);
  private final ConfigChangeLogMapper configChangeLogMapper = mock(ConfigChangeLogMapper.class);
  private final InMemoryWorkflowExcelImportStore importStore =
      new InMemoryWorkflowExcelImportStore();
  private DefaultConsoleWorkflowExcelApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new DefaultConsoleWorkflowExcelApplicationService(
            tenantGuard,
            requestMetadataResolver,
            workflowDefinitionMapper,
            workflowNodeMapper,
            workflowEdgeMapper,
            configChangeLogMapper,
            importStore,
            new WorkflowExcelWorkbookWriter(workflowNodeMapper, workflowEdgeMapper));
    when(requestMetadataResolver.current())
        .thenReturn(
            new ConsoleRequestMetadata("req-1", "trace-1", "t1", "u1", "idem-1", "127.0.0.1"));
    when(tenantGuard.resolveTenant(any())).thenReturn("t1");
    doNothing().when(tenantGuard).assertTenantAllowed(anyString());
  }

  @Test
  void shouldExportWorkflowMaintenanceWorkbook() throws Exception {
    WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
    definition.setId(10L);
    definition.setTenantId("t1");
    definition.setWorkflowCode("WF1");
    definition.setWorkflowName("Workflow 1");
    definition.setWorkflowType("DAG");
    definition.setVersion(1);
    definition.setEnabled(true);
    definition.setDescription("desc");

    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("NODE1");
    node.setNodeName("Node 1");
    node.setNodeType("TASK");
    node.setRelatedJobCode("JOB1");
    node.setRelatedPipelineCode("PIPE1");
    node.setWorkerGroup("WG1");
    node.setWindowCode("W1");
    node.setNodeOrder(1);
    node.setRetryPolicy("FIXED");
    node.setRetryMaxCount(3);
    node.setTimeoutSeconds(30);
    node.setNodeParams("{\"mode\":\"test\"}");
    node.setEnabled(true);

    WorkflowEdgeEntity edge = new WorkflowEdgeEntity();
    edge.setFromNodeCode("NODE1");
    edge.setToNodeCode("NODE2");
    edge.setEdgeType("SUCCESS");
    edge.setConditionExpr("${ok}");
    edge.setEnabled(true);

    when(workflowDefinitionMapper.selectByQuery(any())).thenReturn(List.of(definition));
    when(workflowNodeMapper.selectByQuery(any())).thenReturn(List.of(node));
    when(workflowEdgeMapper.selectByQuery(any())).thenReturn(List.of(edge));

    ResponseEntity<InputStreamResource> response =
        service.exportWorkflowExcel(new WorkflowDefinitionQueryRequest());
    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

    try (Workbook workbook = WorkbookFactory.create(response.getBody().getInputStream())) {
      assertThat(workbook.getNumberOfSheets()).isEqualTo(6);
      assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("workflow_definition");
      assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("workflow_node");
      assertThat(workbook.getSheetAt(2).getSheetName()).isEqualTo("workflow_edge");
      assertThat(workbook.getSheetAt(3).getSheetName()).isEqualTo("说明");
      assertThat(workbook.getSheetAt(4).getSheetName()).isEqualTo("字典");
      assertThat(workbook.getSheetAt(5).getSheetName()).isEqualTo("校验");
      XSSFSheet sheet = (XSSFSheet) workbook.getSheetAt(0);
      XSSFSheet nodeSheet = (XSSFSheet) workbook.getSheetAt(1);
      XSSFSheet edgeSheet = (XSSFSheet) workbook.getSheetAt(2);
      Row header = sheet.getRow(0);
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("workflow_code");
      assertThat(header.getCell(1).getCellComment()).isNotNull();
      assertThat(sheet.getCTWorksheet().isSetAutoFilter()).isTrue();
      assertThat(sheet.getDataValidations()).hasSize(2);
      assertThat(nodeSheet.getDataValidations()).hasSize(3);
      assertThat(edgeSheet.getDataValidations()).hasSize(2);
      assertThat(sheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("WF1");
    }
  }

  @Test
  void shouldUploadPreviewAndApplyWorkflowWorkbook() throws Exception {
    byte[] xlsx = buildWorkbook();
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "workflow.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsx);

    WorkflowDefinitionEntity savedDefinition = new WorkflowDefinitionEntity();
    savedDefinition.setId(10L);
    savedDefinition.setTenantId("t1");
    savedDefinition.setWorkflowCode("WF1");
    savedDefinition.setVersion(1);

    when(workflowDefinitionMapper.selectByUniqueKey("t1", "WF1", 1))
        .thenReturn(null, savedDefinition);
    when(workflowNodeMapper.selectByUniqueKey(10L, "NODE1")).thenReturn(null);
    when(workflowEdgeMapper.selectByUniqueKey(10L, "NODE1", "NODE2", "SUCCESS")).thenReturn(null);

    var upload = service.upload(file);
    assertThat(upload.definitionRows()).isEqualTo(1);
    assertThat(upload.nodeRows()).isEqualTo(1);
    assertThat(upload.edgeRows()).isEqualTo(1);

    var preview = service.preview(upload.uploadToken());
    assertThat(preview.totalRows()).isEqualTo(3);
    assertThat(preview.invalidRows()).isZero();
    assertThat(preview.definitions()).hasSize(1);
    assertThat(preview.nodes()).hasSize(1);
    assertThat(preview.edges()).hasSize(1);

    WorkflowExcelApplyRequest request = new WorkflowExcelApplyRequest();
    request.setReason("bulk maintenance");
    var apply = service.apply(upload.uploadToken(), request);
    assertThat(apply.insertedDefinitions()).isEqualTo(1);
    assertThat(apply.updatedDefinitions()).isZero();
    assertThat(apply.insertedNodes()).isEqualTo(1);
    assertThat(apply.updatedNodes()).isZero();
    assertThat(apply.insertedEdges()).isEqualTo(1);
    assertThat(apply.updatedEdges()).isZero();

    verify(workflowDefinitionMapper, times(2)).selectByUniqueKey("t1", "WF1", 1);
    verify(configChangeLogMapper).insertConfigChangeLog(any());
  }

  @Test
  void shouldMarkWorkflowRowsInvalidWhenDefinitionReferenceIsMissing() throws Exception {
    byte[] xlsx = buildWorkbookWithMissingDefinitionReference();
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "workflow.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            xlsx);

    WorkflowDefinitionEntity savedDefinition = new WorkflowDefinitionEntity();
    savedDefinition.setId(10L);
    savedDefinition.setTenantId("t1");
    savedDefinition.setWorkflowCode("WF1");
    savedDefinition.setVersion(2);

    when(workflowDefinitionMapper.selectByUniqueKey("t1", "WF1", 2)).thenReturn(savedDefinition);

    var upload = service.upload(file);
    var preview = service.preview(upload.uploadToken());

    assertThat(preview.invalidRows()).isEqualTo(2);
    assertThat(preview.issues()).hasSize(2);
    assertThat(
            preview.issues().get(0).messages().stream()
                .anyMatch(message -> message.contains("missing definition")))
        .isTrue();
  }

  private byte[] buildWorkbook() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet definitionSheet = workbook.createSheet("workflow_definition");
      Sheet nodeSheet = workbook.createSheet("workflow_node");
      Sheet edgeSheet = workbook.createSheet("workflow_edge");
      writeRow(
          definitionSheet,
          0,
          List.of(
              "tenant_id",
              "workflow_code",
              "workflow_name",
              "workflow_type",
              "version",
              "enabled",
              "description"));
      writeRow(definitionSheet, 1, List.of("t1", "WF1", "Workflow 1", "DAG", 1, true, "desc"));
      writeRow(
          nodeSheet,
          0,
          List.of(
              "tenant_id",
              "workflow_code",
              "workflow_version",
              "node_code",
              "node_name",
              "node_type",
              "related_job_code",
              "related_pipeline_code",
              "worker_group",
              "window_code",
              "node_order",
              "retry_policy",
              "retry_max_count",
              "timeout_seconds",
              "node_params",
              "enabled"));
      writeRow(
          nodeSheet,
          1,
          List.of(
              "t1",
              "WF1",
              1,
              "NODE1",
              "Node 1",
              "TASK",
              "JOB1",
              "PIPE1",
              "WG1",
              "W1",
              1,
              "FIXED",
              3,
              30,
              "{\"mode\":\"test\"}",
              true));
      writeRow(
          edgeSheet,
          0,
          List.of(
              "tenant_id",
              "workflow_code",
              "workflow_version",
              "from_node_code",
              "to_node_code",
              "edge_type",
              "condition_expr",
              "enabled"));
      writeRow(edgeSheet, 1, List.of("t1", "WF1", 1, "NODE1", "NODE2", "SUCCESS", "${ok}", true));
      workbook.write(out);
      return out.toByteArray();
    }
  }

  private byte[] buildWorkbookWithMissingDefinitionReference() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet definitionSheet = workbook.createSheet("workflow_definition");
      Sheet nodeSheet = workbook.createSheet("workflow_node");
      Sheet edgeSheet = workbook.createSheet("workflow_edge");
      writeRow(
          definitionSheet,
          0,
          List.of(
              "tenant_id",
              "workflow_code",
              "workflow_name",
              "workflow_type",
              "version",
              "enabled",
              "description"));
      writeRow(definitionSheet, 1, List.of("t1", "WF1", "Workflow 1", "DAG", 2, true, "desc"));
      writeRow(
          nodeSheet,
          0,
          List.of(
              "tenant_id",
              "workflow_code",
              "workflow_version",
              "node_code",
              "node_name",
              "node_type",
              "related_job_code",
              "related_pipeline_code",
              "worker_group",
              "window_code",
              "node_order",
              "retry_policy",
              "retry_max_count",
              "timeout_seconds",
              "node_params",
              "enabled"));
      writeRow(
          nodeSheet,
          1,
          List.of(
              "t1",
              "WF1",
              1,
              "NODE1",
              "Node 1",
              "TASK",
              "JOB1",
              "PIPE1",
              "WG1",
              "W1",
              1,
              "FIXED",
              3,
              30,
              "{\"mode\":\"test\"}",
              true));
      writeRow(
          edgeSheet,
          0,
          List.of(
              "tenant_id",
              "workflow_code",
              "workflow_version",
              "from_node_code",
              "to_node_code",
              "edge_type",
              "condition_expr",
              "enabled"));
      writeRow(edgeSheet, 1, List.of("t1", "WF1", 1, "NODE1", "NODE2", "SUCCESS", "${ok}", true));
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
