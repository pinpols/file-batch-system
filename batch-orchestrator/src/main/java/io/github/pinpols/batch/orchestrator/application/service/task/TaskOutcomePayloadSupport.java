package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
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
    if (EmptyChecks.isBlank(payloadJson) || EmptyChecks.isBlank(fieldName)) {
      return null;
    }
    try {
      Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
      if (payloadObject instanceof Map<?, ?> payloadMap) {
        @SuppressWarnings("unchecked")
        Object value = ((Map<String, Object>) payloadMap).get(fieldName);
        return EmptyChecks.isNull(value) ? null : String.valueOf(value);
      }
    } catch (IllegalArgumentException exception) {
      SwallowedExceptionLogger.info(
          TaskOutcomePayloadSupport.class, "catch:IllegalArgumentException", exception);

      return null;
    }
    return null;
  }

  static Long payloadLongValue(String payloadJson, String fieldName) {
    if (EmptyChecks.isBlank(payloadJson) || EmptyChecks.isBlank(fieldName)) {
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
    if (EmptyChecks.isNull(candidate)) {
      return null;
    }
    String rawText = String.valueOf(candidate);
    String text = rawText.trim();
    if (EmptyChecks.isEmpty(text)) {
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
      if (EmptyChecks.isNotNull(candidate) && candidate > 0) {
        return candidate;
      }
    }
    return null;
  }

  /** task.payload + command.resultSummary 三源 fallback 找文件 id;给 success outcome 用。 */
  static Long resolveRelatedFileId(JobTaskEntity task, TaskOutcomeCommand command) {
    Long taskFileId = resolveRelatedFileId(task);
    Long resultRelatedFileId = payloadLongValue(
        EmptyChecks.isNull(command) ? null : command.resultSummary(), "relatedFileId");
    Long resultFileId =
        payloadLongValue(EmptyChecks.isNull(command) ? null : command.resultSummary(), "fileId");
    return firstPositiveLong(taskFileId, resultRelatedFileId, resultFileId);
  }

  /** 按任务类型解析 payload 中的文件关联，供 partition/job 推进使用。 */
  static Long resolveRelatedFileId(JobTaskEntity task) {
    if (EmptyChecks.isNotNull(task) && "DISPATCH".equalsIgnoreCase(task.getTaskType())) {
      String payload = task.getTaskPayload();
      if (EmptyChecks.isNotNull(payloadStringValue(payload, "bundleSourceFileId"))) {
        return payloadLongValue(payload, "bundleSourceFileId");
      }
      if (EmptyChecks.isNotNull(payloadStringValue(payload, "fileId"))) {
        return payloadLongValue(payload, "fileId");
      }
    }
    Long relatedFileId =
        payloadLongValue(EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "relatedFileId");
    Long fileId =
        payloadLongValue(EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "fileId");
    Long sourceFileId =
        payloadLongValue(EmptyChecks.isNull(task) ? null : task.getTaskPayload(), "sourceFileId");
    return firstPositiveLong(relatedFileId, fileId, sourceFileId);
  }
}
