package io.github.pinpols.batch.console.domain.audit.infrastructure.ai;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.contract.request.auth.AiPageContextRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 限制并清洗由调用方提供、将发送给外部模型的 JSON 上下文。 */
final class ConsoleAiContextSanitizer {

  private static final int HARD_MAX_CONTEXT_CHARS = 8000;
  private static final Set<String> V1_FIELDS = Set.of("pageType", "objectType", "objectId");

  private ConsoleAiContextSanitizer() {}

  static String sanitize(
      String contextVersion,
      Map<String, Object> context,
      AiPageContextRequest pageContext,
      int maxChars) {
    if (!"v1".equals(contextVersion)) {
      throw invalid("unsupported context version");
    }
    if (EmptyChecks.isNotEmpty(context) && EmptyChecks.isNotNull(pageContext)) {
      throw invalid("context and pageContext cannot both be provided");
    }
    Map<String, Object> normalized =
        EmptyChecks.isNull(pageContext) ? context : pageContextMap(pageContext);
    if (EmptyChecks.isEmpty(normalized)) {
      return "{}";
    }
    if (maxChars <= 0) {
      throw invalid("context limit must be positive");
    }
    Map<String, String> safeContext = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : normalized.entrySet()) {
      String key = entry.getKey();
      if (!V1_FIELDS.contains(key) || !(entry.getValue() instanceof String value)) {
        throw invalid("v1 context accepts only pageType, objectType and objectId strings");
      }
      int maxLength = "objectId".equals(key) ? 128 : 64;
      String sanitized = ConsoleTextSanitizer.safeInput(value);
      if (sanitized.length() > maxLength) {
        throw invalid("v1 context field exceeds maximum length");
      }
      safeContext.put(key, sanitized);
    }
    String json = JsonUtils.toJson(safeContext);
    if (json.length() > Math.min(maxChars, HARD_MAX_CONTEXT_CHARS)) {
      throw invalid("context exceeds maximum serialized length");
    }
    return json;
  }

  private static Map<String, Object> pageContextMap(AiPageContextRequest pageContext) {
    Map<String, Object> context = new LinkedHashMap<>();
    addIfPresent(context, "pageType", pageContext.getPageType());
    addIfPresent(context, "objectType", pageContext.getObjectType());
    addIfPresent(context, "objectId", pageContext.getObjectId());
    return context;
  }

  private static void addIfPresent(Map<String, Object> context, String key, String value) {
    if (EmptyChecks.isNotNull(value)) {
      context.put(key, value);
    }
  }

  private static BizException invalid(String detail) {
    return BizException.of(
        ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.detailKey(), detail);
  }
}
