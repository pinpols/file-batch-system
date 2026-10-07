package io.github.pinpols.batch.orchestrator.application.service.sensor;

import static io.github.pinpols.batch.testing.TestHttpTransports.failOnRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpMethod;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.orchestrator.config.SensorProperties;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("外部轮询传感策略: 匹配表达式求值与请求约束口径")
class HttpPollSensorPolicyTest {

  private final HttpPollSensorPolicy policy =
      new HttpPollSensorPolicy(new SensorProperties(), new ObjectMapper(), failOnRequest());

  @Test
  @DisplayName("状态码按两位区间匹配时区间内命中, 区间外不命中")
  void matchExpr_status2xx_matches200() {
    assertThat(policy.evaluateMatch("status==2xx", 200, "")).isTrue();
    assertThat(policy.evaluateMatch("status==2xx", 299, "")).isTrue();
    assertThat(policy.evaluateMatch("status==2xx", 300, "")).isFalse();
  }

  @Test
  @DisplayName("状态码与期望值相等时命中, 不等时不命中")
  void matchExpr_statusExact_matches() {
    assertThat(policy.evaluateMatch("status==200", 200, "")).isTrue();
    assertThat(policy.evaluateMatch("status==200", 201, "")).isFalse();
  }

  @Test
  @DisplayName("响应体字段等于期望值时命中, 带引号写法同样支持")
  void matchExpr_jsonPointer_matchesField() {
    String body = "{\"status\":\"READY\",\"data\":{\"id\":1}}";
    assertThat(policy.evaluateMatch("$.status==READY", 200, body)).isTrue();
    assertThat(policy.evaluateMatch("$.status==\"READY\"", 200, body)).isTrue();
    assertThat(policy.evaluateMatch("$.status==PENDING", 200, body)).isFalse();
  }

  @Test
  @DisplayName("支持按层级路径读取响应体字段并比较取值")
  void matchExpr_jsonPointer_nestedField() {
    String body = "{\"data\":{\"id\":42}}";
    assertThat(policy.evaluateMatch("$.data.id==42", 200, body)).isTrue();
  }

  @Test
  @DisplayName("响应体中缺少该路径时返回不命中而不抛异常")
  void matchExpr_missingPath_returnsFalseNotThrow() {
    assertThat(policy.evaluateMatch("$.missing==foo", 200, "{}")).isFalse();
  }

  @Test
  @DisplayName("匹配表达式缺少比较运算符时抛出参数非法异常")
  void matchExpr_noEqualsOperator_throws() {
    assertThatThrownBy(() -> policy.evaluateMatch("status", 200, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("匹配表达式左侧不是受支持的取值来源时抛出参数非法异常")
  void matchExpr_invalidLhs_throws() {
    assertThatThrownBy(() -> policy.evaluateMatch("response==foo", 200, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("探测请求使用受保护地址策略, 动作为提交且内容类型大小写不敏感")
  void shouldUseGuardedTransport_whenPostingProbe() {
    AtomicReference<OutboundHttpRequest> captured = new AtomicReference<>();
    HttpPollSensorPolicy transportPolicy =
        new HttpPollSensorPolicy(new SensorProperties(), new ObjectMapper(), request -> {
          captured.set(request);
          return new OutboundHttpResponse(200, "ready");
        });
    SensorContext context = new SensorContext(
        "tenant-a",
        1L,
        Map.of(
            "url",
            "https://example.test/status",
            "method",
            "POST",
            "headersJson",
            "{\"content-type\":\"text/plain\"}",
            "body",
            "probe",
            "matchExpr",
            "status==2xx"),
        Map.of(),
        Duration.ofSeconds(30));

    SensorProbeResult result = transportPolicy.probe(context);

    assertThat(result.status()).isEqualTo(SensorProbeStatus.MATCHED);
    assertThat(captured.get().method()).isEqualTo(OutboundHttpMethod.POST);
    assertThat(captured.get().mediaType()).isEqualTo("text/plain");
    assertThat(captured.get().addressPolicy()).isEqualTo(OutboundAddressPolicy.GUARDED);
  }

  @Test
  @DisplayName("传感配置携带路由或分帧类请求头时判定为配置错误并给出提示")
  void shouldRejectHeader_whenSpecCarriesRoutingOrFramingHeader() {
    HttpPollSensorPolicy transportPolicy = new HttpPollSensorPolicy(
        new SensorProperties(),
        new ObjectMapper(),
        request -> new OutboundHttpResponse(200, "ready"));
    SensorContext context = new SensorContext(
        "tenant-a",
        1L,
        Map.of(
            "url",
            "https://example.test/status",
            "headersJson",
            "{\"Host\":\"internal.example\"}",
            "matchExpr",
            "status==2xx"),
        Map.of(),
        Duration.ofSeconds(30));

    SensorProbeResult result = transportPolicy.probe(context);

    assertThat(result.status()).isEqualTo(SensorProbeStatus.ERROR);
    assertThat(result.errorArgs()).contains("HTTP_POLL", "HTTP_POLL header is not allowed: Host");
  }
}
