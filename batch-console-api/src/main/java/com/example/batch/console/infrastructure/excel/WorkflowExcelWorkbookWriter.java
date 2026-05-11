package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_DESCRIPTION;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_EDGE_TYPE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_ENABLED;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_NODE_TYPE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_RETRY_POLICY;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.COL_WORKFLOW_TYPE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.DEF_COLUMNS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.DEF_COLUMN_GUIDES;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.DEF_SHEET;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.EDGE_COLUMNS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.EDGE_COLUMN_GUIDES;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.EDGE_SHEET;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.EDGE_SUCCESS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.GUIDE_FALSE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.GUIDE_TRUE;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.NODE_COLUMNS;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.NODE_COLUMN_GUIDES;
import static com.example.batch.console.infrastructure.excel.WorkflowExcelColumnMetadata.NODE_SHEET;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeCell;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowType;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.infrastructure.workflow.DefaultConsoleWorkflowExcelApplicationService;
import com.example.batch.console.mapper.WorkflowEdgeMapper;
import com.example.batch.console.mapper.WorkflowNodeMapper;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowDefinitionRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowEdgeRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowNodeRow;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowExcelRowIssueResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: 从 {@link DefaultConsoleWorkflowExcelApplicationService} 抽出的
 * Excel workbook 写入器(覆盖导出 + 预览两类输出)。
 *
 * <p>原 service 内 ~280 行 POI 写盘逻辑("写表头/写正文行/下拉校验/说明 sheet/字典 shet/校验 sheet/批注")集中到本类:
 *
 * <ul>
 *   <li>{@link #writeMaintenanceWorkbook} — 维护态导出(包含已存在的 definition + 节点 + 边)
 *   <li>{@link #writePreviewWorkbook} — 上传预览(rows + 校验问题 → 高亮批注)
 * </ul>
 *
 * <p>两个公开方法都不抛 checked,内部 IOException 折叠为 {@link BizException};POI 资源用 try-with-resources 兜底。
 */
@Component
@RequiredArgsConstructor
public class WorkflowExcelWorkbookWriter {

  private static final Set<String> WORKFLOW_TYPES = DictEnum.codes(WorkflowType.class);
  private static final Set<String> NODE_TYPES = DictEnum.codes(WorkflowNodeType.class);
  private static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);
  private static final Set<String> EDGE_TYPES = DictEnum.codes(WorkflowEdgeType.class);

  private final WorkflowNodeMapper workflowNodeMapper;
  private final WorkflowEdgeMapper workflowEdgeMapper;
  private final MessageSource messageSource;

  /** 维护态导出:每个 definition 同步带出对应 nodes + edges,含 README/字典/校验 sheet。 */
  public byte[] writeMaintenanceWorkbook(
      String tenantId, List<WorkflowDefinitionEntity> definitions) {
    Locale locale = LocaleContextHolder.getLocale();
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet definitionSheet = workbook.createSheet(DEF_SHEET);
      Sheet nodeSheet = workbook.createSheet(NODE_SHEET);
      Sheet edgeSheet = workbook.createSheet(EDGE_SHEET);
      definitionSheet.createFreezePane(0, 1, 0, 1);
      nodeSheet.createFreezePane(0, 1, 0, 1);
      edgeSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(
          definitionSheet, DEF_COLUMNS, DEF_COLUMN_GUIDES, workbook, messageSource, locale);
      writeTemplateHeaders(
          nodeSheet, NODE_COLUMNS, NODE_COLUMN_GUIDES, workbook, messageSource, locale);
      writeTemplateHeaders(
          edgeSheet, EDGE_COLUMNS, EDGE_COLUMN_GUIDES, workbook, messageSource, locale);

      writeDefinitionSheet(definitionSheet, definitions);
      int nodeRowIndex = 1;
      int edgeRowIndex = 1;
      for (WorkflowDefinitionEntity definition : definitions) {
        nodeRowIndex = writeNodeSheet(nodeSheet, tenantId, definition, nodeRowIndex);
        edgeRowIndex = writeEdgeSheet(edgeSheet, tenantId, definition, edgeRowIndex);
      }

      applyValidations(definitionSheet, nodeSheet, edgeSheet, locale);
      setWidths(definitionSheet, DEF_COLUMNS);
      setWidths(nodeSheet, NODE_COLUMNS);
      setWidths(edgeSheet, EDGE_COLUMNS);
      createReadmeSheet(workbook, locale);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.generate_failed");
    }
  }

  /** 预览工作簿:user 上传内容回填 + 校验问题 expand 成批注 + populate 校验 sheet。 */
  public byte[] writePreviewWorkbook(
      List<WorkflowDefinitionRow> definitions,
      List<WorkflowNodeRow> nodes,
      List<WorkflowEdgeRow> edges,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    Locale locale = LocaleContextHolder.getLocale();
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      Sheet definitionSheet = workbook.createSheet(DEF_SHEET);
      Sheet nodeSheet = workbook.createSheet(NODE_SHEET);
      Sheet edgeSheet = workbook.createSheet(EDGE_SHEET);
      definitionSheet.createFreezePane(0, 1, 0, 1);
      nodeSheet.createFreezePane(0, 1, 0, 1);
      edgeSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(
          definitionSheet, DEF_COLUMNS, DEF_COLUMN_GUIDES, workbook, messageSource, locale);
      writeTemplateHeaders(
          nodeSheet, NODE_COLUMNS, NODE_COLUMN_GUIDES, workbook, messageSource, locale);
      writeTemplateHeaders(
          edgeSheet, EDGE_COLUMNS, EDGE_COLUMN_GUIDES, workbook, messageSource, locale);

      populatePreviewDefinitionSheet(definitionSheet, definitions);
      populatePreviewNodeSheet(nodeSheet, nodes);
      populatePreviewEdgeSheet(edgeSheet, edges);

      applyValidations(definitionSheet, nodeSheet, edgeSheet, locale);
      setWidths(definitionSheet, DEF_COLUMNS);
      setWidths(nodeSheet, NODE_COLUMNS);
      setWidths(edgeSheet, EDGE_COLUMNS);
      createReadmeSheet(workbook, locale);
      createDictSheet(workbook);
      createValidationSheet(workbook);

      populatePreviewIssueAnnotations(workbook, definitionSheet, nodeSheet, edgeSheet, issues);
      return ConsoleExcelPreviewWorkbookSupport.toBytes(workbook);
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.preview_workbook_failed");
    }
  }

  // ── definition / node / edge sheet 写入 ─────────────────────────────────

  private void writeDefinitionSheet(Sheet sheet, List<WorkflowDefinitionEntity> definitions) {
    int rowIndex = 1;
    for (WorkflowDefinitionEntity definition : definitions) {
      Row row = sheet.createRow(rowIndex++);
      writeCell(row, 0, definition.getTenantId());
      writeCell(row, 1, definition.getWorkflowCode());
      writeCell(row, 2, definition.getWorkflowName());
      writeCell(row, 3, definition.getWorkflowType());
      writeCell(row, 4, definition.getVersion());
      writeCell(row, 5, definition.getEnabled());
      writeCell(row, 6, definition.getDescription());
    }
  }

  private int writeNodeSheet(
      Sheet sheet, String tenantId, WorkflowDefinitionEntity definition, int startRowIndex) {
    WorkflowNodeQuery nodeQuery =
        WorkflowNodeQuery.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(definition.getId())
            .workflowCode(definition.getWorkflowCode())
            .build();
    List<WorkflowNodeEntity> nodes = workflowNodeMapper.selectByQuery(nodeQuery);
    int rowIndex = startRowIndex;
    for (WorkflowNodeEntity node : nodes) {
      Row row = sheet.createRow(rowIndex++);
      writeCell(row, 0, tenantId);
      writeCell(row, 1, definition.getWorkflowCode());
      writeCell(row, 2, definition.getVersion());
      writeCell(row, 3, node.getNodeCode());
      writeCell(row, 4, node.getNodeName());
      writeCell(row, 5, node.getNodeType());
      writeCell(row, 6, node.getRelatedJobCode());
      writeCell(row, 7, node.getRelatedPipelineCode());
      writeCell(row, 8, node.getWorkerGroup());
      writeCell(row, 9, node.getWindowCode());
      writeCell(row, 10, node.getNodeOrder());
      writeCell(row, 11, node.getRetryPolicy());
      writeCell(row, 12, node.getRetryMaxCount());
      writeCell(row, 13, node.getTimeoutSeconds());
      writeCell(row, 14, node.getNodeParams());
      writeCell(row, 15, node.getEnabled());
    }
    return rowIndex;
  }

  private int writeEdgeSheet(
      Sheet sheet, String tenantId, WorkflowDefinitionEntity definition, int startRowIndex) {
    WorkflowEdgeQuery edgeQuery =
        WorkflowEdgeQuery.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(definition.getId())
            .workflowCode(definition.getWorkflowCode())
            .build();
    List<WorkflowEdgeEntity> edges = workflowEdgeMapper.selectByQuery(edgeQuery);
    int rowIndex = startRowIndex;
    for (WorkflowEdgeEntity edge : edges) {
      Row row = sheet.createRow(rowIndex++);
      writeCell(row, 0, tenantId);
      writeCell(row, 1, definition.getWorkflowCode());
      writeCell(row, 2, definition.getVersion());
      writeCell(row, 3, edge.getFromNodeCode());
      writeCell(row, 4, edge.getToNodeCode());
      writeCell(row, 5, edge.getEdgeType());
      writeCell(row, 6, edge.getConditionExpr());
      writeCell(row, 7, edge.getEnabled());
    }
    return rowIndex;
  }

  private void populatePreviewDefinitionSheet(
      Sheet definitionSheet, List<WorkflowDefinitionRow> definitions) {
    int rowIndex = 1;
    for (WorkflowDefinitionRow rowData : definitions) {
      Row row = definitionSheet.createRow(rowIndex++);
      writeCell(row, 0, rowData.tenantId());
      writeCell(row, 1, rowData.workflowCode());
      writeCell(row, 2, rowData.workflowName());
      writeCell(row, 3, rowData.workflowType());
      writeCell(row, 4, rowData.version());
      writeCell(row, 5, rowData.enabled());
      writeCell(row, 6, rowData.description());
    }
  }

  private void populatePreviewNodeSheet(Sheet nodeSheet, List<WorkflowNodeRow> nodes) {
    int rowIndex = 1;
    for (WorkflowNodeRow rowData : nodes) {
      Row row = nodeSheet.createRow(rowIndex++);
      writeCell(row, 0, rowData.tenantId());
      writeCell(row, 1, rowData.workflowCode());
      writeCell(row, 2, rowData.workflowVersion());
      writeCell(row, 3, rowData.nodeCode());
      writeCell(row, 4, rowData.nodeName());
      writeCell(row, 5, rowData.nodeType());
      writeCell(row, 6, rowData.relatedJobCode());
      writeCell(row, 7, rowData.relatedPipelineCode());
      writeCell(row, 8, rowData.workerGroup());
      writeCell(row, 9, rowData.windowCode());
      writeCell(row, 10, rowData.nodeOrder());
      writeCell(row, 11, rowData.retryPolicy());
      writeCell(row, 12, rowData.retryMaxCount());
      writeCell(row, 13, rowData.timeoutSeconds());
      writeCell(row, 14, rowData.nodeParams());
      writeCell(row, 15, rowData.enabled());
    }
  }

  private void populatePreviewEdgeSheet(Sheet edgeSheet, List<WorkflowEdgeRow> edges) {
    int rowIndex = 1;
    for (WorkflowEdgeRow rowData : edges) {
      Row row = edgeSheet.createRow(rowIndex++);
      writeCell(row, 0, rowData.tenantId());
      writeCell(row, 1, rowData.workflowCode());
      writeCell(row, 2, rowData.workflowVersion());
      writeCell(row, 3, rowData.fromNodeCode());
      writeCell(row, 4, rowData.toNodeCode());
      writeCell(row, 5, rowData.edgeType());
      writeCell(row, 6, rowData.conditionExpr());
      writeCell(row, 7, rowData.enabled());
    }
  }

  private void populatePreviewIssueAnnotations(
      Workbook workbook,
      Sheet definitionSheet,
      Sheet nodeSheet,
      Sheet edgeSheet,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    List<WorkbookIssue> workbookIssues =
        issues.stream()
            .flatMap(
                issue ->
                    ConsoleExcelPreviewWorkbookSupport.expandIssues(
                        issue.sheetName(),
                        issue.rowNo(),
                        issue.messages(),
                        WorkflowExcelColumnMetadata.columnsForSheet(issue.sheetName()))
                        .stream())
            .toList();
    ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(workbook, workbookIssues);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(
        definitionSheet, DEF_COLUMNS, filterIssues(workbookIssues, DEF_SHEET), 1);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(
        nodeSheet, NODE_COLUMNS, filterIssues(workbookIssues, NODE_SHEET), 3);
    ConsoleExcelPreviewWorkbookSupport.addIssueComments(
        edgeSheet, EDGE_COLUMNS, filterIssues(workbookIssues, EDGE_SHEET), 3);
  }

  private List<WorkbookIssue> filterIssues(List<WorkbookIssue> issues, String sheetName) {
    return issues.stream().filter(issue -> Objects.equals(sheetName, issue.sheetName())).toList();
  }

  // ── README / 字典 / 校验 sheet ──────────────────────────────────────────

  private void applyValidations(
      Sheet definitionSheet, Sheet nodeSheet, Sheet edgeSheet, Locale locale) {
    addDropdownValidation(
        definitionSheet,
        3,
        WORKFLOW_TYPES.toArray(String[]::new),
        "excel.workflow.def.workflow_type.prompt_title",
        "excel.workflow.def.workflow_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        definitionSheet,
        5,
        new String[] {"TRUE", "FALSE"},
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        nodeSheet,
        5,
        NODE_TYPES.toArray(String[]::new),
        "excel.workflow.node.node_type.prompt_title",
        "excel.workflow.node.node_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        nodeSheet,
        11,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.workflow.node.retry_policy.prompt_title",
        "excel.workflow.node.retry_policy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        nodeSheet,
        15,
        new String[] {"TRUE", "FALSE"},
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        edgeSheet,
        5,
        EDGE_TYPES.toArray(String[]::new),
        "excel.workflow.edge.edge_type.prompt_title",
        "excel.workflow.edge.edge_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        edgeSheet,
        7,
        new String[] {"TRUE", "FALSE"},
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
  }

  private void createReadmeSheet(Workbook workbook, Locale locale) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] keys = {
      "excel.workflow.readme.title",
      "excel.workflow.readme.line1",
      "excel.workflow.readme.line2",
      "excel.workflow.readme.line3",
      "excel.workflow.readme.line4",
      "excel.workflow.readme.line5"
    };
    for (int i = 0; i < keys.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(messageSource.getMessage(keys[i], null, keys[i], locale));
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  private void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_DICT);
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", COL_DESCRIPTION), dictHeaderStyle);
    String[][] rows = {
      {COL_WORKFLOW_TYPE, "DAG", "dag workflow"},
      {COL_WORKFLOW_TYPE, "PIPELINE", "pipeline workflow"},
      {COL_WORKFLOW_TYPE, "MIXED", "mixed workflow"},
      {COL_NODE_TYPE, "TASK", "task node"},
      {COL_NODE_TYPE, "GATEWAY", "gateway node"},
      {COL_NODE_TYPE, "FILE_STEP", "file step node"},
      {COL_NODE_TYPE, "START", "start node"},
      {COL_NODE_TYPE, "END", "end node"},
      {COL_NODE_TYPE, "JOB", "job node"},
      {COL_RETRY_POLICY, "NONE", "no retry"},
      {COL_RETRY_POLICY, "FIXED", "fixed retry"},
      {COL_RETRY_POLICY, "EXPONENTIAL", "exponential retry"},
      {COL_EDGE_TYPE, EDGE_SUCCESS, "success edge"},
      {COL_EDGE_TYPE, "FAILURE", "failure edge"},
      {COL_EDGE_TYPE, "CONDITION", "condition edge"},
      {COL_EDGE_TYPE, "ALWAYS", "always edge"},
      {COL_ENABLED, GUIDE_TRUE, COL_ENABLED},
      {COL_ENABLED, GUIDE_FALSE, "disabled"}
    };
    for (int i = 0; i < rows.length; i++) {
      Row row = sheet.createRow(i + 1);
      row.createCell(0).setCellValue(rows[i][0]);
      row.createCell(1).setCellValue(rows[i][1]);
      row.createCell(2).setCellValue(rows[i][2]);
    }
    setGuideColumnWidths(sheet);
  }

  private void createValidationSheet(Workbook workbook) {
    ConsoleExcelStyles.createValidationSheet(workbook);
  }
}
