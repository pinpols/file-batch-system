package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.JsonUtils;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/** Extracts the parent virtual task reference from a child job parameter snapshot. */
@Slf4j
final class ParentVirtualTaskIdResolver {

  private static final String EFFECTIVE_PARAMS = "effectiveParams";
  private static final String PARENT_VIRTUAL_TASK_ID = "_parentVirtualTaskId";

  private ParentVirtualTaskIdResolver() {}

  static Long resolve(String paramsSnapshot) {
    if (paramsSnapshot == null || paramsSnapshot.isBlank()) {
      return null;
    }
    try {
      Object parsed = JsonUtils.fromJson(paramsSnapshot, Object.class);
      if (!(parsed instanceof Map<?, ?> snapshotMap)) {
        return null;
      }
      Object effectiveParams = snapshotMap.get(EFFECTIVE_PARAMS);
      if (!(effectiveParams instanceof Map<?, ?> effectiveMap)) {
        return null;
      }
      return TaskOutcomePayloadSupport.toPositiveLong(effectiveMap.get(PARENT_VIRTUAL_TASK_ID));
    } catch (IllegalArgumentException badJson) {
      SwallowedExceptionLogger.warn(ParentVirtualTaskIdResolver.class, "catch:bad_json", badJson);
      return null;
    } catch (RuntimeException unexpected) {
      log.error(
          "Parent virtual task id extraction failed unexpectedly: paramsSnapshot length={}",
          paramsSnapshot.length(),
          unexpected);
      throw unexpected;
    }
  }
}
