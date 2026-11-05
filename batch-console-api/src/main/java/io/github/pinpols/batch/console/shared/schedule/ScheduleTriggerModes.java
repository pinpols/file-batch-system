package io.github.pinpols.batch.console.shared.schedule;

import io.github.pinpols.batch.common.utils.EmptyChecks;

/**
 * 调度类型与触发模式的兼容规则。
 *
 * <p>与 {@code WorkflowScheduleUpdateRequest} 的保存校验保持同一张表：CRON / FIXED_RATE 只接受
 * SCHEDULED、MIXED；EVENT 只接受 EVENT、MIXED；MANUAL（及缺省）只接受 MANUAL、API、MIXED。调度器只注册
 * SCHEDULED / MIXED 的工作流，因此“CRON + MANUAL”会显示为定时却永远不触发，生成或保存时必须避免。
 */
public final class ScheduleTriggerModes {

  private ScheduleTriggerModes() {}

  /** 调度类型对应的默认触发模式。 */
  public static String defaultFor(String scheduleType) {
    if (EmptyChecks.isNull(scheduleType)) {
      return "MANUAL";
    }
    return switch (scheduleType) {
      case "CRON", "FIXED_RATE" -> "SCHEDULED";
      case "EVENT" -> "EVENT";
      default -> "MANUAL";
    };
  }

  /** 触发模式是否与调度类型兼容；触发模式为空视为兼容（由 {@link #resolve} 补默认值）。 */
  public static boolean isCompatible(String scheduleType, String triggerMode) {
    if (EmptyChecks.isBlank(triggerMode)) {
      return true;
    }
    if ("MIXED".equals(triggerMode)) {
      return true;
    }
    String type = EmptyChecks.isNull(scheduleType) ? "MANUAL" : scheduleType;
    return switch (type) {
      case "CRON", "FIXED_RATE" -> "SCHEDULED".equals(triggerMode);
      case "EVENT" -> "EVENT".equals(triggerMode);
      default -> "MANUAL".equals(triggerMode) || "API".equals(triggerMode);
    };
  }

  /** 兼容时保留原值，空值或不兼容时取调度类型的默认触发模式。 */
  public static String resolve(String scheduleType, String triggerMode) {
    if (EmptyChecks.isNotBlank(triggerMode) && isCompatible(scheduleType, triggerMode)) {
      return triggerMode;
    }
    return defaultFor(scheduleType);
  }
}
