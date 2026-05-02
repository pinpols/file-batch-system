package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeTemplateHeaders;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.PipelineType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.web.response.workflow.ConsolePipelineDefinitionExcelRowIssueResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: Pipeline 定义 Excel 模板/导出/预览的 workbook writer。
 *
 * <p>覆盖原 service ~270 行写盘逻辑(2 个 sheet 写入 + 校验下拉 + README/字典/校验 sheet),自带列名/列说明/枚举集常量。 与 {@link
 * WorkflowExcelWorkbookWriter} 同款 pattern;每个业务 Excel god class 都可挂一个 writer。
 */
@Component
public class PipelineExcelWorkbookWriter {

  // ── sheet 与列常量 ─────────────────────────────────────────────────────────
  static final String PIPELINE_SHEET_NAME = "pipeline_definition";
  static final String STEP_SHEET_NAME = "pipeline_step_definition";

  private static final String COL_TENANT_ID = "tenant_id";
  private static final String COL_JOB_CODE = "job_code";
  private static final String COL_VERSION = "version";
  private static final String COL_PIPELINE_NAME = "pipeline_name";
  private static final String COL_PIPELINE_TYPE = "pipeline_type";
  private static final String COL_BIZ_TYPE = "biz_type";
  private static final String COL_WORKER_GROUP = "worker_group";
  private static final String COL_ENABLED = "enabled";
  private static final String COL_DESCRIPTION = "description";
  private static final String COL_STEP_CODE = "step_code";
  private static final String COL_STEP_NAME = "step_name";
  private static final String COL_STAGE_CODE = "stage_code";
  private static final String COL_STEP_ORDER = "step_order";
  private static final String COL_IMPL_CODE = "impl_code";
  private static final String COL_STEP_PARAMS = "step_params";
  private static final String COL_TIMEOUT_SECONDS = "timeout_seconds";
  private static final String COL_RETRY_POLICY = "retry_policy";
  private static final String COL_RETRY_MAX_COUNT = "retry_max_count";
  private static final String STAGE_PARSE = "PARSE";
  private static final String STAGE_DISPATCH = "DISPATCH";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_STR = "字符串";
  private static final String GUIDE_INT = "整数";

