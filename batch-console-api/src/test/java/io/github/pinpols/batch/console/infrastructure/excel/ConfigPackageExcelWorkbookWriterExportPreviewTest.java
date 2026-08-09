package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.PackageValidationResult;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator.SheetResult;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

/**
 * Writer 导出/预览路径的 characterization 测试：锁定 buildExportWorkbook / buildPreviewWorkbook 的
 * 行为基线（sheet 顺序与数量、表头、数据单元格、错误批注与校验明细），防止拆分重构时行为漂移。
 */
class ConfigPackageExcelWorkbookWriterExportPreviewTest {

  private static final List<String> DATA_SHEET_NAMES = List.of(
      ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET,
      ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET,
      ConfigPackageExcelValidator.BATCH_WINDOW_SHEET,
      ConfigPackageExcelValidator.JOB_SHEET,
      ConfigPackageExcelValidator.CHANNEL_SHEET,
      ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
      ConfigPackageExcelValidator.PIPELINE_SHEET,
      ConfigPackageExcelValidator.STEP_SHEET,
      ConfigPackageExcelValidator.WF_DEF_SHEET,
      ConfigPackageExcelValidator.WF_NODE_SHEET,
      ConfigPackageExcelValidator.WF_EDGE_SHEET);

  private static final List<String> SUPPLEMENT_SHEET_NAMES = List.of(
      ConsoleExcelStyles.SHEET_NAME_README,
      ConfigPackageWorkbookSupplementWriter.SHEET_NAME_DEPENDENCY,
      ConfigPackageWorkbookSupplementWriter.SHEET_NAME_FOUR_WORKER,
      ConfigPackageWorkbookSupplementWriter.SHEET_NAME_BUNDLE,
      ConsoleExcelStyles.SHEET_NAME_GUIDE,
      ConsoleExcelStyles.SHEET_NAME_VALIDATION);

  @Test
  void exportWorkbookContainsAllDataAndSupplementSheetsInStableOrder() throws Exception {
    List<List<Map<String, Object>>> sheetData = exportDataRows();
    try (XSSFWorkbook wb = read(workbookWriter().buildExportWorkbook(sheetData, Map.of()))) {
      assertThat(sheetNames(wb))
          .containsExactlyElementsOf(concat(DATA_SHEET_NAMES, SUPPLEMENT_SHEET_NAMES));
      for (int i = 0; i < DATA_SHEET_NAMES.size(); i++) {
        assertThat(wb.getSheetAt(i).getSheetName()).isEqualTo(DATA_SHEET_NAMES.get(i));
      }
    }
  }

  @Test
  void exportWorkbookWritesHeadersAndEscapesFormulaCells() throws Exception {
    List<Map<String, Object>> jobRows = List.of(
        row("job_code", "JOB_IMPORT_CUSTOMER", "job_name", "导入客户", "default_params", "=1+1"));
    List<List<Map<String, Object>>> sheetData = emptyExportData();
    sheetData.set(3, jobRows);

    try (XSSFWorkbook wb = read(workbookWriter().buildExportWorkbook(sheetData, Map.of()))) {
      Sheet jobSheet = wb.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      // 表头 = schema 列
      assertThat(jobSheet.getRow(0).getCell(0).getStringCellValue())
          .isEqualTo(ConfigPackageExcelValidator.COL_TENANT_ID);
      // 数据行单元格按列写入
      assertThat(jobSheet.getRow(1).getCell(1).getStringCellValue())
          .isEqualTo("JOB_IMPORT_CUSTOMER");
      // 公式注入被转义（不产生可执行公式）
      assertThat(jobSheet.getRow(1).getCell(20).getStringCellValue())
          .isNotEqualTo("=1+1")
          .contains("1+1");
    }
  }

  @Test
  void exportWorkbookAppliesStepImplCodeDropdownFromRegistry() throws Exception {
    List<List<Map<String, Object>>> sheetData = emptyExportData();
    sheetData.set(
        7, List.of(row("job_code", "JOB_PROCESS", "version", "1", "impl_code", "sqlCompute")));

    try (XSSFWorkbook wb = read(workbookWriter()
        .buildExportWorkbook(sheetData, Map.of("PROCESS", List.of("sqlCompute"))))) {
      Sheet stepSheet = wb.getSheet(ConfigPackageExcelValidator.STEP_SHEET);
      // impl_code 列(index 6)有动态下拉
      assertThat(stepSheet.getDataValidations()).anySatisfy(dv -> {
        String formula = dv.getValidationConstraint().getFormula1();
        assertThat(formula).contains("PROCESS:sqlCompute");
      });
    }
  }

