package io.github.pinpols.batch.common.utils;

import java.util.Locale;

/**
 * {@code alert_event} 字段 → Alertmanager label 的映射规则（emit 直连 / silence 桥接共用）。
 *
 * <p>迁移方案 §4：AM 单实例全局，租户维度只能靠 label 携带。此工具集中两处易漂移的映射：
 *
 * <ul>
 *   <li><b>severity 词形</b>：fbs 侧枚举是大写 {@code INFO/WARN/ERROR/CRITICAL}，AM 模板 route/inhibit 用小写
 *       {@code info/warning/error/critical}（{@code alertmanager-batch-template.yml}）。{@code
 *       WARN→warning} 是词形差异，必须显式映射，不能简单 toLowerCase。
 *   <li><b>alert_group / team</b>：由 {@code alert_type} 关键字推导，对齐 AM route matcher
 *       （dispatch/sla/freshness/capacity）。这是 §11 待核实项的 v1 收敛实现（关键字派生，不引额外映射表）。
 * </ul>
 *
 * <p>基数守则（§4/§8）：只有低基数枚举（alertname/tenant/severity/alert_group/team）可进 {@code group_by}； {@code
 * resource_key/trace_id} 属高基数，一律进 annotation，本工具不产出它们的 label。
 */
public final class AlertLabels {

  /** AM 分组用的告警类别枚举（对齐 route matcher 与 inhibit equal 集合）。 */
  public static final String GROUP_DISPATCH = "dispatch";

  public static final String GROUP_SLA = "sla";
  public static final String GROUP_FRESHNESS = "freshness";
  public static final String GROUP_CAPACITY = "capacity";
  public static final String GROUP_DEFAULT = "ops";

  private AlertLabels() {}

  /** fbs severity 枚举 → AM severity 小写词形。未知值兜底 lowercase（并把 {@code warn} 归一到 {@code warning}）。 */
  public static String amSeverity(String severity) {
    if (severity == null || severity.isBlank()) {
      return "warning";
    }
    return switch (severity.trim().toUpperCase(Locale.ROOT)) {
      case "INFO" -> "info";
      case "WARN", "WARNING" -> "warning";
      case "ERROR" -> "error";
      case "CRITICAL" -> "critical";
      default -> {
        String lower = severity.trim().toLowerCase(Locale.ROOT);
        yield "warn".equals(lower) ? "warning" : lower;
      }
    };
  }

  /** alert_type 关键字 → AM alert_group（route 分类主键之一）。 */
  public static String alertGroup(String alertType) {
    if (alertType == null || alertType.isBlank()) {
      return GROUP_DEFAULT;
    }
    String lower = alertType.toLowerCase(Locale.ROOT);
    if (lower.contains("dispatch")) {
      return GROUP_DISPATCH;
    }
    if (lower.contains("sla")) {
      return GROUP_SLA;
    }
    if (lower.contains("freshness") || lower.contains("asset") || lower.contains("stale")) {
      return GROUP_FRESHNESS;
    }
    if (lower.contains("capacity") || lower.contains("drain") || lower.contains("backpressure")) {
      return GROUP_CAPACITY;
    }
    return GROUP_DEFAULT;
  }

  /** 由 alert_group 派生 team（v1 收敛：group→team 一一对应，供 route 二次分流/展示）。 */
  public static String team(String alertType) {
    return switch (alertGroup(alertType)) {
      case GROUP_DISPATCH -> "batch-dispatch";
      case GROUP_SLA -> "batch-sla";
      case GROUP_FRESHNESS -> "batch-data";
      case GROUP_CAPACITY -> "batch-sre";
      default -> "batch-ops";
    };
  }
}
