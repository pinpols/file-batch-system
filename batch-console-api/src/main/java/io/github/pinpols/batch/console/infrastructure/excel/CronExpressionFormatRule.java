package io.github.pinpols.batch.console.infrastructure.excel;

import io.github.pinpols.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A4 fixture 工程化校验规则:job_definition.schedule_expr 在 schedule_type=CRON 时必须是 Quartz 6 或 7 字段表达式 (sec
 * min hour day-of-month month day-of-week [year]),不接受 Linux 5 字段。
 *
 * <p>背景:sim-e2e 第 2 波发现 fixture 写成 '0 2 * * *' (Linux 5-field),trigger 模块底层使用
 * org.springframework.scheduling.support.CronExpression 解析时会 fail,job_instance 永远不被排期。
 *
 * <p>纯函数实现,无 Spring 依赖。
 */
public final class CronExpressionFormatRule {

  public static final String SHEET = ConfigPackageExcelValidator.JOB_SHEET;
  public static final String SCHEDULE_TYPE_CRON = "CRON";

  private CronExpressionFormatRule() {}

  /**
   * @param rows 已 normalize 的 job_definition 行
   * @return 违反场景的 issue 列表
   */
  public static List<WorkbookIssue> validate(List<Map<String, Object>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    if (rows == null || rows.isEmpty()) {
      return issues;
    }
    for (Map<String, Object> row : rows) {
      String scheduleType = upper(asString(row.get(ConfigPackageExcelValidator.COL_SCHEDULE_TYPE)));
      String expr = asString(row.get(ConfigPackageExcelValidator.COL_SCHEDULE_EXPR));
      int rowNo = parseRowNo(row.get("__excel_row_no"));
      if (!SCHEDULE_TYPE_CRON.equals(scheduleType)) {
        continue;
      }
      if (isBlank(expr)) {
        issues.add(
            new WorkbookIssue(
                SHEET,
                rowNo,
                ConfigPackageExcelValidator.COL_SCHEDULE_EXPR,
                "schedule_expr is required when schedule_type=CRON"));
        continue;
      }
      String[] parts = expr.trim().split("\\s+");
      if (parts.length != 6 && parts.length != 7) {
        issues.add(
            new WorkbookIssue(
                SHEET,
                rowNo,
                ConfigPackageExcelValidator.COL_SCHEDULE_EXPR,
                "schedule_expr must be a Quartz 6 or 7-field cron (sec min hour dom mon dow"
                    + " [year]); found "
                    + parts.length
                    + " fields: '"
                    + expr
                    + "'"));
      }
    }
    return issues;
  }

  private static String asString(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static int parseRowNo(Object o) {
    if (o == null) {
      return 0;
    }
    if (o instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(o.toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
