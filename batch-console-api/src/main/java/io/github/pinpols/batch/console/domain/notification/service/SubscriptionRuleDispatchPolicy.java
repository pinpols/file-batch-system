package io.github.pinpols.batch.console.domain.notification.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.Hashes;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.support.ratelimit.SlidingWindowRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;

/** 通知订阅规则的匹配、去重及限流策略，不承担异步投递和重试。 */
@RequiredArgsConstructor
@Slf4j
final class SubscriptionRuleDispatchPolicy {

  private static final List<String> SEVERITY_KEYS = List.of("severity");
  private static final List<String> JOB_CODE_KEYS = List.of("jobCode", "job_code");
  private static final int MAX_SENDS_PER_CHANNEL_PER_MINUTE = 120;
  private static final int MAX_SENDS_PER_DESTINATION_PER_MINUTE = 60;
  private static final int DEDUP_WINDOW_LIMIT = 1;

  private final SlidingWindowRateLimiter sendRateLimiter;
  private final MeterRegistry meterRegistry;

  String normalizeEventType(String eventType) {
    return eventType == null ? "UNKNOWN" : eventType.trim().toUpperCase(Locale.ROOT);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> asDataMap(Object data) {
    return data instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
  }

  boolean matches(Map<String, Object> rule, String eventType, Map<String, Object> dataMap) {
    return eventTypeMatches(stringValue(rule, "event_types"), eventType)
        && filterMatches(stringValue(rule, "severity_filter"), dataMap, SEVERITY_KEYS)
        && filterMatches(stringValue(rule, "job_code_filter"), dataMap, JOB_CODE_KEYS);
  }

  boolean withinSendRateLimit(
      String tenantId, String channelCode, String channelType, String eventType) {
    try {
      boolean allowed =
          sendRateLimiter.tryAcquire(
              "notify:send:" + tenantId + ":" + channelCode, MAX_SENDS_PER_CHANNEL_PER_MINUTE);
      if (!allowed) {
        recordDrop("channel_ratelimit");
        log.warn(
            "notification send rate limit exceeded; dropping to prevent flooding: channelCode={},"
                + " channelType={}, eventType={}, limitPerMin={}",
            channelCode,
            channelType,
            eventType,
            MAX_SENDS_PER_CHANNEL_PER_MINUTE);
      }
      return allowed;
    } catch (DataAccessException ex) {
      log.warn(
          "notification send rate limiter unavailable — fail-open: channelCode={}, cause={}",
          channelCode,
          ex.getMessage());
      return true;
    }
  }

  boolean isDuplicateWithinWindow(
      String tenantId, String channelCode, String eventType, String payloadJson) {
    String fingerprint =
        payloadJson == null || payloadJson.isEmpty() ? "empty" : Hashes.sha256Short(payloadJson);
    String key =
        "notify:dedup:" + tenantId + ":" + channelCode + ":" + eventType + ":" + fingerprint;
    try {
      boolean firstInWindow = sendRateLimiter.tryAcquire(key, DEDUP_WINDOW_LIMIT);
      if (!firstInWindow) {
        log.info(
            "notification suppressed as duplicate within window: channelCode={}, eventType={}",
            channelCode,
            eventType);
      }
      return !firstInWindow;
    } catch (DataAccessException ex) {
      log.warn(
          "notification dedup rate limiter unavailable — fail-open (not deduped):"
              + " channelCode={}, eventType={}, cause={}",
          channelCode,
          eventType,
          ex.getMessage());
      Counter.builder("notification.dedup.redis_fallback").register(meterRegistry).increment();
      return false;
    }
  }

  boolean withinDestinationRateLimit(
      String tenantId,
      Map<String, Object> rule,
      String channelCode,
      String channelType,
      String eventType) {
    Map<String, Object> config = parseConfig(stringValue(rule, "config_json"));
    String destination =
        firstNonBlank(
            stringValue(config, "url"),
            stringValue(config, "to"),
            stringValue(config, "phoneNumbers"));
    if (destination == null) {
      return true;
    }
    try {
      boolean allowed =
          sendRateLimiter.tryAcquire(
              "notify:dest:" + tenantId + ":" + Hashes.sha256Short(destination),
              MAX_SENDS_PER_DESTINATION_PER_MINUTE);
      if (!allowed) {
        recordDrop("dest_ratelimit");
        log.warn(
            "notification destination rate limit exceeded; dropping: channelCode={},"
                + " channelType={}, eventType={}, limitPerMin={}",
            channelCode,
            channelType,
            eventType,
            MAX_SENDS_PER_DESTINATION_PER_MINUTE);
      }
      return allowed;
    } catch (DataAccessException ex) {
      log.warn(
          "notification destination rate limiter unavailable — fail-open: channelCode={},"
              + " channelType={}, eventType={}, cause={}",
          channelCode,
          channelType,
          eventType,
          ex.getMessage());
      Counter.builder("notification.ratelimit.redis_fallback").register(meterRegistry).increment();
      return true;
    }
  }

  void recordDrop(String reason) {
    meterRegistry.counter("notification.dropped", "reason", reason).increment();
  }

  Map<String, Object> parseConfig(String configJson) {
    if (configJson == null || configJson.isBlank()) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          JsonUtils.fromJson(configJson, new TypeReference<Map<String, Object>>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.info(
          SubscriptionRuleWebhookDispatcher.class, "catch:config_json parse", ex);
      return Map.of();
    }
  }

  private boolean eventTypeMatches(String configuredEventTypes, String eventType) {
    if (configuredEventTypes == null || configuredEventTypes.isBlank()) {
      return false;
    }
    if ("*".equals(configuredEventTypes.trim())) {
      return true;
    }
    return splitUpper(configuredEventTypes).contains(eventType);
  }

  private boolean filterMatches(String filter, Map<String, Object> dataMap, List<String> dataKeys) {
    if (filter == null || filter.isBlank()) {
      return true;
    }
    Collection<String> allowed = splitUpper(filter);
    if (allowed.isEmpty()) {
      return true;
    }
    String actual = firstNonNull(dataMap, dataKeys);
    return actual != null && allowed.contains(actual.toUpperCase(Locale.ROOT));
  }

  private String firstNonNull(Map<String, Object> dataMap, List<String> keys) {
    for (String key : keys) {
      Object value = dataMap.get(key);
      if (value != null) {
        return value.toString();
      }
    }
    return null;
  }

  private List<String> splitUpper(String csv) {
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .map(value -> value.toUpperCase(Locale.ROOT))
        .toList();
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static String stringValue(Map<String, Object> map, String key) {
    Object value = map.get(key);
    return value == null ? null : value.toString();
  }
}
