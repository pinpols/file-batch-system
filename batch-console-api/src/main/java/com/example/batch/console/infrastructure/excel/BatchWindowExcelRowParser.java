package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.normalize;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalBoolean;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.optionalText;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireEnum;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.requireText;
import static com.example.batch.console.infrastructure.excel.AbstractSingleSheetExcelService.resolveTenantField;

import com.example.batch.common.enums.BatchWindowEndStrategy;
import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.OutOfWindowAction;
import com.example.batch.common.utils.Texts;
import com.example.batch.console.domain.job.param.BatchWindowUpsertParam;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.Builder;

/** Shared parser/upsert helper for batch_window Excel rows. */
public final class BatchWindowExcelRowParser {

  public static final String SHEET_NAME = "batch_window";

  private static final Pattern TIME_PATTERN = Pattern.compile("^\\d{2}:\\d{2}(:\\d{2})?$");
  private static final Set<String> END_STRATEGIES = DictEnum.codes(BatchWindowEndStrategy.class);
  private static final Set<String> OUT_OF_WINDOW_ACTIONS = DictEnum.codes(OutOfWindowAction.class);

  private BatchWindowExcelRowParser() {}

  public static WindowRow parseRow(
      String tenantId, int rowNo, Map<String, String> values, List<String> issues) {
    String effectiveTenant = resolveTenantField(values, tenantId, issues);
    WindowRow row =
        WindowRow.builder()
            .rowNo(rowNo)
            .tenantId(effectiveTenant)
            .windowCode(requireText(values, "window_code", 128, issues))
            .windowName(requireText(values, "window_name", 256, issues))
            .timezone(requireText(values, "timezone", 64, issues))
            .startTime(requireTime(values, "start_time", issues))
            .endTime(requireTime(values, "end_time", issues))
            .endStrategy(
                optionalEnum(values, "end_strategy", END_STRATEGIES, 32, "FINISH_RUNNING", issues))
            .outOfWindowAction(
                optionalEnum(
                    values, "out_of_window_action", OUT_OF_WINDOW_ACTIONS, 32, "WAIT", issues))
            .allowCrossDay(optionalBoolean(values, "allow_cross_day", false, issues))
            .enabled(optionalBoolean(values, "enabled", true, issues))
            .description(optionalText(values, "description", 512, issues))
            .build();
    return row;
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
