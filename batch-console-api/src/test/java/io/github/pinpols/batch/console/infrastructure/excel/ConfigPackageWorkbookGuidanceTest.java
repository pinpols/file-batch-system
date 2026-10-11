package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.BatchWindowEndStrategy;
import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileChannelAuthType;
import io.github.pinpols.batch.common.enums.FileChannelType;
import io.github.pinpols.batch.common.enums.FileReceiptPolicy;
import io.github.pinpols.batch.common.enums.HolidayRollRule;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.OutOfWindowAction;
import io.github.pinpols.batch.common.enums.PipelineType;
import io.github.pinpols.batch.common.enums.ScheduleType;
import io.github.pinpols.batch.common.enums.WorkflowEdgeType;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowType;
import io.github.pinpols.batch.common.exception.BizException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;

/**
 * 配置引导防漂移单测:锁定 {@link ConfigPackageExcelWorkbookWriter} 生成的模板里 ① 新增的「五类Worker示例」「依赖说明」只读 sheet 存在且
 * 表头/行数与设计一致; ② 「字段说明」sheet 新增「填写示例」列且最难字段给出真实非空片段(import vs export 两套结构都覆盖); ③ 五类Worker示例的
 * worker_type / pipeline_type 与后端 enum 集合一致(enum 新增成员而示例未同步即挂)。
 */
@DisplayName("配置包模板引导内容:示例 sheet、填写顺序与字段说明的防漂移校验")
class ConfigPackageWorkbookGuidanceTest {

