package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Cron 表达式格式规则:调度类型与字段段数的校验行为")
class CronExpressionFormatRuleTest {

  @Test
  @DisplayName("六段式 Quartz 表达式:校验通过且不产生任何问题")
  void shouldPass_whenQuartz6Field() {
    List<WorkbookIssue> issues =
        CronExpressionFormatRule.validate(List.of(row("CRON", "0 0 2 * * ?", 2)));
    assertThat(issues).isEmpty();
  }

  @Test
  @DisplayName("七段式含年份 Quartz 表达式:校验通过")
  void shouldPass_whenQuartz7FieldWithYear() {
    List<WorkbookIssue> issues =
        CronExpressionFormatRule.validate(List.of(row("CRON", "0 15 10 ? * MON-FRI 2026", 2)));
    assertThat(issues).isEmpty();
  }

  @Test
  @DisplayName("五段式 Linux 表达式:上报字段数不足,并保留列名与原始行号")
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
  @DisplayName("CRON 调度:调度表达式留空时上报必填问题")
  void shouldReport_whenScheduleExprBlankForCron() {
    List<WorkbookIssue> issues = CronExpressionFormatRule.validate(List.of(row("CRON", null, 4)));
    assertThat(issues).singleElement().satisfies(i -> {
      assertThat(i.message()).contains("schedule_expr is required when schedule_type=CRON");
      assertThat(i.rowNo()).isEqualTo(4);
    });
  }

  @Test
  @DisplayName("手工调度:跳过表达式格式校验且不产生问题")
  void shouldSkip_whenScheduleTypeIsManual() {
    List<WorkbookIssue> issues = CronExpressionFormatRule.validate(List.of(row("MANUAL", null, 5)));
    assertThat(issues).isEmpty();
  }

  @Test
  @DisplayName("固定频率调度:数值表达式不按 Cron 段数校验")
  void shouldSkip_whenScheduleTypeIsFixedRateWithNumericValue() {
    List<WorkbookIssue> issues =
        CronExpressionFormatRule.validate(List.of(row("FIXED_RATE", "300", 6)));
    assertThat(issues).isEmpty();
  }

  @Test
  @DisplayName("空输入:无行或行集合为空时均不产生问题")
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
