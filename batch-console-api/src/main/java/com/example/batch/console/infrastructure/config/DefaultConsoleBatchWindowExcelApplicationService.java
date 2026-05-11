package com.example.batch.console.infrastructure.config;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.addDropdownValidation;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.createReadmeTitleStyle;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setGuideColumnWidths;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.setReadmeColumnWidth;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.writeHeaders;

import com.example.batch.common.enums.BatchWindowEndStrategy;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.OutOfWindowAction;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.application.config.ConsoleBatchWindowExcelApplicationService;
import com.example.batch.console.domain.param.BatchWindowUpsertParam;
import com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService;
import com.example.batch.console.mapper.BatchWindowMapper;
import com.example.batch.console.mapper.ConfigChangeLogMapper;
import com.example.batch.console.support.ConfigChangeLogBuilder;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import com.example.batch.console.support.excel.ConsoleExcelStyles.ColumnGuide;
import com.example.batch.console.support.excel.ExcelImportStore;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.excel.ExcelApplyRequest;
import com.example.batch.console.web.response.excel.ExcelApplyResponse;
import com.example.batch.console.web.response.file.ConsoleBatchWindowResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Builder;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
              optionalColumn(
                  "excel.window.tenant_id.desc", "excel.guide.format.string", "tenant-a")),
          Map.entry(
              "window_code",
              requiredColumn(
                  "excel.window.window_code.desc", "excel.guide.format.string", "WIN_SETTLEMENT")),
          Map.entry(
              "window_name",
              requiredColumn("excel.window.window_name.desc", "excel.guide.format.string", "清算窗口")),
          Map.entry(
              COL_TIMEZONE,
              requiredColumn(
                  "excel.window.timezone.desc", "excel.guide.format.timezone_id", "Asia/Shanghai")),
          Map.entry(
              "start_time",
              requiredColumn("excel.window.start_time.desc", "excel.guide.format.time", "08:00")),
          Map.entry(
              "end_time",
              requiredColumn("excel.window.end_time.desc", "excel.guide.format.time", "18:00")),
          Map.entry(
              COL_END_STRATEGY,
              requiredColumn(
                  "excel.window.end_strategy.desc",
                  "excel.guide.format.enum",
                  "FINISH_RUNNING",
                  "STOP",
                  "FINISH_RUNNING",
                  "CONTINUE")),
          Map.entry(
              COL_OUT_OF_WINDOW_ACTION,
              requiredColumn(
                  "excel.window.out_of_window_action.desc",
                  "excel.guide.format.enum",
                  "WAIT",
                  "WAIT",
                  "FAIL")),
          Map.entry(
              COL_ALLOW_CROSS_DAY,
              optionalColumn(
                  "excel.window.allow_cross_day.desc",
                  "excel.guide.format.boolean",
                  GUIDE_FALSE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.window.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              COL_DESCRIPTION,
              optionalColumn(
                  "excel.window.description.desc", "excel.guide.format.string", "用于清算批处理的执行窗口")));

  private final BatchWindowMapper batchWindowMapper;
  private final ConfigChangeLogMapper configChangeLogMapper;

  public DefaultConsoleBatchWindowExcelApplicationService(
      ConsoleTenantGuard tenantGuard,
      ConsoleRequestMetadataResolver requestMetadataResolver,
      ExcelImportStore importStore,
      BatchDateTimeSupport dateTimeSupport,
      MessageSource messageSource,
      BatchWindowMapper batchWindowMapper,
      ConfigChangeLogMapper configChangeLogMapper) {
    super(tenantGuard, requestMetadataResolver, importStore, dateTimeSupport, messageSource);
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
    Map<String, Object> existing = batchWindowMapper.selectByUniqueKey(tenantId, row.windowCode());
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
  protected boolean rowExists(WindowRow row, String tenantId) {
    Map<String, Object> existing = batchWindowMapper.selectByUniqueKey(tenantId, row.windowCode());
    return existing != null && !existing.isEmpty();
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
        ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
            .forType("BATCH_WINDOW")
            .withKey(row.windowCode())
            .action(action)
            .summary(
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
                        row.outOfWindowAction())))
            .build());
  }

  @Override
  protected void applyValidations(Sheet sheet) {
    Locale locale = LocaleContextHolder.getLocale();
    addDropdownValidation(
        sheet,
        6,
        END_STRATEGIES.toArray(String[]::new),
        "excel.window.end_strategy.prompt_title",
        "excel.window.end_strategy.prompt_box",
        messageSource,
        locale);
    addDropdownValidation(
        sheet,
        7,
        OUT_OF_WINDOW_ACTIONS.toArray(String[]::new),
        "excel.window.out_of_window_action.prompt_title",
        "excel.window.out_of_window_action.prompt_box",
        messageSource,
        locale);
    for (int col : new int[] {8, 9}) {
      addDropdownValidation(
          sheet,
          col,
          new String[] {"TRUE", "FALSE"},
          "excel.common.enabled.prompt_title",
          "excel.common.enabled.prompt_box",
          messageSource,
          locale);
    }
  }

  @Override
  protected void createReadmeSheet(Workbook workbook) {
    Locale locale = LocaleContextHolder.getLocale();
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_README);
    setReadmeColumnWidth(sheet);
    CellStyle titleStyle = createReadmeTitleStyle(workbook);
    String[] keys = {
      "excel.window.readme.title",
      "excel.window.readme.line1",
      "excel.window.readme.line2",
      "excel.window.readme.line3",
      "excel.window.readme.line4",
      "excel.window.readme.line5"
    };
    for (int i = 0; i < keys.length; i++) {
      Row row = sheet.createRow(i);
      row.createCell(0).setCellValue(messageSource.getMessage(keys[i], null, keys[i], locale));
      if (i == 0) {
        row.getCell(0).setCellStyle(titleStyle);
      }
    }
  }

  @Override
  protected void createDictSheet(Workbook workbook) {
    Sheet sheet = workbook.createSheet(ConsoleExcelStyles.SHEET_NAME_DICT);
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
    setGuideColumnWidths(sheet);
  }

  private static String requireTime(Map<String, String> values, String key, List<String> issues) {
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
