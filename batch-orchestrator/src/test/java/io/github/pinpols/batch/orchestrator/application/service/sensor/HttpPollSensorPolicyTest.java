package io.github.pinpols.batch.orchestrator.application.service.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.orchestrator.config.SensorProperties;
import org.junit.jupiter.api.Test;

class HttpPollSensorPolicyTest {

  private final HttpPollSensorPolicy policy =
      new HttpPollSensorPolicy(new SensorProperties(), new ObjectMapper());

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
}