  private XSSFWorkbook buildTemplate() throws Exception {
    StaticMessageSource ms = new StaticMessageSource();
    ms.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer = new ConfigPackageExcelWorkbookWriter(ms);
    byte[] bytes = writer.buildTemplateWorkbook();
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private XSSFWorkbook buildSampleTemplate(String scenario) throws Exception {
    StaticMessageSource ms = new StaticMessageSource();
    ms.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer =
        new ConfigPackageExcelWorkbookWriter(ms, List.of(scenario));
    byte[] bytes =
        writer.buildExportWorkbook(ConfigPackageSampleDataFactory.sampleSheets(scenario));
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private XSSFWorkbook buildSampleTemplate(List<String> scenarios) throws Exception {
    StaticMessageSource ms = new StaticMessageSource();
    ms.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer = new ConfigPackageExcelWorkbookWriter(ms, scenarios);
    byte[] bytes =
        writer.buildExportWorkbook(ConfigPackageSampleDataFactory.sampleSheets(scenarios));
    return new XSSFWorkbook(new ByteArrayInputStream(bytes));
  }

  private XSSFWorkbook buildFocusedTemplate(List<String> scenarios) throws Exception {
    StaticMessageSource ms = new StaticMessageSource();
    ms.setUseCodeAsDefaultMessage(true);
    ConfigPackageExcelWorkbookWriter writer = new ConfigPackageExcelWorkbookWriter(ms, scenarios);
    return new XSSFWorkbook(new ByteArrayInputStream(writer.buildTemplateWorkbook()));
  }

  @Test
  @DisplayName("场景模板只包含相关业务 Sheet 和精简列，不暴露平台队列配置")
  void shouldContainScenarioRows_whenBuildingSampleTemplate() throws Exception {
    try (XSSFWorkbook importWb = buildSampleTemplate("IMPORT")) {
      assertThat(importWb.getSheet(ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET))
          .as("平台运行监控阈值不进入租户场景模板")
          .isNull();
      assertThat(importWb.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET))
          .isNull();
      assertThat(
              cellByHeader(importWb.getSheet(ConfigPackageExcelValidator.JOB_SHEET), "job_code", 1))
          .isEqualTo("JOB_IMPORT_CUSTOMER");
      assertThat(cellByHeader(
              importWb.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET),
              "template_code",
              1))
          .isEqualTo("TPL_IMPORT_CUSTOMER");
      assertThat(importWb
              .getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET)
              .getRow(0)
              .getLastCellNum())
          .isLessThan((short) 41);
    }
    try (XSSFWorkbook processWb = buildSampleTemplate("PROCESS")) {
      assertThat(processWb.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET))
          .isNull();
      assertThat(processWb.getSheet(ConfigPackageExcelValidator.PIPELINE_SHEET)).isNotNull();
      assertThat(processWb.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET))
          .isNull();
    }
    try (XSSFWorkbook atomicWb = buildSampleTemplate("ATOMIC")) {
      Sheet jobSheet = atomicWb.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      assertThat(cellByHeader(jobSheet, "job_code", 1)).isEqualTo("JOB_ATOMIC_SQL_CHECK");
      assertThat(cellByHeader(jobSheet, "job_type", 1)).isEqualTo("ATOMIC");
      assertThat(atomicWb.getSheet(ConfigPackageExcelValidator.PIPELINE_SHEET)).isNull();
      assertThat(atomicWb.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET))
          .isNull();
    }
  }

  @Test
  @DisplayName("完整模板路径保持原有工作簿结构和完整字段")
  void shouldPreserveLegacyFullWorkbook_whenBuildingTemplate() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      assertThat(wb.getNumberOfSheets()).isEqualTo(19);
      assertThat(wb.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET)).isNotNull();
      assertThat(wb.getSheet(ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET))
          .isNotNull();
      assertThat(wb.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET)
              .getRow(0)
              .getLastCellNum())
          .isEqualTo((short) 41);
    }
  }

  @Test
  @DisplayName("空白场景模板仅保留所选业务 Sheet，ALL 不包含平台资源表")
  void shouldWriteFocusedBlankTemplateWithoutPlatformResourceSheets() throws Exception {
    StaticMessageSource ms = messageSource();
    ConfigPackageExcelWorkbookWriter writer =
        new ConfigPackageExcelWorkbookWriter(ms, List.of("IMPORT"));
    byte[] bytes;
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      writer.writeFocusedTemplateWorkbook(out, Map.of());
      bytes = out.toByteArray();
    }
    try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET)).isNotNull();
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET))
          .isNotNull();
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET))
          .isNull();
      assertThat(workbook.getSheet(ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET))
          .isNull();
      assertThat(workbook.getSheet(ConfigPackageWorkbookSupplementWriter.SHEET_NAME_FIVE_WORKER))
          .isNull();
      assertThat(workbook.getSheet(ConfigPackageWorkbookSupplementWriter.SHEET_NAME_BUNDLE))
          .isNull();
    }
  }

  @Test
  @DisplayName("精简场景模板的下拉验证按字段名定位且选项与后端枚举一致")
  void shouldAlignDropdownsWithProjectedColumns() throws Exception {
    try (XSSFWorkbook workbook = buildFocusedTemplate(List.of("ALL"))) {
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET),
          "job_type",
          DictEnum.codeList(JobType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET),
          "schedule_type",
          DictEnum.codeList(ScheduleType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET),
          "channel_type",
          DictEnum.codeList(FileChannelType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET),
          "auth_type",
          DictEnum.codeList(FileChannelAuthType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET),
          "receipt_policy",
          DictEnum.codeList(FileReceiptPolicy.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.PIPELINE_SHEET),
          "pipeline_type",
          DictEnum.codeList(PipelineType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.WF_DEF_SHEET),
          "workflow_type",
          DictEnum.codeList(WorkflowType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.WF_NODE_SHEET),
          "node_type",
          DictEnum.codeList(WorkflowNodeType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.WF_EDGE_SHEET),
          "edge_type",
          DictEnum.codeList(WorkflowEdgeType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET),
          "holiday_roll_rule",
          DictEnum.codeList(HolidayRollRule.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET),
          "catch_up_policy",
          DictEnum.codeList(CatchUpPolicyType.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.BATCH_WINDOW_SHEET),
          "end_strategy",
          DictEnum.codeList(BatchWindowEndStrategy.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.BATCH_WINDOW_SHEET),
          "out_of_window_action",
          DictEnum.codeList(OutOfWindowAction.class));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET),
          "completion_deadline_severity",
          List.of("WARN", "ERROR", "CRITICAL"));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.STEP_SHEET),
          "stage_code",
          List.of(ConfigPackageSheetSpecs.STAGE_CODE_DROPDOWN));
      assertDropdownValues(
          workbook.getSheet(ConfigPackageExcelValidator.JOB_SHEET),
          "enabled",
          List.of("TRUE", "FALSE"));
    }
  }

  @Test
  @DisplayName("调度场景提供业务日历、时间窗口和业务 SLA，但不暴露平台运行阈值")
  void shouldProjectBusinessSchedulingFieldsWithoutPlatformThresholds() {
    List<ConfigPackageSheetSpecs.SheetDef> sheets =
        new ConfigPackageSheetSpecs(messageSource()).project(List.of("SCHEDULE"));
    assertThat(sheets)
        .extracting(ConfigPackageSheetSpecs.SheetDef::name)
        .containsExactlyInAnyOrder(
            ConfigPackageExcelValidator.JOB_SHEET,
            ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET,
            ConfigPackageExcelValidator.BATCH_WINDOW_SHEET,
            ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET);
    ConfigPackageSheetSpecs.SheetDef policy = sheets.stream()
        .filter(
            sheet -> ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET.equals(sheet.name()))
        .findFirst()
        .orElseThrow();
    assertThat(policy.columns())
        .contains("completion_deadline_local_time", "completion_deadline_day_offset")
        .doesNotContain("soft_runtime_seconds", "start_grace_seconds");
  }

  @Test
  @DisplayName("IMPORT 与 EXPORT 模板仅展示场景字段和必要配置列")
  void shouldExposeOnlyScenarioFieldsInImportAndExportTemplates() {
    ConfigPackageSheetSpecs specs = new ConfigPackageSheetSpecs(messageSource());

    ConfigPackageSheetSpecs.SheetDef importJob =
        sheet(specs, "IMPORT", ConfigPackageExcelValidator.JOB_SHEET);
    assertThat(importJob.columns())
        .containsExactly(
            "job_code",
            "job_name",
            "job_type",
            "biz_type",
            "schedule_type",
            "schedule_expr",
            "depends_on_job_code",
            "calendar_code",
            "window_code",
            "default_params",
            "enabled",
            "description");
    assertThat(importJob.columns())
        .doesNotContain(
            "queue_code",
            "worker_group",
            "retry_policy",
            "retry_max_count",
            "timeout_seconds",
            "shard_strategy",
            "execution_mode",
            "watermark_field",
            "execution_handler",
            "param_schema");

    assertThat(sheet(specs, "IMPORT", ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET)
            .columns())
        .containsExactly(
            "template_code",
            "template_name",
            "template_type",
            "biz_type",
            "file_format_type",
            "charset",
            "with_bom",
            "line_separator",
            "delimiter",
            "quote_char",
            "escape_char",
            "record_length",
            "header_rows",
            "footer_rows",
            "field_mappings",
            "validation_rule_set",
            "enabled",
            "version",
            "description");

    assertThat(sheet(specs, "EXPORT", ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET)
            .columns())
        .containsExactly(
            "template_code",
            "template_name",
            "template_type",
            "biz_type",
            "file_format_type",
            "charset",
            "with_bom",
            "line_separator",
            "delimiter",
            "quote_char",
            "escape_char",
            "record_length",
            "header_rows",
            "footer_rows",
            "naming_rule",
            "field_mappings",
            "default_query_code",
            "default_query_sql",
            "query_param_schema",
            "enabled",
            "version",
            "description");
  }

  private static ConfigPackageSheetSpecs.SheetDef sheet(
      ConfigPackageSheetSpecs specs, String scenario, String sheetName) {
    return specs.project(List.of(scenario)).stream()
        .filter(def -> sheetName.equals(def.name()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("ALL 场景展开为租户业务能力，不包含平台资源分配")
  void shouldExpandAllIntoTenantBusinessScenarios() {
    List<String> scenarios = ConfigPackageSampleDataFactory.tenantTemplateScenarios(List.of("ALL"));
    assertThat(scenarios).contains("SCHEDULE", "IMPORT", "EXPORT", "ATOMIC");
    assertThat(new ConfigPackageSheetSpecs(messageSource()).project(scenarios))
        .extracting(ConfigPackageSheetSpecs.SheetDef::name)
        .doesNotContain(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET);
    ConfigPackageSheetSpecs.SheetDef jobs = new ConfigPackageSheetSpecs(messageSource())
        .project(scenarios).stream()
            .filter(sheet -> ConfigPackageExcelValidator.JOB_SHEET.equals(sheet.name()))
            .findFirst()
            .orElseThrow();
    assertThat(jobs.columns()).doesNotContain("queue_code", "worker_group");
  }

  @Test
  @DisplayName("导出、派发和工作流模板仅包含场景所需 sheet")
  void shouldKeepSheetIndexes_whenBuildingExportDispatchAndWorkflowSamples() throws Exception {
    try (XSSFWorkbook exportWb = buildSampleTemplate("EXPORT")) {
      assertThat(cellByHeader(
              exportWb.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET),
              "template_code",
              1))
          .isEqualTo("TPL_EXPORT_CUSTOMER");
      assertThat(exportWb.getSheet(ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET))
          .isNull();
    }
    try (XSSFWorkbook dispatchWb = buildSampleTemplate("DISPATCH")) {
      assertThat(cellByHeader(
              dispatchWb.getSheet(ConfigPackageExcelValidator.CHANNEL_SHEET), "channel_code", 1))
          .isEqualTo("demo-sftp");
      assertThat(dispatchWb.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET))
          .isNull();
    }
    try (XSSFWorkbook workflowWb = buildSampleTemplate("WORKFLOW")) {
      assertThat(cellByHeader(
              workflowWb.getSheet(ConfigPackageExcelValidator.WF_DEF_SHEET), "workflow_code", 1))
          .isEqualTo("WF_CUSTOMER_EOD");
      assertThat(workflowWb.getSheet(ConfigPackageExcelValidator.WF_EDGE_SHEET).getLastRowNum())
          .isEqualTo(3);
      assertThat(workflowWb.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET))
          .isNull();
    }
  }

  @Test
  @DisplayName("多场景组合模板合并业务行且不生成资源分配 Sheet")
  void shouldCombineScenarios_whenMultipleSelected() throws Exception {
    try (XSSFWorkbook wb = buildSampleTemplate(List.of("IMPORT", "PROCESS"))) {
      Sheet jobSheet = wb.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      List<String> jobCodes = new ArrayList<>();
      for (int i = 1; i <= jobSheet.getLastRowNum(); i++) {
        jobCodes.add(cellByHeader(jobSheet, "job_code", i));
      }
      assertThat(jobCodes).contains("JOB_IMPORT_CUSTOMER", "JOB_PROCESS_CUSTOMER");
      assertThat(wb.getSheet(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET)).isNull();
    }
  }

  @Test
  @DisplayName("所有场景模板的精简表头均满足上传解析器的结构要求")
  void shouldIncludeRequiredHeadersInProjectedScenarioSheets() {
    ConfigPackageSheetSpecs specs = new ConfigPackageSheetSpecs(messageSource());
    for (String scenario : List.of(
        "IMPORT",
        "EXPORT",
        "IMPORT_DELIMITED",
        "IMPORT_FIXED_WIDTH",
        "IMPORT_EXCEL",
        "IMPORT_XML",
        "IMPORT_JSON",
        "EXPORT_DELIMITED",
        "EXPORT_FIXED_WIDTH",
        "EXPORT_EXCEL",
        "EXPORT_JSON",
        "PROCESS",
        "DISPATCH",
        "ATOMIC",
        "WORKFLOW",
        "SCHEDULE")) {
      for (ConfigPackageSheetSpecs.SheetDef sheet : specs.project(List.of(scenario))) {
        assertThat(sheet.columns())
            .as("%s 场景的 %s 表头", scenario, sheet.name())
            .containsAll(
                ConfigPackageSheetSpecs.requiredImportHeaders(sheet.name(), sheet.columns()));
      }
    }
    List<String> importColumns = specs.project(List.of("IMPORT")).stream()
        .filter(sheet -> ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET.equals(sheet.name()))
        .findFirst()
        .orElseThrow()
        .columns();
    List<String> exportColumns = specs.project(List.of("EXPORT")).stream()
        .filter(sheet -> ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET.equals(sheet.name()))
        .findFirst()
        .orElseThrow()
        .columns();
    assertThat(importColumns).contains("validation_rule_set").doesNotContain("default_query_sql");
    assertThat(exportColumns)
        .contains("default_query_sql", "query_param_schema")
        .doesNotContain("validation_rule_set");
  }

  @Test
  @DisplayName("文件模板按方向和格式隐藏无关列并固定所选格式")
  void shouldProjectFileTemplateColumnsByDirectionAndFormat() {
    ConfigPackageSheetSpecs specs = new ConfigPackageSheetSpecs(messageSource());
    List<String> delimitedImport = fileTemplateColumns(specs, "IMPORT_DELIMITED");
    List<String> fixedWidthImport = fileTemplateColumns(specs, "IMPORT_FIXED_WIDTH");
    List<String> excelImport = fileTemplateColumns(specs, "IMPORT_EXCEL");
    List<String> xmlImport = fileTemplateColumns(specs, "IMPORT_XML");
    List<String> excelExport = fileTemplateColumns(specs, "EXPORT_EXCEL");

    assertThat(delimitedImport)
        .contains("delimiter", "quote_char", "validation_rule_set")
        .doesNotContain("record_length", "default_query_sql");
    assertThat(fixedWidthImport)
        .contains("record_length", "validation_rule_set")
        .doesNotContain("delimiter", "quote_char", "default_query_sql");
    assertThat(excelImport)
        .contains("header_rows", "validation_rule_set")
        .doesNotContain("charset", "delimiter", "record_length");
    assertThat(xmlImport)
        .contains("charset", "validation_rule_set")
        .doesNotContain("delimiter", "record_length", "header_rows");
    assertThat(excelExport)
        .contains("default_query_sql", "query_param_schema", "header_rows")
        .doesNotContain("validation_rule_set", "charset", "delimiter", "record_length");
    assertThat(specs.project(List.of("IMPORT_EXCEL")).stream()
            .filter(sheet -> ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET.equals(sheet.name()))
            .findFirst()
            .orElseThrow()
            .guides()
            .get("file_format_type")
            .allowedValues())
        .containsExactly("EXCEL");
    assertThat(specs.project(List.of("IMPORT_EXCEL")).stream()
            .filter(sheet -> ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET.equals(sheet.name()))
            .findFirst()
            .orElseThrow()
            .guides()
            .get("template_type")
            .allowedValues())
        .containsExactly("IMPORT");
    assertThat(ConfigPackageSampleDataFactory.normalizeScenario("IMPORT_EXCEL"))
        .isEqualTo("IMPORT_EXCEL");
  }

  @Test
  @DisplayName("格式场景空白模板限制格式下拉且示例值与场景一致")
  void shouldConstrainFormatDropdownAndAlignSampleFormat() throws Exception {
    try (XSSFWorkbook blankTemplate = buildFocusedTemplate(List.of("IMPORT_EXCEL"))) {
      Sheet sheet = blankTemplate.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET);
      int formatColumn = headerIndex(sheet, "file_format_type");
      DataValidation formatValidation = sheet.getDataValidations().stream()
          .filter(validation ->
              validation.getRegions().getCellRangeAddress(0).getFirstColumn() == formatColumn)
          .findFirst()
          .orElseThrow();
      assertThat(formatValidation.getValidationConstraint().getExplicitListValues())
          .containsExactly("EXCEL");
      int templateTypeColumn = headerIndex(sheet, "template_type");
      DataValidation typeValidation = sheet.getDataValidations().stream()
          .filter(validation ->
              validation.getRegions().getCellRangeAddress(0).getFirstColumn() == templateTypeColumn)
          .findFirst()
          .orElseThrow();
      assertThat(typeValidation.getValidationConstraint().getExplicitListValues())
          .containsExactly("IMPORT");
    }
    try (XSSFWorkbook sampleTemplate = buildSampleTemplate("IMPORT_EXCEL")) {
      assertThat(cellByHeader(
              sampleTemplate.getSheet(ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET),
              "file_format_type",
              1))
          .isEqualTo("EXCEL");
    }
  }

  private static List<String> fileTemplateColumns(ConfigPackageSheetSpecs specs, String scenario) {
    return specs.project(List.of(scenario)).stream()
        .filter(sheet -> ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET.equals(sheet.name()))
        .findFirst()
        .orElseThrow()
        .columns();
  }

  private static int headerIndex(Sheet sheet, String name) {
    Row header = sheet.getRow(0);
    for (int column = 0; column < header.getLastCellNum(); column++) {
      if (name.equals(header.getCell(column).getStringCellValue())) {
        return column;
      }
    }
    throw new AssertionError("missing header: " + name);
  }

  private static void assertDropdownValues(Sheet sheet, String headerName, List<String> expected) {
    int columnIndex = headerIndex(sheet, headerName);
    DataValidation validation = sheet.getDataValidations().stream()
        .filter(candidate -> candidate.getValidationConstraint().getExplicitListValues() != null)
        .filter(candidate ->
            candidate.getRegions().getCellRangeAddress(0).getFirstColumn() == columnIndex)
        .findFirst()
        .orElseThrow(() ->
            new AssertionError("missing dropdown for " + sheet.getSheetName() + "." + headerName));
    assertThat(validation.getValidationConstraint().getExplicitListValues())
        .containsExactlyElementsOf(expected);
  }

  @Test
  @DisplayName("未知场景被明确拒绝，不回退为完整配置包")
  void shouldRejectUnknownScenario() {
    assertThatThrownBy(() -> ConfigPackageSampleDataFactory.normalizeScenario("IMPRT"))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> ConfigPackageSampleDataFactory.tenantTemplateScenarios(List.of()))
        .isInstanceOf(BizException.class);
  }

  private static StaticMessageSource messageSource() {
    StaticMessageSource ms = new StaticMessageSource();
    ms.setUseCodeAsDefaultMessage(true);
    return ms;
  }

  private static String cellByHeader(Sheet sheet, String headerName, int rowIndex) {
    Row header = sheet.getRow(0);
    Row row = sheet.getRow(rowIndex);
    for (int column = 0; column < header.getLastCellNum(); column++) {
      if (headerName.equals(header.getCell(column).getStringCellValue())) {
        return row.getCell(column).getStringCellValue();
      }
    }
    throw new AssertionError("missing header: " + headerName);
  }

  @Test
  @DisplayName("填写顺序 sheet:必需列、超链接与留空默认提示均已生成")
  void shouldProvideFillOrderSheet_whenBuildingTemplate() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet sheet = wb.getSheet(ConfigPackageWorkbookSupplementWriter.SHEET_NAME_FILL_ORDER);
      assertThat(sheet).as("填写顺序 sheet 必须存在").isNotNull();
      Row header = sheet.getRow(1);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("顺序");
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("Sheet");
      assertThat(header.getCell(4).getStringCellValue()).isEqualTo("关键必填列");
      assertThat(header.getCell(6).getStringCellValue()).isEqualTo("留空/默认提示");

      Row firstData = sheet.getRow(2);
      assertThat(firstData.getCell(1).getStringCellValue())
          .isEqualTo(ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET);
      assertThat(firstData.getCell(1).getHyperlink().getAddress())
          .isEqualTo("'" + ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET + "'!A1");
      assertThat(firstData.getCell(4).getStringCellValue()).contains("queue_code", "queue_name");
      assertThat(firstData.getCell(6).getStringCellValue()).contains("字段说明");
    }
  }

  @Test
  @DisplayName("数据 sheet:必填列通过公式校验强制非空")
  void shouldApplyRequiredColumnValidation_whenBuildingDataSheets() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet jobSheet = wb.getSheet(ConfigPackageExcelValidator.JOB_SHEET);
      List<String> formulas = new ArrayList<>();
      for (DataValidation validation : jobSheet.getDataValidations()) {
        formulas.add(validation.getValidationConstraint().getFormula1());
      }
      assertThat(formulas)
          .contains("LEN(TRIM(B2))>0") // job_code
          .contains("LEN(TRIM(C2))>0"); // job_name
    }
  }

  @Test
  @DisplayName("五类 Worker 示例 sheet:表头与五行数据齐全,类型取值与枚举一致")
  void shouldProvideFiveWorkerSheet_whenBuildingTemplate() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet sheet = wb.getSheet(ConfigPackageWorkbookSupplementWriter.SHEET_NAME_FIVE_WORKER);
      assertThat(sheet).as("五类Worker示例 sheet 必须存在").isNotNull();
      // row0 = 提示行, row1 = 表头, row2..row6 = 5 类 worker
      Row header = sheet.getRow(1);
      List<String> headerValues = new ArrayList<>();
      for (int c = 0; c < ConfigPackageWorkbookSupplementWriter.FIVE_WORKER_HEADERS.length; c++) {
        headerValues.add(header.getCell(c).getStringCellValue());
      }
      assertThat(headerValues)
          .containsExactly(ConfigPackageWorkbookSupplementWriter.FIVE_WORKER_HEADERS);
      assertThat(ConfigPackageWorkbookSupplementWriter.FIVE_WORKER_ROWS).hasSize(5);
      List<String> workerTypes = new ArrayList<>();
      List<String> pipelineTypes = new ArrayList<>();
      for (int r = 0; r < 5; r++) {
        Row row = sheet.getRow(2 + r);
        workerTypes.add(row.getCell(0).getStringCellValue());
        pipelineTypes.add(row.getCell(2).getStringCellValue());
      }
      assertThat(workerTypes).containsExactly("IMPORT", "EXPORT", "PROCESS", "DISPATCH", "ATOMIC");
      // Atomic 无 Pipeline，其余 pipeline_type / worker_type 必须都是合法 enum code。
      Set<String> pipelineCodes = DictEnum.codes(PipelineType.class);
      Set<String> jobCodes = DictEnum.codes(JobType.class);
      assertThat(pipelineTypes.subList(0, 4)).allMatch(pipelineCodes::contains);
      assertThat(pipelineTypes.get(4)).isEqualTo("不适用");
      assertThat(workerTypes).allMatch(jobCodes::contains);
    }
  }

  @Test
  @DisplayName("文件束示例 sheet:三种束类型与真实清单示例齐备")
  void shouldProvideBundleSheetWithManifestExamples_whenBuildingTemplate() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet sheet = wb.getSheet(ConfigPackageWorkbookSupplementWriter.SHEET_NAME_BUNDLE);
      assertThat(sheet).as("文件束示例 sheet 必须存在").isNotNull();
      Row header = sheet.getRow(1);
      List<String> headerValues = new ArrayList<>();
      for (int c = 0; c < ConfigPackageWorkbookSupplementWriter.BUNDLE_HEADERS.length; c++) {
        headerValues.add(header.getCell(c).getStringCellValue());
      }
      assertThat(headerValues)
          .containsExactly(ConfigPackageWorkbookSupplementWriter.BUNDLE_HEADERS);
      assertThat(ConfigPackageWorkbookSupplementWriter.BUNDLE_ROWS).hasSize(3);
      List<String> jobTypes = new ArrayList<>();
      List<String> manifestExamples = new ArrayList<>();
      for (int r = 0; r < 3; r++) {
        Row row = sheet.getRow(2 + r);
        jobTypes.add(row.getCell(0).getStringCellValue());
        manifestExamples.add(row.getCell(4).getStringCellValue());
      }
      assertThat(jobTypes).containsExactly("BUNDLE_IMPORT", "BUNDLE_EXPORT", "BUNDLE_DISPATCH");
      // job_type 必须都是合法 JobType enum code(束类型新增/改名而示例未同步即挂)。
      Set<String> jobCodes = DictEnum.codes(JobType.class);
      assertThat(jobTypes).allMatch(jobCodes::contains);
      // 每行的 manifest 示例必须是真实非空 .batch.json v2(含 schemaVersion + jobCode),不是占位。
      assertThat(manifestExamples)
          .allSatisfy(m -> assertThat(m)
              .contains("batch-manifest-v2")
              .contains("jobCode")
              .contains("fileMapping"));
      // 导出束清单不含 requiredFiles(manifest-only 触发);导入/分发含。
      assertThat(manifestExamples.get(1)).doesNotContain("requiredFiles");
      assertThat(manifestExamples.get(0)).contains("requiredFiles");
      assertThat(manifestExamples.get(2)).contains("requiredFiles");
    }
  }

  @Test
  @DisplayName("依赖说明 sheet:表头与关键引用关系行齐备")
  void shouldProvideDependencySheet_whenBuildingTemplate() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet sheet = wb.getSheet(ConfigPackageWorkbookSupplementWriter.SHEET_NAME_DEPENDENCY);
      assertThat(sheet).as("依赖说明 sheet 必须存在").isNotNull();
      Row header = sheet.getRow(1);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("source_sheet");
      assertThat(header.getCell(4).getStringCellValue()).isEqualTo("db_fallback");
      // 关键引用关系行必须在(job->queue / step->channel / default_params.templateCode->file_template)。
      List<String> firstCol = new ArrayList<>();
      List<String> targets = new ArrayList<>();
      for (String[] r : ConfigPackageWorkbookSupplementWriter.DEPENDENCY_ROWS) {
        firstCol.add(r[0]);
        targets.add(r[2]);
      }
      assertThat(firstCol).contains("job_definition", "pipeline_step_definition", "workflow_node");
      assertThat(targets).contains("resource_queue", "file_channel_config", "file_template_config");
    }
  }

  @Test
  @DisplayName("字段说明 sheet:填写示例列给出导入导出两套真实非空结构")
  void shouldProvideRealFillExamples_whenFieldGuideRendered() throws Exception {
    try (XSSFWorkbook wb = buildTemplate()) {
      Sheet sheet = wb.getSheet("字段说明");
      assertThat(sheet).isNotNull();
      Row header = sheet.getRow(0);
      assertThat(header.getCell(3).getStringCellValue()).isEqualTo("填写层级");
      // 第 10 列(index 9)= 默认值/留空行为；第 11 列(index 10)= 填写示例
      assertThat(header.getCell(9).getStringCellValue()).isEqualTo("默认值/留空行为");
      assertThat(header.getCell(10).getStringCellValue()).isEqualTo("填写示例");
      assertThat(rowOf(sheet, "queue_code").getCell(1).getHyperlink().getAddress())
          .isEqualTo("'" + ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET + "'!A1");
      assertThat(rowOf(sheet, "template_code").getCell(3).getStringCellValue()).isEqualTo("必填");
      assertThat(rowOf(sheet, "template_code").getCell(9).getStringCellValue()).contains("必须填写");
      assertThat(rowOf(sheet, "field_mappings").getCell(3).getStringCellValue()).isEqualTo("常用");
      assertThat(rowOf(sheet, "encrypt_type").getCell(3).getStringCellValue()).isEqualTo("高级");
      assertThat(rowOf(sheet, "header_rows").getCell(6).getStringCellValue()).contains("留空默认 0");
      assertThat(rowOf(sheet, "log_masking_enabled").getCell(6).getStringCellValue())
          .contains("留空默认 FALSE");

      // 收集 file_template_config 段的 field_mappings / query_param_schema 行的填写示例,
      // 必须同时含 import 和 export 两套结构关键字,且非空 {}。
      String fieldMappingsFill = fillExampleOf(sheet, "field_mappings");
      assertThat(fieldMappingsFill)
          .contains("targetColumn") // import 结构
          .contains("sourceColumn") // export 结构
          .doesNotContain("\"source\":\"name\",\"target\":\"NAME\"");
      String queryParamFill = fillExampleOf(sheet, "query_param_schema");
      assertThat(queryParamFill)
          .contains("jdbcMappedImport") // import
          .contains("conflictColumns")
          .isNotEqualTo("{}");

      // 示例只展示渠道参数结构；导出的租户配置包不得包含渠道凭据。
      String configJsonFill = fillExampleOf(sheet, "config_json");
      assertThat(configJsonFill)
          .contains("sftp_host", "sftp_port", "sftp_user")
          .doesNotContain("passwordRef", "sftp_password");
      assertThat(rowOf(sheet, "config_json").getCell(3).getStringCellValue()).isEqualTo("可选");
    }
  }

  private static String fillExampleOf(Sheet sheet, String colName) {
    return rowOf(sheet, colName).getCell(10).getStringCellValue();
  }

  private static Row rowOf(Sheet sheet, String colName) {
    for (Row row : sheet) {
      if (row.getRowNum() == 0) {
        continue;
      }
      if (colName.equals(row.getCell(1).getStringCellValue())) {
        return row;
      }
    }
    throw new AssertionError("column not found in field guide: " + colName);
  }
}
