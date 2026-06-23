package io.github.pinpols.batch.orchestrator.application.service.sensor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.enums.SensorType;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.SensorProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * HTTP_POLL sensor：周期 GET/POST 外部 URL，按 matchExpr 判断响应是否达成预期。
 *
 * <p>sensor_spec：
 *
 * <pre>{@code
 * {
 *   "url":        "https://...",     // 必填
 *   "method":     "GET",             // 可选，默认 GET（仅允许 GET/POST/HEAD）
 *   "headersJson":"{\"k\":\"v\"}",   // 可选，JSON object
 *   "body":       "{...}",           // 可选，POST body
 *   "matchExpr":  "$.status==READY"  // 受限语法：仅支持 $.<jsonPointer>==<literal>
 * }
 * }</pre>
 *
 * <p>matchExpr 语法（保持极简，避免引入完整 JSONPath/SpEL）：
 *
 * <ul>
 *   <li>{@code $.field.path == value} — JsonPointer 取值后字面量等值
 *   <li>{@code status==2xx}（特殊形式）—— 仅看 HTTP 状态码
 * </ul>
 *
 * <p>命中返 {@code responseStatus / responseBody}。
 */
@Slf4j
@Component
public class HttpPollSensorPolicy implements SensorPolicy {

  private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "HEAD");

  private final SensorProperties props;
  private final ObjectMapper objectMapper;
  private final RestClient restClient;

  public HttpPollSensorPolicy(SensorProperties props, ObjectMapper objectMapper) {
    this.props = props;
    this.objectMapper = objectMapper;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) props.getHttpRequestTimeout().toMillis());
    factory.setReadTimeout((int) props.getHttpRequestTimeout().toMillis());
    this.restClient = RestClient.builder().requestFactory(factory).build();
  }

  @Override
  public SensorType type() {
    return SensorType.HTTP_POLL;
  }

  @Override
  public SensorProbeResult probe(SensorContext ctx) {
    Map<String, Object> spec = ctx.sensorSpec();
    String url = SensorSpecs.string(spec, "url");
    if (!Texts.hasText(url)) {
      return SensorProbeResult.error(
          "error.workflow.sensor_spec_invalid", List.of("HTTP_POLL", "url required"));
    }
    String method = upperOrDefault(SensorSpecs.string(spec, "method"), "GET");
    if (!ALLOWED_METHODS.contains(method)) {
      return SensorProbeResult.error(
          "error.workflow.sensor_spec_invalid",
          List.of("HTTP_POLL", "method must be one of " + ALLOWED_METHODS));
    }
    String matchExpr = SensorSpecs.string(spec, "matchExpr");
    if (!Texts.hasText(matchExpr)) {
      return SensorProbeResult.error(
          "error.workflow.sensor_spec_invalid", List.of("HTTP_POLL", "matchExpr required"));
    }

    Duration timeRemaining = ctx.timeRemaining();
    if (timeRemaining != null && timeRemaining.compareTo(props.getHttpRequestTimeout()) < 0) {
      // 接近 timeout，再发请求只会浪费一次重试机会
      return SensorProbeResult.notYet();
    }

    try {
      ResponseEntity<String> resp = invoke(url, method, spec);
      int status = resp.getStatusCode().value();
      String body = resp.getBody() == null ? "" : resp.getBody();
      if (evaluateMatch(matchExpr, status, body)) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("responseStatus", status);
        output.put("responseBody", body);
        return SensorProbeResult.matched(output);
      }
      return SensorProbeResult.notYet();
    } catch (ResourceAccessException e) {
      log.debug("HTTP_POLL timeout url={} err={}", url, e.getMessage());
      return SensorProbeResult.notYet();
    } catch (Exception e) {
      log.warn("HTTP_POLL probe error url={} err={}", url, e.toString());
      return SensorProbeResult.error(
          "error.workflow.sensor_probe_failed", List.of("HTTP_POLL", e.getMessage()));
    }
  }

  private ResponseEntity<String> invoke(String url, String method, Map<String, Object> spec)
      throws JsonProcessingException {
    HttpHeaders headers = parseHeaders(SensorSpecs.string(spec, "headersJson"));
    RestClient.RequestBodySpec req =
        restClient.method(HttpMethod.valueOf(method)).uri(url).headers(h -> h.addAll(headers));
    String body = SensorSpecs.string(spec, "body");
    if (Texts.hasText(body) && ("POST".equals(method))) {
      if (headers.getContentType() == null) {
        req.contentType(MediaType.APPLICATION_JSON);
      }
      return req.body(body).retrieve().toEntity(String.class);
    }
    return req.retrieve().toEntity(String.class);
  }

  private HttpHeaders parseHeaders(String headersJson) throws JsonProcessingException {
    HttpHeaders headers = new HttpHeaders();
    if (!Texts.hasText(headersJson)) {
      return headers;
    }
    JsonNode node = objectMapper.readTree(headersJson);
    if (node != null && node.isObject()) {
      for (Map.Entry<String, JsonNode> entry : node.properties()) {
        headers.add(entry.getKey(), entry.getValue().asText());
      }
    }
    return headers;
  }

  /** matchExpr 解析：仅支持 {@code status==2xx} 或 {@code $.<jsonPointer>==<literal>}。 */
  boolean evaluateMatch(String matchExpr, int status, String body) {
    String expr = matchExpr.trim();
    int eq = expr.indexOf("==");
    if (eq < 0) {
      throw new IllegalArgumentException("matchExpr must contain '==' operator");
    }
    String lhs = expr.substring(0, eq).trim();
    String rhs = stripQuotes(expr.substring(eq + 2).trim());

    if ("status".equals(lhs)) {
      if (rhs.endsWith("xx")) {
        int prefix = Integer.parseInt(rhs.substring(0, 1));
        return status / 100 == prefix;
      }
      return Integer.toString(status).equals(rhs);
    }
    if (lhs.startsWith("$.")) {
      String pointer = "/" + lhs.substring(2).replace('.', '/');
      try {
        JsonNode root = objectMapper.readTree(body);
        JsonNode found = root.at(pointer);
        if (found.isMissingNode() || found.isNull()) {
          return false;
        }
        return rhs.equals(found.asText());
      } catch (JsonProcessingException e) {
        log.debug("HTTP_POLL body not JSON parseable: {}", e.getMessage());
        return false;
      }
    }
    throw new IllegalArgumentException("matchExpr lhs must be 'status' or '$.path', got: " + lhs);
  }

  private static String stripQuotes(String s) {
    if (s.length() >= 2
        && ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
            || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\''))) {
      return s.substring(1, s.length() - 1);
    }
    return s;
  }

  private static String upperOrDefault(String s, String dflt) {
    return Texts.hasText(s) ? s.trim().toUpperCase() : dflt;
  }
}
