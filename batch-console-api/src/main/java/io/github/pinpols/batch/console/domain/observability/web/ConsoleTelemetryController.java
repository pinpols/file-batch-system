package io.github.pinpols.batch.console.domain.observability.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.observability.application.ConsoleTelemetryService;
import io.github.pinpols.batch.console.domain.observability.application.contract.request.FrontendTelemetryRequest;
import io.github.pinpols.batch.console.domain.observability.application.contract.request.FrontendTelemetryRequest.Event;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
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
public class ConsoleTelemetryController {

  private static final int MAX_PROPS_BYTES_PER_EVENT = 8 * 1024;
  private static final int MAX_PROPS_KEYS_PER_EVENT = 50;
  private static final int MAX_PROPS_DEPTH = 5;
  private static final int MAX_PROPS_LIST_ITEMS = 100;

  private final ConsoleResponseFactory responseFactory;
  private final ConsoleTelemetryService telemetryService;

  @PostMapping("/events")
  public CommonResponse<Void> receiveEvents(@RequestBody @Valid FrontendTelemetryRequest request) {
    validateProps(request);
    telemetryService.receive(request);
    return responseFactory.success(null);
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
      String serializedProps = JsonUtils.toJson(props);
      byte[] serializedPropsBytes = serializedProps.getBytes(StandardCharsets.UTF_8);
      int bytes = serializedPropsBytes.length;
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