  static final List<String> PIPELINE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_JOB_CODE,
          COL_PIPELINE_NAME,
          COL_PIPELINE_TYPE,
          COL_BIZ_TYPE,
          COL_WORKER_GROUP,
          COL_VERSION,
          COL_ENABLED,
          COL_DESCRIPTION);

  static final List<String> STEP_COLUMNS =
      List.of(
          COL_JOB_CODE,
          COL_VERSION,
          COL_STEP_CODE,
          COL_STEP_NAME,
          COL_STAGE_CODE,
          COL_STEP_ORDER,
          COL_IMPL_CODE,
          COL_STEP_PARAMS,
          COL_TIMEOUT_SECONDS,
          COL_RETRY_POLICY,
          COL_RETRY_MAX_COUNT,
          COL_ENABLED);

  static final Set<String> PIPELINE_REQUIRED_HEADERS = Set.copyOf(PIPELINE_COLUMNS);
  static final Set<String> STEP_REQUIRED_HEADERS = Set.copyOf(STEP_COLUMNS);

  private static final Set<String> PIPELINE_TYPES = DictEnum.codes(PipelineType.class);
  private static final Set<String> STAGE_CODES =
      Set.of(
          "RECEIVE",
          "PREPROCESS",
          STAGE_PARSE,
          "VALIDATE",
          "LOAD",
          "GENERATE",
          "TRANSFER",
          STAGE_DISPATCH,
          "ACK");
  private static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);

  private static final Map<String, ConsoleExcelStyles.ColumnGuide> PIPELINE_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID, optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              COL_JOB_CODE,
              requiredColumn("作业唯一编码，与 version 组成联合键。", GUIDE_STR, "JOB_IMPORT_SETTLEMENT")),
          Map.entry(COL_PIPELINE_NAME, requiredColumn("流水线名称。", GUIDE_STR, "清算导入流水线")),
          Map.entry(
              COL_PIPELINE_TYPE,
              requiredColumn("流水线类型。", "枚举", "IMPORT", "IMPORT", "EXPORT", STAGE_DISPATCH)),
          Map.entry(COL_BIZ_TYPE, optionalColumn("业务类型标识。", GUIDE_STR, "SETTLEMENT")),
          Map.entry(COL_WORKER_GROUP, optionalColumn("Worker 分组名称。", GUIDE_STR, "default")),
          Map.entry(COL_VERSION, requiredColumn("版本号，与 job_code 组成联合键。", GUIDE_INT, "1")),
          Map.entry(
              COL_ENABLED, optionalColumn("是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(COL_DESCRIPTION, optionalColumn("流水线描述。", GUIDE_STR, "用于清算文件导入")));

  private static final Map<String, ConsoleExcelStyles.ColumnGuide> STEP_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_JOB_CODE,
              requiredColumn("关联的 pipeline job_code。", GUIDE_STR, "JOB_IMPORT_SETTLEMENT")),
          Map.entry(COL_VERSION, requiredColumn("关联的 pipeline version。", GUIDE_INT, "1")),
          Map.entry(COL_STEP_CODE, requiredColumn("步骤唯一编码。", GUIDE_STR, "STEP_PARSE_CSV")),
          Map.entry(COL_STEP_NAME, requiredColumn("步骤名称。", GUIDE_STR, "解析CSV文件")),
          Map.entry(
              COL_STAGE_CODE,
              requiredColumn(
                  "阶段编码。",
                  "枚举",
                  STAGE_PARSE,
                  "RECEIVE",
                  "PREPROCESS",
                  STAGE_PARSE,
                  "VALIDATE",
                  "LOAD",
                  "GENERATE",
                  "TRANSFER",
                  STAGE_DISPATCH,
                  "ACK")),
          Map.entry(COL_STEP_ORDER, requiredColumn("步骤顺序，整数。", GUIDE_INT, "1")),
          Map.entry(COL_IMPL_CODE, requiredColumn("步骤实现编码。", GUIDE_STR, "csvParserStep")),
          Map.entry(
              COL_STEP_PARAMS, optionalColumn("步骤参数，须为合法 JSON。", "JSON", "{\"delimiter\":\",\"}")),
          Map.entry(COL_TIMEOUT_SECONDS, requiredColumn("超时时间（秒），必须 >= 0。", GUIDE_INT, "60")),
          Map.entry(
              COL_RETRY_POLICY,
              requiredColumn("重试策略。", "枚举", "NONE", "NONE", "FIXED", "EXPONENTIAL")),
          Map.entry(COL_RETRY_MAX_COUNT, requiredColumn("最大重试次数，必须 >= 0。", GUIDE_INT, "0")),
          Map.entry(
              COL_ENABLED, optionalColumn("是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));

  public byte[] writeMaintenanceWorkbook(
      List<Map<String, Object>> pipelines, List<Map<String, Object>> steps) {
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writePipelineSheet(workbook, pipelines);
      writeStepSheet(workbook, steps);
      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);
      workbook.write(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.generate_failed");
    }
  }

  public byte[] writePreviewWorkbook(
      List<Map<String, String>> pipelineRawRows,
      List<Map<String, String>> stepRawRows,
      List<ConsolePipelineDefinitionExcelRowIssueResponse> allIssues) {
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      Sheet pipelineSheet = workbook.createSheet(PIPELINE_SHEET_NAME);
      pipelineSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(pipelineSheet, PIPELINE_COLUMNS, PIPELINE_COLUMN_GUIDES, workbook);
      int rowIndex = 1;
      for (Map<String, String> rawRow : pipelineRawRows) {
        Row dataRow = pipelineSheet.createRow(rowIndex++);
        for (int i = 0; i < PIPELINE_COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          String value = rawRow.get(PIPELINE_COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : value);
        }
      }
      applyPipelineValidations(pipelineSheet);
      setWidths(pipelineSheet, PIPELINE_COLUMNS);

      Sheet stepSheet = workbook.createSheet(STEP_SHEET_NAME);
      stepSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(stepSheet, STEP_COLUMNS, STEP_COLUMN_GUIDES, workbook);
      rowIndex = 1;
      for (Map<String, String> rawRow : stepRawRows) {
        Row dataRow = stepSheet.createRow(rowIndex++);
        for (int i = 0; i < STEP_COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          String value = rawRow.get(STEP_COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : value);
        }
      }
      applyStepValidations(stepSheet);
      setWidths(stepSheet, STEP_COLUMNS);

      createReadmeSheet(workbook);
      createDictSheet(workbook);
      createValidationSheet(workbook);

      List<WorkbookIssue> workbookIssues =
          allIssues.stream()
              .flatMap(
                  issue -> {
                    String targetSheet = issue.sheetName();
                    List<String> columns =
                        PIPELINE_SHEET_NAME.equals(targetSheet) ? PIPELINE_COLUMNS : STEP_COLUMNS;
                    return ConsoleExcelPreviewWorkbookSupport.expandIssues(
                        targetSheet, issue.rowNo(), issue.messages(), columns)
                        .stream();
                  })
              .toList();
      ConsoleExcelPreviewWorkbookSupport.populateValidationSheet(workbook, workbookIssues);

      List<WorkbookIssue> pipelineIssues =
          workbookIssues.stream().filter(i -> PIPELINE_SHEET_NAME.equals(i.sheetName())).toList();
      List<WorkbookIssue> stepIssues =
          workbookIssues.stream().filter(i -> STEP_SHEET_NAME.equals(i.sheetName())).toList();
      ConsoleExcelPreviewWorkbookSupport.addIssueComments(
          pipelineSheet, PIPELINE_COLUMNS, pipelineIssues, 1);
      ConsoleExcelPreviewWorkbookSupport.addIssueComments(stepSheet, STEP_COLUMNS, stepIssues, 1);

      return ConsoleExcelPreviewWorkbookSupport.toBytes(workbook);
    } catch (IOException exception) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.excel.preview_workbook_failed");
    }
  }

  private void writePipelineSheet(SXSSFWorkbook workbook, List<Map<String, Object>> rows) {
    Sheet sheet = workbook.createSheet(PIPELINE_SHEET_NAME);
    sheet.createFreezePane(0, 1, 0, 1);
    writeTemplateHeaders(sheet, PIPELINE_COLUMNS, PIPELINE_COLUMN_GUIDES, workbook);
    int rowIndex = 1;
    for (Map<String, Object> row : rows) {
      Row dataRow = sheet.createRow(rowIndex++);
      for (int i = 0; i < PIPELINE_COLUMNS.size(); i++) {
        String header = PIPELINE_COLUMNS.get(i);
        Cell cell = dataRow.createCell(i);
        Object value = row.get(header);
        cell.setCellValue(
            value == null ? "" : ConsoleExcelStyles.escapeFormula(String.valueOf(value)));
      }
    }
    applyPipelineValidations(sheet);
    setWidths(sheet, PIPELINE_COLUMNS);
  }

  private void writeStepSheet(SXSSFWorkbook workbook, List<Map<String, Object>> rows) {
    Sheet sheet = workbook.createSheet(STEP_SHEET_NAME);
    sheet.createFreezePane(0, 1, 0, 1);
    writeTemplateHeaders(sheet, STEP_COLUMNS, STEP_COLUMN_GUIDES, workbook);
    int rowIndex = 1;
    for (Map<String, Object> row : rows) {
      Row dataRow = sheet.createRow(rowIndex++);
      for (int i = 0; i < STEP_COLUMNS.size(); i++) {
        String header = STEP_COLUMNS.get(i);
        Cell cell = dataRow.createCell(i);
        Object value = row.get(header);
        cell.setCellValue(
            value == null ? "" : ConsoleExcelStyles.escapeFormula(String.valueOf(value)));
      }
    }
    applyStepValidations(sheet);
    setWidths(sheet, STEP_COLUMNS);
  }

  private void applyPipelineValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 3, PIPELINE_TYPES.toArray(String[]::new), "pipeline_type 填写提示", "请从下拉列表中选择流水线类型。");
    addBooleanValidation(sheet, new int[] {7}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  private void applyStepValidations(Sheet sheet) {
    addDropdownValidation(
        sheet, 4, STAGE_CODES.toArray(String[]::new), "stage_code 填写提示", "请从下拉列表中选择阶段编码。");
    addDropdownValidation(
        sheet, 9, RETRY_POLICIES.toArray(String[]::new), "retry_policy 填写提示", "请从下拉列表中选择重试策略。");
    addBooleanValidation(sheet, new int[] {11}, "enabled 填写提示", "请填写 TRUE 或 FALSE。");
  }

  private void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "Pipeline 定义维护模板",
      "1. 'pipeline_definition' sheet 维护 pipeline 定义；橙色表头表示必填字段。",
      "2. 'pipeline_step_definition' sheet 维护 step 定义,按 job_code + version 关联。",
      "3. pipeline_type / stage_code / retry_policy / enabled 已内置下拉值校验。",
      "4. step_params 必须保持合法 JSON(或留空)。",
      "5. 导入流程：上传 → 预览 → 应用。",
      "6. 鼠标悬停表头单元格可查看字段规则与示例。"
    };
    for (int i = 0; i < lines.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(lines[i]);
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
      {COL_PIPELINE_TYPE, "IMPORT", "import pipeline"},
      {COL_PIPELINE_TYPE, "EXPORT", "export pipeline"},
      {COL_PIPELINE_TYPE, STAGE_DISPATCH, "dispatch pipeline"},
      {COL_STAGE_CODE, "RECEIVE", "receive stage"},
      {COL_STAGE_CODE, "PREPROCESS", "preprocess stage"},
      {COL_STAGE_CODE, STAGE_PARSE, "parse stage"},
      {COL_STAGE_CODE, "VALIDATE", "validate stage"},
      {COL_STAGE_CODE, "LOAD", "load stage"},
      {COL_STAGE_CODE, "GENERATE", "generate stage"},
      {COL_STAGE_CODE, "TRANSFER", "transfer stage"},
      {COL_STAGE_CODE, STAGE_DISPATCH, "dispatch stage"},
      {COL_STAGE_CODE, "ACK", "acknowledge stage"},
      {COL_RETRY_POLICY, "NONE", "no retry"},
      {COL_RETRY_POLICY, "FIXED", "fixed interval retry"},
      {COL_RETRY_POLICY, "EXPONENTIAL", "exponential backoff retry"},
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
