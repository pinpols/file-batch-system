package io.github.pinpols.batch.console.infrastructure.excel;

import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.normalize;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.optionalBoolean;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.optionalEnum;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.optionalText;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.requireText;
import static io.github.pinpols.batch.console.infrastructure.excel.SingleSheetExcelCellParsers.resolveTenantField;

import io.github.pinpols.batch.common.enums.BatchWindowEndStrategy;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.OutOfWindowAction;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.job.param.BatchWindowUpsertParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Builder;

/** batch_window Excel 行共用的解析与新增或更新辅助逻辑。 */
public final class BatchWindowExcelRowParser {

  /** batch_window.window_code VARCHAR(128)（V3__create_config_tables.sql）。 */
  private static final int WINDOW_CODE_MAX_LENGTH = 128;

  /** batch_window.window_name VARCHAR(256)（V3__create_config_tables.sql）。 */
  private static final int WINDOW_NAME_MAX_LENGTH = 256;

  /** batch_window.timezone VARCHAR(64)（V3__create_config_tables.sql）。 */
  private static final int TIMEZONE_MAX_LENGTH = 64;

  /** batch_window.end_strategy VARCHAR(32)（V3__create_config_tables.sql）。 */
  private static final int END_STRATEGY_MAX_LENGTH = 32;

  /** batch_window.out_of_window_action VARCHAR(32)（V3__create_config_tables.sql）。 */
  private static final int OUT_OF_WINDOW_ACTION_MAX_LENGTH = 32;

  /** batch_window.description VARCHAR(512)（V3__create_config_tables.sql）。 */
  private static final int DESCRIPTION_MAX_LENGTH = 512;

  public static final String SHEET_NAME = "batch_window";

  private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2})?$");
  private static final Set<String> END_STRATEGIES = DictEnum.codes(BatchWindowEndStrategy.class);
  private static final Set<String> OUT_OF_WINDOW_ACTIONS = DictEnum.codes(OutOfWindowAction.class);

  private BatchWindowExcelRowParser() {}

  public static WindowRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    WindowRow.WindowRowBuilder rowBuilder =
        WindowRow.builder().rowNo(rowNo).tenantId(effectiveTenant);
    String windowCodeText = requireText(values, "window_code", WINDOW_CODE_MAX_LENGTH, issues);
    String windowCode = CodeNormalizer.toConfigFormOrNull(windowCodeText);
    rowBuilder.windowCode(windowCode);

    String windowName = requireText(values, "window_name", WINDOW_NAME_MAX_LENGTH, issues);
    rowBuilder.windowName(windowName);
    String timezone = requireText(values, "timezone", TIMEZONE_MAX_LENGTH, issues);
    rowBuilder.timezone(timezone);
    String startTime = requireTime(values, "start_time", issues);
    rowBuilder.startTime(startTime);
    String endTime = requireTime(values, "end_time", issues);
    rowBuilder.endTime(endTime);
    String endStrategy = optionalEnum(
        values, "end_strategy", END_STRATEGIES, END_STRATEGY_MAX_LENGTH, "FINISH_RUNNING", issues);
    rowBuilder.endStrategy(endStrategy);
    String outOfWindowAction = optionalEnum(
        values,
        "out_of_window_action",
        OUT_OF_WINDOW_ACTIONS,
        OUT_OF_WINDOW_ACTION_MAX_LENGTH,
        "WAIT",
        issues);
    rowBuilder.outOfWindowAction(outOfWindowAction);
    Boolean allowCrossDay = optionalBoolean(values, "allow_cross_day", false, issues);
    rowBuilder.allowCrossDay(allowCrossDay);
    Boolean enabled = optionalBoolean(values, "enabled", true, issues);
    rowBuilder.enabled(enabled);
    String description = optionalText(values, "description", DESCRIPTION_MAX_LENGTH, issues);
    rowBuilder.description(description);
    return rowBuilder.build();
  }

  public static BatchWindowUpsertParam toUpsertParam(WindowRow row) {
    BatchWindowUpsertParam param = new BatchWindowUpsertParam();
    param.setTenantId(row.tenantId());
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
    return param;
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
  public record WindowRow(
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
