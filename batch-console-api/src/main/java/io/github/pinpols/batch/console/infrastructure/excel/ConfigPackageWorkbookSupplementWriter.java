package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;

import io.github.pinpols.batch.common.enums.BatchWindowEndStrategy;
import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.ExecutionMode;
import io.github.pinpols.batch.common.enums.FileChannelAuthType;
import io.github.pinpols.batch.common.enums.FileChannelType;
import io.github.pinpols.batch.common.enums.FileChecksumType;
import io.github.pinpols.batch.common.enums.FileCompressType;
import io.github.pinpols.batch.common.enums.FileEncryptType;
import io.github.pinpols.batch.common.enums.FileReceiptPolicy;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.enums.FileTemplateType;
import io.github.pinpols.batch.common.enums.HolidayRollRule;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.OutOfWindowAction;
import io.github.pinpols.batch.common.enums.PipelineType;
import io.github.pinpols.batch.common.enums.QueuePriorityPolicy;
import io.github.pinpols.batch.common.enums.ResourceQueueType;
import io.github.pinpols.batch.common.enums.RetryPolicyType;
import io.github.pinpols.batch.common.enums.ScheduleType;
import io.github.pinpols.batch.common.enums.ShardStrategy;
import io.github.pinpols.batch.common.enums.WorkflowEdgeType;
import io.github.pinpols.batch.common.enums.WorkflowNodeType;
import io.github.pinpols.batch.common.enums.WorkflowType;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.support.excel.ConsoleExcelStyles;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.common.usermodel.HyperlinkType;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Hyperlink;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.springframework.context.MessageSource;

/** 为租户配置包工作簿写入只读的补充工作表。 */
class ConfigPackageWorkbookSupplementWriter {

  private static final String EMPTY = "";
  private static final ConfigPackageGuidanceContent GUIDANCE = ConfigPackageGuidanceContent.load();

  private static final int README_LINE_COUNT = 86;
  static final String SHEET_NAME_FILL_ORDER = "填写顺序";
  static final String SHEET_NAME_DEPENDENCY = "依赖说明";
  static final String SHEET_NAME_FIVE_WORKER = "五类Worker示例";
  static final String SHEET_NAME_BUNDLE = "文件束示例";
  /** 填写顺序 sheet 的逐 sheet 说明；新增数据 sheet 时在此登记，缺项由引导测试拦截。 */
  private record FillGuide(String scenario, String reason) {}

  private static final FillGuide DEFAULT_FILL_GUIDE = new FillGuide("按业务需要填写。", EMPTY);

  private static final String BASE_DICTIONARY_REASON = "基础字典，后续 job / workflow node 会引用。";

  private static final String FILE_REFERENCE_REASON = "文件类作业和派发步骤会引用，建议先于 pipeline step 填。";

