package com.example.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CronExpressionFormatRuleTest {

  @Test
  void shouldPass_whenQuartz6Field() {
    List<WorkbookIssue> issues =
        CronExpressionFormatRule.validate(List.of(row("CRON", "0 0 2 * * ?", 2)));
    assertThat(issues).isEmpty();
  }

  @Test
  void shouldPass_whenQuartz7FieldWithYear() {
    List<WorkbookIssue> issues =
        CronExpressionFormatRule.validate(List.of(row("CRON", "0 15 10 ? * MON-FRI 2026", 2)));
    assertThat(issues).isEmpty();
  }

  @Test
  void shouldReport_whenLinux5Field() {
    List<WorkbookIssue> issues =
        CronExpressionFormatRule.validate(List.of(row("CRON", "0 2 * * *", 3)));

    assertThat(issues).hasSize(1);
    WorkbookIssue i = issues.get(0);
    assertThat(i.columnName()).isEqualTo("schedule_expr");
    assertThat(i.rowNo()).isEqualTo(3);
    assertThat(i.message())
        .contains("Quartz 6 or 7-field")
        .contains("found 5 fields")
        .contains("0 2 * * *");
  }

  @Test
  void shouldReport_whenScheduleExprBlankForCron() {
    List<WorkbookIssue> issues = CronExpressionFormatRule.validate(List.of(row("CRON", null, 4)));
    assertThat(issues)
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.message()).contains("schedule_expr is required when schedule_type=CRON");
              assertThat(i.rowNo()).isEqualTo(4);
            });
  }

  @Test
  void shouldSkip_whenScheduleTypeIsManual() {
    List<WorkbookIssue> issues = CronExpressionFormatRule.validate(List.of(row("MANUAL", null, 5)));
    assertThat(issues).isEmpty();
  }

  @Test
  void shouldSkip_whenScheduleTypeIsFixedRateWithNumericValue() {
    List<WorkbookIssue> issues =
        CronExpressionFormatRule.validate(List.of(row("FIXED_RATE", "300", 6)));
    assertThat(issues).isEmpty();
  }

  @Test
  void shouldHandleEmpty() {
    assertThat(CronExpressionFormatRule.validate(null)).isEmpty();
    assertThat(CronExpressionFormatRule.validate(List.of())).isEmpty();
  }

  private static Map<String, Object> row(String scheduleType, String expr, int rowNo) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put(ConfigPackageExcelValidator.COL_SCHEDULE_TYPE, scheduleType);
    m.put(ConfigPackageExcelValidator.COL_SCHEDULE_EXPR, expr);
    m.put("__excel_row_no", rowNo);
    return m;
  }
}
