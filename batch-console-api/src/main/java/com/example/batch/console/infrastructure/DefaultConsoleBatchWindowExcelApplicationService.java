package com.example.batch.console.infrastructure;

import static com.example.batch.console.support.ConsoleExcelStyles.addBooleanValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.BatchWindowEndStrategy;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.OutOfWindowAction;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.console.application.ConsoleBatchWindowExcelApplicationService;
import com.example.batch.console.mapper.BatchWindowMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.mapper.param.BatchWindowUpsertParam;
import com.example.batch.console.support.ConsoleExcelStyles;
import com.example.batch.console.support.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.support.ExcelImportStore;
import com.example.batch.console.web.request.ExcelApplyRequest;
import com.example.batch.console.web.response.ConsoleBatchWindowResponse;
import com.example.batch.console.web.response.ExcelApplyResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Builder;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.batch.common.utils.Texts;

/** {@link ConsoleBatchWindowExcelApplicationService} 的默认实现。 */
@Service
public class DefaultConsoleBatchWindowExcelApplicationService
    extends AbstractSingleSheetExcelService<
        DefaultConsoleBatchWindowExcelApplicationService.WindowRow, ConsoleBatchWindowResponse>
    implements ConsoleBatchWindowExcelApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String COL_TIMEZONE = "timezone";
  private static final String COL_DESCRIPTION = "description";
  private static final String COL_END_STRATEGY = "end_strategy";
  private static final String COL_ENABLED = "enabled";
  private static final String GUIDE_FALSE = "FALSE";
  private static final String GUIDE_TRUE = "TRUE";
  private static final String COL_OUT_OF_WINDOW_ACTION = "out_of_window_action";
  private static final String COL_ALLOW_CROSS_DAY = "allow_cross_day";
  private static final String GUIDE_STR = "字符串";
  private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2})?$");

  private static final String SHEET_NAME = "batch_window";
  private static final List<String> COLUMNS =
      List.of(
          "tenant_id",
          "window_code",
          "window_name",
          COL_TIMEZONE,
          "start_time",
          "end_time",
          COL_END_STRATEGY,
          COL_OUT_OF_WINDOW_ACTION,
          COL_ALLOW_CROSS_DAY,
          COL_ENABLED,
          COL_DESCRIPTION);
  private static final Set<String> END_STRATEGIES = DictEnum.codes(BatchWindowEndStrategy.class);
  private static final Set<String> OUT_OF_WINDOW_ACTIONS = DictEnum.codes(OutOfWindowAction.class);
  private static final Map<String, ColumnGuide> COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              "tenant_id",
              optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              "window_code", requiredColumn("窗口唯一编码，作为导入匹配键。", GUIDE_STR, "WIN_SETTLEMENT")),
          Map.entry("window_name", requiredColumn("控制台展示的窗口名称。", GUIDE_STR, "清算窗口")),
          Map.entry(COL_TIMEZONE, requiredColumn("时区标识。", GUIDE_STR, "Asia/Shanghai")),
          Map.entry(
              "start_time", requiredColumn("窗口开始时间，格式 HH:mm 或 HH:mm:ss。", "时间", "08:00")),
          Map.entry(
              "end_time", requiredColumn("窗口结束时间，格式 HH:mm 或 HH:mm:ss。", "时间", "18:00")),
          Map.entry(
              COL_END_STRATEGY,
              requiredColumn(
                  "窗口结束策略。", "枚举", "FINISH_RUNNING", "STOP", "FINISH_RUNNING", "CONTINUE")),
          Map.entry(
              COL_OUT_OF_WINDOW_ACTION,
              requiredColumn("窗口外操作策略。", "枚举", "WAIT", "WAIT", "FAIL")),
          Map.entry(
              COL_ALLOW_CROSS_DAY,
              optionalColumn("是否允许跨天。", "布尔值", GUIDE_FALSE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(
              COL_ENABLED,
              optionalColumn("窗口是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(
              COL_DESCRIPTION, optionalColumn("窗口描述信息。", GUIDE_STR, "用于清算批处理的执行窗口")));

  private final BatchWindowMapper batchWindowMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleBatchWindowExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      BatchWindowMapper batchWindowMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore);
    this.batchWindowMapper = batchWindowMapper;
    this.configChangeLogMapper = configChangeLogMapper;
  }


  @Override
  public ResponseEntity<InputStreamResource> exportBatchWindows(String tenantId) {
    String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
    List<Map<String, Object>> rows =
        batchWindowMapper.selectByQuery(resolvedTenantId, null, null, null);
    return doExport(resolvedTenantId, rows);
  }

  @Override
  @Transactional
  public ExcelApplyResponse apply(String uploadToken, ExcelApplyRequest request) {
    return doApply(uploadToken, request.getReason());
  }


  @Override
  protected String sheetName() {
    return SHEET_NAME;
  }

  @Override
  protected List<String> columns() {
    return COLUMNS;
  }

  @Override
  protected Map<String, ColumnGuide> columnGuides() {
    return COLUMN_GUIDES;
  }

  @Override
  protected WindowRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    return WindowRow.builder()
        .rowNo(rowNo)
        .tenantId(effectiveTenant)
        .windowCode(requireText(values, "window_code", 128, issues))
        .windowName(requireText(values, "window_name", 256, issues))
        .timezone(requireText(values, COL_TIMEZONE, 64, issues))
        .startTime(requireTime(values, "start_time", issues))
        .endTime(requireTime(values, "end_time", issues))
        .endStrategy(requireEnum(values, COL_END_STRATEGY, END_STRATEGIES, 32, issues))
        .outOfWindowAction(
            requireEnum(values, COL_OUT_OF_WINDOW_ACTION, OUT_OF_WINDOW_ACTIONS, 32, issues))
        .allowCrossDay(optionalBoolean(values, COL_ALLOW_CROSS_DAY, false, issues))
        .enabled(optionalBoolean(values, COL_ENABLED, true, issues))
        .description(optionalText(values, COL_DESCRIPTION, 512, issues))
        .build();
  }

  @Override
  protected String rowUniqueKey(WindowRow row) {
    return row.windowCode();
  }

  @Override
  protected ConsoleBatchWindowResponse toResponse(WindowRow row) {
    return new ConsoleBatchWindowResponse(
        null,
        row.tenantId(),
        row.windowCode(),
        row.windowName(),
        row.timezone(),
        row.startTime(),
        row.endTime(),
        row.endStrategy(),
        row.outOfWindowAction(),
        row.allowCrossDay(),
        row.enabled(),
        row.description(),
        null,
        null);
  }

  @Override
  protected boolean upsertRow(WindowRow row, String tenantId, String operatorId) {
    Map<String, Object> existing =
        batchWindowMapper.selectByUniqueKey(tenantId, row.windowCode());
    BatchWindowUpsertParam param = new BatchWindowUpsertParam();
    param.setTenantId(tenantId);
    param.setWindowCode(row.windowCode());
    param.setWindowName(row.windowName());
    param.setTimezone(row.timezone());
    param.setStartTime(row.startTime());
    param.setEndTime(row.endTime());
    param.setEndStrategy(row.endStrategy());
    param.setOutOfWindowAction(row.outOfWindowAction());
    param.setAllowCrossDay(row.allowCrossDay());
    param.setEnabled(row.enabled());
    param.setDescription(row.description());
    batchWindowMapper.upsertBatchWindow(param);
    return existing == null || existing.isEmpty();
  }

  @Override
  protected void logChange(
      String tenantId,
      WindowRow row,
      String reason,
      String operatorId,
      String traceId,
      String action) {
    configChangeLogMapper.insertConfigChangeLog(
        mapOf(
            "tenantId",
            tenantId,
            "configType",
            "BATCH_WINDOW",
            "configKey",
            row.windowCode(),
            "versionNo",
            1,
            "changeAction",
            action,
            "changeResult",
            "SUCCESS",
            "operatorType",
            "USER",
            "operatorId",
            ConsoleTextSanitizer.safeInput(operatorId, 64),
            "traceId",
            ConsoleTextSanitizer.safeInput(traceId, 128),
            "changeSummaryJson",
            changeSummaryJson(
                reason,
                mapOf(
                    "windowName",
                    row.windowName(),
                    COL_TIMEZONE,
                    row.timezone(),
                    "startTime",
                    row.startTime(),
                    "endTime",
                    row.endTime(),
                    "endStrategy",
                    row.endStrategy(),
                    "outOfWindowAction",
                    row.outOfWindowAction()))));
  }

  @Override
  protected void applyValidations(Sheet sheet) {
    addDropdownValidation(
        sheet,
        6,
        END_STRATEGIES.toArray(String[]::new),
        "end_strategy 填写提示",
        "请从下拉列表中选择窗口结束策略。");
    addDropdownValidation(
        sheet,
        7,
        OUT_OF_WINDOW_ACTIONS.toArray(String[]::new),
        "out_of_window_action 填写提示",
        "请从下拉列表中选择窗口外操作策略。");
    addBooleanValidation(sheet, new int[] {8, 9}, "布尔值填写提示", "请填写 TRUE 或 FALSE。");
  }

  @Override
  protected void createReadmeSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("README");
    sheet.setColumnWidth(0, 16000);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] lines = {
      "batch window config maintenance template",
      "1. Orange headers mark required fields. Hover the header to see field rules and"
          + " examples.",
      "2. window_code is the unique key used during preview and apply.",
      "3. end_strategy, out_of_window_action, allow_cross_day, and enabled have built-in"
          + " dropdown validation.",
      "4. start_time and end_time must be in HH:mm or HH:mm:ss format.",
      "5. Import flow is upload -> preview -> apply."
    };
    for (int i = 0; i < lines.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(lines[i]);
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  @Override
  protected void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet("DICT");
    sheet.createFreezePane(0, 1, 0, 1);
    CellStyle dictHeaderStyle = ConsoleExcelStyles.createHeaderStyle(workbook);
    writeHeaders(sheet, List.of("field", "value", COL_DESCRIPTION), dictHeaderStyle);
    String[][] rows = {
      {COL_END_STRATEGY, "STOP", "stop immediately"},
      {COL_END_STRATEGY, "FINISH_RUNNING", "finish running tasks"},
      {COL_END_STRATEGY, "CONTINUE", "continue without restriction"},
      {COL_OUT_OF_WINDOW_ACTION, "WAIT", "wait until window opens"},
      {COL_OUT_OF_WINDOW_ACTION, "FAIL", "fail the task"},
      {COL_ALLOW_CROSS_DAY, GUIDE_TRUE, "allow cross-day window"},
      {COL_ALLOW_CROSS_DAY, GUIDE_FALSE, "disallow cross-day window"},
      {COL_ENABLED, GUIDE_TRUE, COL_ENABLED},
      {COL_ENABLED, GUIDE_FALSE, "disabled"}
    };
    for (int i = 0; i < rows.length; i++) {
      Row row = sheet.createRow(i + 1);
      row.createCell(0).setCellValue(rows[i][0]);
      row.createCell(1).setCellValue(rows[i][1]);
      row.createCell(2).setCellValue(rows[i][2]);
    }
    sheet.setColumnWidth(0, 24 * 256);
    sheet.setColumnWidth(1, 20 * 256);
    sheet.setColumnWidth(2, 36 * 256);
  }


  private static String requireTime(
      Map<String, String> values, String key, List<String> issues) {
    String normalized = normalize(values.get(key));
    if (!Texts.hasText(normalized)) {
      issues.add(key + " is required");
      return null;
    }
    if (!TIME_PATTERN.matcher(normalized).matches()) {
      issues.add(key + " must be HH:mm or HH:mm:ss format");
    }
    return normalized;
  }


  @Builder
  record WindowRow(
      int rowNo,
      String tenantId,
      String windowCode,
      String windowName,
      String timezone,
      String startTime,
      String endTime,
      String endStrategy,
      String outOfWindowAction,
      Boolean allowCrossDay,
      Boolean enabled,
      String description) {}
}