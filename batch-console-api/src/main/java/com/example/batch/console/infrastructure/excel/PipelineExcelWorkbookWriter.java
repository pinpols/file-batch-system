package com.example.batch.console.infrastructure.excel;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: Pipeline 定义 Excel 模板/导出/预览的 workbook writer。
 *
 * <p>覆盖原 service ~270 行写盘逻辑(2 个 sheet 写入 + 校验下拉 + README/字典/校验 sheet),自带列名/列说明/枚举集常量。 与 {@link
 * WorkflowExcelWorkbookWriter} 同款 pattern;每个业务 Excel god class 都可挂一个 writer。
 */
@Component
@RequiredArgsConstructor
public class PipelineExcelWorkbookWriter {

  private final MessageSource messageSource;

  private static final String FMT_STRING_KEY = "excel.guide.format.string";

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
              COL_TENANT_ID,
              optionalColumn("excel.pipeline.def.tenant_id.desc", FMT_STRING_KEY, "tenant-a")),
          Map.entry(
              COL_JOB_CODE,
              requiredColumn(
                  "excel.pipeline.def.job_code.desc", FMT_STRING_KEY, "JOB_IMPORT_SETTLEMENT")),
          Map.entry(
              COL_PIPELINE_NAME,
              requiredColumn("excel.pipeline.def.pipeline_name.desc", FMT_STRING_KEY, "清算导入流水线")),
          Map.entry(
              COL_PIPELINE_TYPE,
              requiredColumn(
                  "excel.pipeline.def.pipeline_type.desc",
                  "excel.guide.format.enum",
                  "IMPORT",
                  "IMPORT",
                  "EXPORT",
                  STAGE_DISPATCH)),
          Map.entry(
              COL_BIZ_TYPE,
              optionalColumn("excel.pipeline.def.biz_type.desc", FMT_STRING_KEY, "SETTLEMENT")),
          Map.entry(
              COL_WORKER_GROUP,
              optionalColumn("excel.pipeline.def.worker_group.desc", FMT_STRING_KEY, "default")),
          Map.entry(
              COL_VERSION,
              requiredColumn("excel.pipeline.def.version.desc", "excel.guide.format.integer", "1")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.pipeline.def.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              COL_DESCRIPTION,
              optionalColumn("excel.pipeline.def.description.desc", FMT_STRING_KEY, "用于清算文件导入")));

  private static final Map<String, ConsoleExcelStyles.ColumnGuide> STEP_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_JOB_CODE,
              requiredColumn(
                  "excel.pipeline.step.job_code.desc", FMT_STRING_KEY, "JOB_IMPORT_SETTLEMENT")),
          Map.entry(
              COL_VERSION,
              requiredColumn(
                  "excel.pipeline.step.version.desc", "excel.guide.format.integer", "1")),
          Map.entry(
              COL_STEP_CODE,
              requiredColumn(
                  "excel.pipeline.step.step_code.desc", FMT_STRING_KEY, "STEP_PARSE_CSV")),
          Map.entry(
              COL_STEP_NAME,
              requiredColumn("excel.pipeline.step.step_name.desc", FMT_STRING_KEY, "解析CSV文件")),
          Map.entry(
              COL_STAGE_CODE,
              requiredColumn(
                  "excel.pipeline.step.stage_code.desc",
                  "excel.guide.format.enum",
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
          Map.entry(
              COL_STEP_ORDER,
              requiredColumn(
                  "excel.pipeline.step.step_order.desc", "excel.guide.format.integer", "1")),
          Map.entry(
              COL_IMPL_CODE,
              requiredColumn(
                  "excel.pipeline.step.impl_code.desc", FMT_STRING_KEY, "csvParserStep")),
          Map.entry(
              COL_STEP_PARAMS,
              optionalColumn(
                  "excel.pipeline.step.step_params.desc",
                  "excel.guide.format.json",
                  "{\"delimiter\":\",\"}")),
          Map.entry(
              COL_TIMEOUT_SECONDS,
              requiredColumn(
                  "excel.pipeline.step.timeout_seconds.desc", "excel.guide.format.integer", "60")),
          Map.entry(
              COL_RETRY_POLICY,
              requiredColumn(
                  "excel.pipeline.step.retry_policy.desc",
                  "excel.guide.format.enum",
                  "NONE",
                  "NONE",
                  "FIXED",
                  "EXPONENTIAL")),
          Map.entry(
              COL_RETRY_MAX_COUNT,
              requiredColumn(
                  "excel.pipeline.step.retry_max_count.desc", "excel.guide.format.integer", "0")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.pipeline.step.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)));

  public byte[] writeMaintenanceWorkbook(
      List<Map<String, Object>> pipelines, List<Map<String, Object>> steps) {
    Locale locale = LocaleContextHolder.getLocale();
    try (SXSSFWorkbook workbook = new SXSSFWorkbook(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writePipelineSheet(workbook, pipelines, locale);
      writeStepSheet(workbook, steps, locale);
      createReadmeSheet(workbook, locale);
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
    Locale locale = LocaleContextHolder.getLocale();
    try (Workbook workbook = ConsoleExcelPreviewWorkbookSupport.createWorkbook()) {
      Sheet pipelineSheet = workbook.createSheet(PIPELINE_SHEET_NAME);
      pipelineSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(
          pipelineSheet, PIPELINE_COLUMNS, PIPELINE_COLUMN_GUIDES, workbook, messageSource, locale);
      int rowIndex = 1;
      for (Map<String, String> rawRow : pipelineRawRows) {
        Row dataRow = pipelineSheet.createRow(rowIndex++);
        for (int i = 0; i < PIPELINE_COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          String value = rawRow.get(PIPELINE_COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : value);
        }
      }
      applyPipelineValidations(pipelineSheet, locale);
      setWidths(pipelineSheet, PIPELINE_COLUMNS);

      Sheet stepSheet = workbook.createSheet(STEP_SHEET_NAME);
      stepSheet.createFreezePane(0, 1, 0, 1);
      writeTemplateHeaders(
          stepSheet, STEP_COLUMNS, STEP_COLUMN_GUIDES, workbook, messageSource, locale);
      rowIndex = 1;
      for (Map<String, String> rawRow : stepRawRows) {
        Row dataRow = stepSheet.createRow(rowIndex++);
        for (int i = 0; i < STEP_COLUMNS.size(); i++) {
          Cell cell = dataRow.createCell(i);
          String value = rawRow.get(STEP_COLUMNS.get(i));
          cell.setCellValue(value == null ? "" : value);
        }
      }
      applyStepValidations(stepSheet, locale);
      setWidths(stepSheet, STEP_COLUMNS);

      createReadmeSheet(workbook, locale);
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

  private void writePipelineSheet(
      SXSSFWorkbook workbook, List<Map<String, Object>> rows, Locale locale) {
    Sheet sheet = workbook.createSheet(PIPELINE_SHEET_NAME);
    sheet.createFreezePane(0, 1, 0, 1);
    writeTemplateHeaders(
        sheet, PIPELINE_COLUMNS, PIPELINE_COLUMN_GUIDES, workbook, messageSource, locale);
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
    applyPipelineValidations(sheet, locale);
    setWidths(sheet, PIPELINE_COLUMNS);
  }

  private void writeStepSheet(
      SXSSFWorkbook workbook, List<Map<String, Object>> rows, Locale locale) {
    Sheet sheet = workbook.createSheet(STEP_SHEET_NAME);
    sheet.createFreezePane(0, 1, 0, 1);
    writeTemplateHeaders(sheet, STEP_COLUMNS, STEP_COLUMN_GUIDES, workbook, messageSource, locale);
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
    applyStepValidations(sheet, locale);
    setWidths(sheet, STEP_COLUMNS);
  }

  private void applyPipelineValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        3,
        PIPELINE_TYPES.toArray(String[]::new),
        "excel.pipeline.def.pipeline_type.prompt_title",
        "excel.pipeline.def.pipeline_type.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        new String[] {"TRUE", "FALSE"},
        "excel.common.enabled.prompt_title",
        "excel.common.enabled.prompt_box",
        messageSource,
        locale);
  }

  private void applyStepValidations(Sheet sheet, Locale locale) {
    addDropdownValidation(
        sheet,
        4,
        STAGE_CODES.toArray(String[]::new),
        "excel.pipeline.step.stage_code.prompt_title",
        "excel.pipeline.step.stage_code.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        9,
        RETRY_POLICIES.toArray(String[]::new),
        "excel.pipeline.step.retry_policy.prompt_title",
        "excel.pipeline.step.retry_policy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        11,
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
      "excel.pipeline.readme.title",
      "excel.pipeline.readme.line1",
      "excel.pipeline.readme.line2",
      "excel.pipeline.readme.line3",
      "excel.pipeline.readme.line4",
      "excel.pipeline.readme.line5",
      "excel.pipeline.readme.line6"
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
