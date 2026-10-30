package io.github.pinpols.batch.orchestrator.infrastructure.sensor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorContext;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorProbeStatus;
import io.github.pinpols.batch.orchestrator.application.service.sensor.SensorSpecs;
import io.github.pinpols.batch.orchestrator.config.SensorProperties;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

@DisplayName("Kafka 偏移传感器:非法数值不进入 Kafka 查询")
class KafkaOffsetSensorPolicyTest {
  @Test
  @DisplayName("负数、超界、小数和过大偏移直接判定配置错误")
  void shouldRejectInvalidNumbersBeforeAccessingKafka() {
    KafkaAdmin admin = mock(KafkaAdmin.class);
    KafkaOffsetSensorPolicy policy = new KafkaOffsetSensorPolicy(admin, new SensorProperties());
    for (Map<String, Object> spec : List.<Map<String, Object>>of(
        Map.of("topic", "events", "partition", -1, "minOffset", 1),
        Map.of("topic", "events", "partition", 4_294_967_296L, "minOffset", 1),
        Map.of("topic", "events", "partition", 1.5, "minOffset", 1),
        Map.of("topic", "events", "partition", 0, "minOffset", -1),
        Map.of("topic", "events", "partition", 0, "minOffset", 1.5),
        Map.of(
            "topic",
            "events",
            "partition",
            0,
            "minOffset",
            new BigInteger("9223372036854775808")))) {
      SensorContext context =
          new SensorContext("tenant", 1L, spec, Map.of(), Duration.ofMinutes(1), null);
      assertThat(policy.probe(context).status())
          .as(spec.toString())
          .isEqualTo(SensorProbeStatus.ERROR);
    }
    verifyNoInteractions(admin);
  }

  @Test
  @DisplayName("整数和整数文本保留精确值, 非整数与溢出不发生截断")
  void shouldParseIntegerValuesExactly() {
    assertThat(SensorSpecs.longValue(Map.of("x", " 9223372036854775807 "), "x"))
        .isEqualTo(Long.MAX_VALUE);
    assertThat(SensorSpecs.longValue(Map.of("x", new BigDecimal("1.0")), "x")).isEqualTo(1L);
    assertThat(SensorSpecs.longValue(Map.of("x", new BigDecimal("1.1")), "x")).isNull();
    assertThat(SensorSpecs.longValue(Map.of("x", Double.POSITIVE_INFINITY), "x"))
        .isNull();
    assertThat(SensorSpecs.intValue(Map.of("x", Integer.MAX_VALUE), "x"))
        .isEqualTo(Integer.MAX_VALUE);
    assertThat(SensorSpecs.intValue(Map.of("x", 4_294_967_296L), "x")).isNull();
  }
}
