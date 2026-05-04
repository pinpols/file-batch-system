package com.example.batch.orchestrator.application.service.task;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import java.util.Map;

/**
 * P2-4 god-class-decomposition extract: 从 {@link DefaultTaskOutcomeService} 抽出的纯函数 payload 解析助手。
 *
 * <p>覆盖原 service 内 8 个互相依赖的 helper:
 *
 * <ul>
 *   <li>{@code payloadStringValue / payloadLongValue} — JSONB 字段提取
 *   <li>{@code toPositiveLong / firstPositiveLong} — 数值归一与首个非空选取
 *   <li>{@code resolveRelatedFileId} 两个重载 — task payload + result summary 双源 fallback 找文件 id
 * </ul>
 *
 * <p>无任何 Spring/DB/状态机依赖,纯静态工具类。提取目的是把 service 从 926 LOC 降到目标区间(600-800), 让核心方法 {@code
 * applyTaskOutcome / advancePartitionAndInstance / advanceDagNodes} 不被这堆样板淹没。
 */
final class TaskOutcomePayloadSupport {

  private TaskOutcomePayloadSupport() {}

  static String payloadStringValue(String payloadJson, String fieldName) {
    if (payloadJson == null || payloadJson.isBlank() || fieldName == null || fieldName.isBlank()) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        @SuppressWarnings("unchecked")
        Object value = ((Map<String, Object>) payloadMap).get(fieldName);
        return value == null ? null : String.valueOf(value);
      }
    } catch (IllegalArgumentException exception) {
      SwallowedExceptionLogger.info(
          TaskOutcomePayloadSupport.class, "catch:IllegalArgumentException", exception);

      return null;
    }
    return null;
  }

  static Long payloadLongValue(String payloadJson, String fieldName) {
    if (payloadJson == null || payloadJson.isBlank() || fieldName == null || fieldName.isBlank()) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        @SuppressWarnings("unchecked")
        Object value = ((Map<String, Object>) payloadMap).get(fieldName);
        return toPositiveLong(value);
      }
    } catch (IllegalArgumentException exception) {
      SwallowedExceptionLogger.info(
          TaskOutcomePayloadSupport.class, "catch:IllegalArgumentException", exception);

      return null;
    }
    return null;
  }

  static Long toPositiveLong(Object candidate) {
    if (candidate instanceof Number number) {
      long value = number.longValue();
      return value > 0 ? value : null;
    }
    if (candidate == null) {
      return null;
    }
    String text = String.valueOf(candidate).trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      long value = Long.parseLong(text);
      return value > 0 ? value : null;
    } catch (NumberFormatException ignored) {
      SwallowedExceptionLogger.info(
          TaskOutcomePayloadSupport.class, "catch:NumberFormatException", ignored);

      return null;
    }
  }

  static Long firstPositiveLong(Long... candidates) {
    for (Long candidate : candidates) {
      if (candidate != null && candidate > 0) {
        return candidate;
      }
    }
    return null;
  }

  /** task.payload + command.resultSummary 三源 fallback 找文件 id;给 success outcome 用。 */
  static Long resolveRelatedFileId(JobTaskEntity task, TaskOutcomeCommand command) {
    return firstPositiveLong(
        resolveRelatedFileId(task),
        payloadLongValue(command == null ? null : command.resultSummary(), "relatedFileId"),
        payloadLongValue(command == null ? null : command.resultSummary(), "fileId"));
  }

  /** 仅 task.payload 三键 fallback;给 partition/job 推进时用(无 outcome 上下文)。 */
  static Long resolveRelatedFileId(JobTaskEntity task) {
    return firstPositiveLong(
        payloadLongValue(task == null ? null : task.getTaskPayload(), "relatedFileId"),
        payloadLongValue(task == null ? null : task.getTaskPayload(), "fileId"),
        payloadLongValue(task == null ? null : task.getTaskPayload(), "sourceFileId"));
  }
}
