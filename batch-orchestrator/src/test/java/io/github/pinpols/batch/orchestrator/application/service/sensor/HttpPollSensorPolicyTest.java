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
import org.junit.jupiter.api.Test;

class HttpPollSensorPolicyTest {

  private final HttpPollSensorPolicy policy =
      new HttpPollSensorPolicy(new SensorProperties(), new ObjectMapper(), failOnRequest());

  @Test
  void matchExpr_status2xx_matches200() {
    assertThat(policy.evaluateMatch("status==2xx", 200, "")).isTrue();
    assertThat(policy.evaluateMatch("status==2xx", 299, "")).isTrue();
    assertThat(policy.evaluateMatch("status==2xx", 300, "")).isFalse();
  }

  @Test
  void matchExpr_statusExact_matches() {
    assertThat(policy.evaluateMatch("status==200", 200, "")).isTrue();
    assertThat(policy.evaluateMatch("status==200", 201, "")).isFalse();
  }

  @Test
  void matchExpr_jsonPointer_matchesField() {
    String body = "{\"status\":\"READY\",\"data\":{\"id\":1}}";
    assertThat(policy.evaluateMatch("$.status==READY", 200, body)).isTrue();
    assertThat(policy.evaluateMatch("$.status==\"READY\"", 200, body)).isTrue();
    assertThat(policy.evaluateMatch("$.status==PENDING", 200, body)).isFalse();
  }

  @Test
  void matchExpr_jsonPointer_nestedField() {
    String body = "{\"data\":{\"id\":42}}";
    assertThat(policy.evaluateMatch("$.data.id==42", 200, body)).isTrue();
  }

  @Test
  void matchExpr_missingPath_returnsFalseNotThrow() {
    assertThat(policy.evaluateMatch("$.missing==foo", 200, "{}")).isFalse();
  }

  @Test
  void matchExpr_noEqualsOperator_throws() {
    assertThatThrownBy(() -> policy.evaluateMatch("status", 200, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void matchExpr_invalidLhs_throws() {
    assertThatThrownBy(() -> policy.evaluateMatch("response==foo", 200, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void postUsesGuardedTransportAndCaseInsensitiveContentType() {
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
  void rejectsRoutingAndFramingHeadersFromSensorSpec() {
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