  private static final Map<String, FillGuide> FILL_GUIDES = Map.ofEntries(
      Map.entry(
          ConfigPackageExcelValidator.RESOURCE_QUEUE_SHEET,
          new FillGuide("配置资源池/并发/QPS。", BASE_DICTIONARY_REASON)),
      Map.entry(
          ConfigPackageExcelValidator.BUSINESS_CALENDAR_SHEET,
          new FillGuide("配置批量日历、时区、节假日。", BASE_DICTIONARY_REASON)),
      Map.entry(
          ConfigPackageExcelValidator.BATCH_WINDOW_SHEET,
          new FillGuide("配置允许执行的时间窗口。", BASE_DICTIONARY_REASON)),
      Map.entry(
          ConfigPackageExcelValidator.JOB_SHEET,
          new FillGuide("配置作业主定义，所有 Worker 共用。", "pipeline 和 workflow node 都会引用 job_code。")),
      Map.entry(
          ConfigPackageExcelValidator.JOB_MONITORING_POLICY_SHEET,
          new FillGuide("配置作业运行时长、启动宽限与完成截止告警。", "按 job_code 关联作业，需在作业定义之后填写。")),
      Map.entry(
          ConfigPackageExcelValidator.CHANNEL_SHEET,
          new FillGuide("配置 SFTP/OSS/S3/API 等文件渠道。", FILE_REFERENCE_REASON)),
      Map.entry(
          ConfigPackageExcelValidator.FILE_TEMPLATE_SHEET,
          new FillGuide("配置导入/导出文件格式、字段映射、查询。", FILE_REFERENCE_REASON)),
      Map.entry(
          ConfigPackageExcelValidator.WF_DEF_SHEET,
          new FillGuide("配置工作流主定义。", "workflow_node / workflow_edge 共用 workflow_code + version。")),
      Map.entry(
          ConfigPackageExcelValidator.WF_NODE_SHEET,
          new FillGuide("配置工作流节点。", "workflow_edge 需要引用 from/to node_code。")),
      Map.entry(
          ConfigPackageExcelValidator.WF_EDGE_SHEET,
          new FillGuide("配置工作流节点依赖边。", "最后填写，依赖 workflow node 已存在。")),
      Map.entry(
          ConfigPackageExcelValidator.WORKFLOW_MONITORING_POLICY_SHEET,
          new FillGuide("配置工作流运行时长、启动宽限与完成截止告警。", "按 workflow_code + version 关联工作流，需在工作流定义之后填写。")));
  private static final String READONLY_SHEET_HINT =
      "本 sheet 为只读说明，不参与导入解析与 apply；请复制片段到对应数据 sheet 后修改。";
  private static final String DICTIONARY_SHEET_HINT =
      "以下为配置包常用枚举的编码与含义；数据 Sheet 请填写编码，具体可选范围以列下拉和字段说明为准。";
  private static final List<EnumDictionary> ENUM_DICTIONARIES = List.of(
      new EnumDictionary("作业类型", JobType.class),
      new EnumDictionary("调度类型", ScheduleType.class),
      new EnumDictionary("重试策略", RetryPolicyType.class),
      new EnumDictionary("分片策略", ShardStrategy.class),
      new EnumDictionary("执行模式", ExecutionMode.class),
      new EnumDictionary("资源队列类型", ResourceQueueType.class),
      new EnumDictionary("队列优先级策略", QueuePriorityPolicy.class),
      new EnumDictionary("文件渠道类型", FileChannelType.class),
      new EnumDictionary("文件渠道认证类型", FileChannelAuthType.class),
      new EnumDictionary("文件回执策略", FileReceiptPolicy.class),
      new EnumDictionary("文件模板类型", FileTemplateType.class),
      new EnumDictionary("文件格式", FileTemplateFormat.class),
      new EnumDictionary("文件校验算法", FileChecksumType.class),
      new EnumDictionary("文件压缩方式", FileCompressType.class),
      new EnumDictionary("文件加密方式", FileEncryptType.class),
      new EnumDictionary("节假日顺延规则", HolidayRollRule.class),
      new EnumDictionary("补数策略", CatchUpPolicyType.class),
      new EnumDictionary("窗口结束策略", BatchWindowEndStrategy.class),
      new EnumDictionary("超出窗口处理方式", OutOfWindowAction.class),
      new EnumDictionary("流水线类型", PipelineType.class),
      new EnumDictionary("工作流类型", WorkflowType.class),
      new EnumDictionary("工作流节点类型", WorkflowNodeType.class),
      new EnumDictionary("工作流依赖边类型", WorkflowEdgeType.class));

  static final String[] DEPENDENCY_HEADERS = GUIDANCE.sheet("dependency").headers();
  static final List<String[]> DEPENDENCY_ROWS = GUIDANCE.sheet("dependency").rows();
  static final String[] FIVE_WORKER_HEADERS = GUIDANCE.sheet("fiveWorker").headers();
  static final List<String[]> FIVE_WORKER_ROWS = GUIDANCE.sheet("fiveWorker").rows();
  static final String[] BUNDLE_HEADERS = GUIDANCE.sheet("bundle").headers();
  static final List<String[]> BUNDLE_ROWS = GUIDANCE.sheet("bundle").rows();

  private final MessageSource messageSource;