  @Test
  void previewWorkbookWritesSessionRowsAndIssueComments() throws Exception {
    Map<String, String> badJob = new LinkedHashMap<>();
    badJob.put("tenant_id", "t1");
    badJob.put("job_code", "JOB_MISSING_QUEUE");
    badJob.put("job_name", "缺队列作业");
    badJob.put("job_type", "IMPORT");
    badJob.put("schedule_type", "MANUAL");
    badJob.put("queue_code", "no-such-queue");
    PackageExcelSession session = new PackageExcelSession(
        "tenant-package.xlsx",
        "t1",
        Instant.parse("2026-08-09T00:00:00Z"),
        List.of(),
        List.of(),
        List.of(),
        List.of(badJob),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
    WorkbookIssue queueIssue = new WorkbookIssue(
        ConfigPackageExcelValidator.JOB_SHEET,
        2,
        ConfigPackageExcelValidator.COL_QUEUE_CODE,
        "queue_code references unknown resource_queue: no-such-queue");
    // 单元格批注来自 sheet 自身 issue；跨表 issue 只进入校验明细 sheet（锁定现状，避免重构漂移）。
    List<WorkbookIssue> crossRefIssues = List.of(new WorkbookIssue(
        ConfigPackageExcelValidator.PIPELINE_SHEET,
        3,
        ConfigPackageExcelValidator.COL_JOB_CODE,
        "job_code references unknown job definition: JOB_MISSING"));
    PackageValidationResult result = new PackageValidationResult(
        emptySheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET),
        emptySheet(ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET),
        emptySheet(ConfigPackageExcelValidator.BATCH_WINDOW_SHEET),
        new SheetResult(ConfigPackageExcelValidator.JOB_SHEET, 1, List.of(), List.of(queueIssue)),
        emptySheet(ConfigPackageExcelValidator.CHANNEL_SHEET),
        emptySheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET),
        emptySheet(ConfigPackageExcelValidator.PIPELINE_SHEET),
        emptySheet(ConfigPackageExcelValidator.STEP_SHEET),
        emptySheet(ConfigPackageExcelValidator.WF_DEF_SHEET),
        emptySheet(ConfigPackageExcelValidator.WF_NODE_SHEET),
        emptySheet(ConfigPackageExcelValidator.WF_EDGE_SHEET),
        crossRefIssues);

    try (XSSFWorkbook wb = read(workbookWriter().buildPreviewWorkbook(session, result, Map.of()))) {
      Sheet jobSheet = wb.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      assertThat(jobSheet.getRow(1).getCell(1).getStringCellValue()).isEqualTo("JOB_MISSING_QUEUE");
      Comment comment = jobSheet.getRow(1).getCell(5).getCellComment();
      assertThat(comment).isNotNull();
      assertThat(comment.getString().getString()).contains("no-such-queue");

      Sheet validationSheet = wb.getSheet(ConsoleExcelStyles.SHEET_NAME_VALIDATION);
      assertThat(validationSheet).isNotNull();
      assertThat(validationSheet.getRow(1).getCell(0).getStringCellValue())
          .isEqualTo(ConfigPackageExcelValidator.JOB_SHEET);
      assertThat(validationSheet.getRow(1).getCell(3).getStringCellValue())
          .contains("no-such-queue");
      assertThat(validationSheet.getRow(2).getCell(0).getStringCellValue())
          .isEqualTo(ConfigPackageExcelValidator.PIPELINE_SHEET);
      assertThat(validationSheet.getRow(2).getCell(3).getStringCellValue()).contains("JOB_MISSING");
    }
  }

  private static ConfigPackageExcelWorkbookWriter workbookWriter() {
    StaticMessageSource ms = new StaticMessageSource();
    ms.setUseCodeAsDefaultMessage(true);
    return new ConfigPackageExcelWorkbookWriter(ms);
  }

  private static XSSFWorkbook read(byte[] bytes) throws Exception {
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private static List<String> sheetNames(XSSFWorkbook wb) {
    ArrayList<String> names = new ArrayList<>();
    for (int i = 0; i < wb.getNumberOfSheets(); i++) {
      names.add(wb.getSheetName(i));
    }
    return names;
  }

  private static List<String> concat(List<String> a, List<String> b) {
    ArrayList<String> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private static List<List<Map<String, Object>>> emptyExportData() {
    List<List<Map<String, Object>>> data = new ArrayList<>();
    for (int i = 0; i < DATA_SHEET_NAMES.size(); i++) {
      data.add(List.of());
    }
    return data;
  }

  private static List<List<Map<String, Object>>> exportDataRows() {
    List<List<Map<String, Object>>> data = emptyExportData();
    data.set(3, List.of(row("job_code", "JOB_IMPORT_CUSTOMER", "job_name", "导入客户")));
    return data;
  }

  private static Map<String, Object> row(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static SheetResult emptySheet(String sheetName) {
    return new SheetResult(sheetName, 0, List.of(), List.of());
  }
}
