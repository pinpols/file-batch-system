package io.github.pinpols.batch.console.domain.file.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.Texts;

/** 校验已提供的映射，同时允许尚未填写完整的模板骨架。 */
public final class FixedWidthMappingValidation {
  private FixedWidthMappingValidation() {}

  public static void validate(
      ObjectMapper objectMapper, Boolean enabled, String type, String format, String mappingsJson) {
    if (!Boolean.TRUE.equals(enabled)
        || !"IMPORT".equalsIgnoreCase(type)
        || !"FIXED_WIDTH".equalsIgnoreCase(format)
        || !Texts.hasText(mappingsJson)) {
      return;
    }
    JsonNode mappings;
    try {
      mappings = objectMapper.readTree(mappingsJson);
    } catch (JsonProcessingException e) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "invalid FIXED_WIDTH field mappings JSON");
    }
    if (EmptyChecks.isNotNull(mappings)
        && (mappings.isNull()
            || (mappings.isObject()
                && mappings.isEmpty()))) { // empty-check: allow - Jackson 对象节点判空
      return;
    }
    Guard.require(
        EmptyChecks.isNotNull(mappings)
            && mappings.isArray()
            && !mappings.isEmpty(), // empty-check: allow - Jackson 数组节点判空
        "FIXED_WIDTH field mappings must be a non-empty array");
    for (JsonNode entry : mappings) {
      JsonNode target = entry.path("target");
      JsonNode start = entry.path("start");
      JsonNode length = entry.path("length");
      Guard.require(
          target.isTextual()
              && Texts.hasText(target.textValue())
              && start.isIntegralNumber()
              && start.canConvertToInt()
              && start.intValue() >= 0
              && length.isIntegralNumber()
              && length.canConvertToInt()
              && length.intValue() > 0,
          "FIXED_WIDTH field mappings require non-blank target, non-negative start and positive length");
    }
  }
}