  ConfigPackageWorkbookSupplementWriter(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  void createReadmeSheet(Workbook wb, Locale locale) {
    Sheet sheet = wb.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    sheet.setColumnWidth(0, 28000);
    CellStyle title = createReadmeTitleStyle(wb);
    Row titleRow = sheet.createRow(0);
    Cell titleCell = titleRow.createCell(0);
    String titleKey = "excel.package.readme.title";
    titleCell.setCellValue(messageSource.getMessage(titleKey, null, titleKey, locale));
    titleCell.setCellStyle(title);
    for (int i = 1; i <= README_LINE_COUNT; i++) {
      String key = "excel.package.readme.line" + i;
      Row row = sheet.createRow(i);
      Cell cell = row.createCell(0);
      cell.setCellValue(messageSource.getMessage(key, null, key, locale));
    }
  }

  void createFocusedReadmeSheet(
      Workbook wb, Locale locale, List<ConfigPackageSheetSpecs.SheetDef> sheetDefs) {
    Sheet sheet = wb.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    sheet.setColumnWidth(0, 28000);
    CellStyle title = createReadmeTitleStyle(wb);
    String[] keys = {
      "excel.package.focused.title",
      "excel.package.focused.scope",
      "excel.package.focused.resources",
      "excel.package.focused.apply"
    };
    Object[][] arguments = {
      {},
      {
        String.join(
            ", ", sheetDefs.stream().map(ConfigPackageSheetSpecs.SheetDef::name).toList())
      },
      {},
      {}
    };
    for (int i = 0; i < keys.length; i++) {
      Row row = sheet.createRow(i);
      Cell cell = row.createCell(0);
      cell.setCellValue(messageSource.getMessage(keys[i], arguments[i], keys[i], locale));
      if (i == 0) {
        cell.setCellStyle(title);
      }
    }
  }

  void createFillOrderSheet(Workbook wb, List<ConfigPackageSheetSpecs.SheetDef> sheetDefs) {
    Sheet sheet = wb.createSheet(SHEET_NAME_FILL_ORDER);
    CreationHelper creationHelper = wb.getCreationHelper();
    Row hintRow = sheet.createRow(0);
    hintRow.createCell(0).setCellValue("建议先按本 sheet 从上到下填写；只读说明 sheet 不参与导入解析与 apply。");
    CellStyle headerStyle = ConsoleExcelStyles.createHeaderStyle(wb);
    CellStyle bodyStyle = ConsoleExcelStyles.createDataStyle(wb);
    bodyStyle.setWrapText(true);
    String[] headers = {"顺序", "Sheet", "填写场景", "为什么先填", "关键必填列", "适用范围", "留空/默认提示"};
    Row headerRow = sheet.createRow(1);
    for (int c = 0; c < headers.length; c++) {
      Cell cell = headerRow.createCell(c);
      cell.setCellValue(headers[c]);
      cell.setCellStyle(headerStyle);
    }
    int rowIdx = 2;
    for (int i = 0; i < sheetDefs.size(); i++) {
      ConfigPackageSheetSpecs.SheetDef def = sheetDefs.get(i);
      Row row = sheet.createRow(rowIdx++);
      writeBodyCell(row, 0, String.valueOf(i + 1), bodyStyle);
      writeBodyCell(row, 1, def.name(), bodyStyle);
      addSheetLink(row.getCell(1), def.name(), creationHelper);
      FillGuide fillGuide = FILL_GUIDES.getOrDefault(def.name(), DEFAULT_FILL_GUIDE);
      writeBodyCell(row, 2, fillGuide.scenario(), bodyStyle);
      writeBodyCell(row, 3, fillGuide.reason(), bodyStyle);
      writeBodyCell(row, 4, requiredColumns(def), bodyStyle);
      writeBodyCell(
          row, 5, ConfigPackageExcelWorkbookWriter.appliesToFor(def.name(), ""), bodyStyle);
      writeBodyCell(row, 6, defaultHintFor(def), bodyStyle);
    }
    sheet.createFreezePane(0, 2);
    int lastFilterRowIndex = Math.max(1, rowIdx - 1);
    CellRangeAddress filterRange =
        new CellRangeAddress(1, lastFilterRowIndex, 0, headers.length - 1);
    sheet.setAutoFilter(filterRange);
    int[] widths = {10, 30, 22, 42, 50, 42, 46};
    for (int c = 0; c < widths.length; c++) {
      sheet.setColumnWidth(c, widths[c] * 256);
    }
  }

  private static void addSheetLink(
      Cell cell, String targetSheetName, CreationHelper creationHelper) {
    Hyperlink link = creationHelper.createHyperlink(HyperlinkType.DOCUMENT);
    link.setAddress("'" + targetSheetName.replace("'", "''") + "'!A1");
    cell.setHyperlink(link);
  }

  void createDependencyGuideSheet(Workbook wb) {
    createReadOnlyTableSheet(wb, SHEET_NAME_DEPENDENCY, DEPENDENCY_HEADERS, DEPENDENCY_ROWS);
  }

  void createFiveWorkerExampleSheet(Workbook wb) {
    createReadOnlyTableSheet(wb, SHEET_NAME_FIVE_WORKER, FIVE_WORKER_HEADERS, FIVE_WORKER_ROWS);
  }

  void createBundleExampleSheet(Workbook wb) {
    createReadOnlyTableSheet(wb, SHEET_NAME_BUNDLE, BUNDLE_HEADERS, BUNDLE_ROWS);
  }

  void createDictionarySheet(Workbook wb, Locale locale) {
    List<String[]> rows = ENUM_DICTIONARIES.stream()
        .flatMap(dictionary -> Arrays.stream(dictionary.type().getEnumConstants())
            .map(value -> new String[] {
              dictionary.name(),
              value.code(),
              messageSource.getMessage(value.messageKey(), null, value.label(), locale)
            }))
        .toList();
    createReadOnlyTableSheet(
        wb,
        ConsoleExcelStyles.SHEET_NAME_DICT,
        new String[] {"字典类别", "编码", "含义"},
        rows,
        DICTIONARY_SHEET_HINT);
    Sheet sheet = wb.getSheet(ConsoleExcelStyles.SHEET_NAME_DICT);
    sheet.setColumnWidth(0, 30 * 256);
    sheet.setColumnWidth(1, 28 * 256);
    sheet.setColumnWidth(2, 44 * 256);
  }

  private void createReadOnlyTableSheet(
      Workbook wb, String sheetName, String[] headers, List<String[]> rows) {
    createReadOnlyTableSheet(wb, sheetName, headers, rows, READONLY_SHEET_HINT);
  }

  private void createReadOnlyTableSheet(
      Workbook wb, String sheetName, String[] headers, List<String[]> rows, String hint) {
    Sheet sheet = wb.createSheet(sheetName);
    Row hintRow = sheet.createRow(0);
    hintRow.createCell(0).setCellValue(hint);
    CellStyle headerStyle = ConsoleExcelStyles.createHeaderStyle(wb);
    CellStyle bodyStyle = ConsoleExcelStyles.createDataStyle(wb);
    bodyStyle.setWrapText(true);
    Row headerRow = sheet.createRow(1);
    for (int c = 0; c < headers.length; c++) {
      Cell cell = headerRow.createCell(c);
      cell.setCellValue(headers[c]);
      cell.setCellStyle(headerStyle);
    }
    int rowIdx = 2;
    for (String[] data : rows) {
      Row row = sheet.createRow(rowIdx++);
      for (int c = 0; c < headers.length; c++) {
        Cell cell = row.createCell(c);
        cell.setCellValue(c < data.length && data[c] != null ? data[c] : EMPTY);
        cell.setCellStyle(bodyStyle);
      }
    }
    sheet.createFreezePane(0, 2);
    for (int c = 0; c < headers.length; c++) {
      sheet.setColumnWidth(c, 12000);
    }
    int lastFilterRowIndex = Math.max(1, rowIdx - 1);
    CellRangeAddress filterRange =
        new CellRangeAddress(1, lastFilterRowIndex, 0, headers.length - 1);
    sheet.setAutoFilter(filterRange);
  }

  private static void writeBodyCell(Row row, int columnIndex, String value, CellStyle style) {
    Cell cell = row.createCell(columnIndex);
    cell.setCellValue(EmptyChecks.isNull(value) ? EMPTY : value);
    cell.setCellStyle(style);
  }

  private static String requiredColumns(ConfigPackageSheetSpecs.SheetDef def) {
    List<String> required = def.columns().stream()
        .filter(column -> {
          ConsoleExcelStyles.ColumnGuide guide = def.guides().get(column);
          return EmptyChecks.isNotNull(guide) && guide.required();
        })
        .toList();
    return EmptyChecks.isEmpty(required) ? "无强制必填列，按业务需要填写。" : String.join(", ", required);
  }

  private static String defaultHintFor(ConfigPackageSheetSpecs.SheetDef def) {
    long optionalCount = def.columns().stream()
        .filter(column -> {
          ConsoleExcelStyles.ColumnGuide guide = def.guides().get(column);
          return EmptyChecks.isNull(guide) || !guide.required();
        })
        .count();
    return optionalCount == 0 ? "本 sheet 列均为必填，留空会在预览阶段报错。" : "选填列可先留空；具体默认值/留空行为见『字段说明』sheet。";
  }

  private record EnumDictionary(String name, Class<? extends DictEnum> type) {}
}
