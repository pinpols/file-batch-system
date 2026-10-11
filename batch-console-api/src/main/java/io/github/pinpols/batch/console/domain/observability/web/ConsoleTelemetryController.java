package io.github.pinpols.batch.console.domain.observability.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.observability.application.contract.request.FrontendTelemetryRequest;
import io.github.pinpols.batch.console.domain.observability.application.contract.request.FrontendTelemetryRequest.Event;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 前端遥测收集：接收前端埋点，通过 slf4j + MDC 输出结构化日志。 */
@RestController
@Validated
@RequestMapping("/api/console/telemetry")
@RequiredArgsConstructor
@Slf4j
public class ConsoleTelemetryController {

  private static final int MAX_PROPS_BYTES_PER_EVENT = 8 * 1024;
  private static final int MAX_PROPS_KEYS_PER_EVENT = 50;
  private static final int MAX_PROPS_DEPTH = 5;
  private static final int MAX_PROPS_LIST_ITEMS = 100;

  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/events")
  public CommonResponse<Void> receiveEvents(@RequestBody @Valid FrontendTelemetryRequest request) {
    validateProps(request);
    for (Event event : request.events()) {
      // 保留聚合诊断信息，不输出调用方控制的事件文本或页面值。
      int propsKeys = event.props() == null ? 0 : event.props().size();
      if ("error".equals(event.type())) {
        log.error(
            "[frontend:error] telemetry event received nameLength={} pagePresent={} propsKeys={}",
            event.name().length(),
            event.page() != null && !event.page().isBlank(),
            propsKeys);
      } else {
        log.info(
            "[frontend] telemetry event received type={} nameLength={} pagePresent={} propsKeys={}",
            safeEventType(event.type()),
            event.name().length(),
            event.page() != null && !event.page().isBlank(),
            propsKeys);
      }
    }
    return responseFactory.success(null);
  }

  private static String safeEventType(String eventType) {
    return switch (eventType) {
      case "route", "click", "api", "error" -> eventType;
      default -> "custom";
    };
  }

  private void validateProps(FrontendTelemetryRequest request) {
    for (Event event : request.events()) {
      Map<String, Object> props = event.props();
      if (props == null || props.isEmpty()) {
        continue;
      }
      if (props.size() > MAX_PROPS_KEYS_PER_EVENT) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.telemetry.props_too_large");
      }
      if (depth(props, 1) > MAX_PROPS_DEPTH) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.telemetry.props_too_deep");
      }
      int bytes = JsonUtils.toJson(props).getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_PROPS_BYTES_PER_EVENT) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.telemetry.props_too_large");
      }
    }
  }

  private int depth(Object value, int currentDepth) {
    if (value instanceof Map<?, ?> map) {
      int max = currentDepth;
      for (Object child : map.values()) {
        max = Math.max(max, depth(child, currentDepth + 1));
      }
      return max;
    }
    if (value instanceof List<?> list) {
      if (list.size() > MAX_PROPS_LIST_ITEMS) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.telemetry.props_too_large");
      }
      int max = currentDepth;
      for (Object child : list) {
        max = Math.max(max, depth(child, currentDepth + 1));
      }
      return max;
    }
    return currentDepth;
  }
}
