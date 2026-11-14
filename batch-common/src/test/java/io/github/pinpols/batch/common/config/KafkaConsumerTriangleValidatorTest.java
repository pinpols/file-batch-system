package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Kafka consumer 三角时序校验的默认值")
class KafkaConsumerTriangleValidatorTest {

  @DisplayName("未配置会话超时与心跳间隔时按 45000/15000 默认值通过校验")
  @Test
  void shouldPassWithDefaultSessionTimeoutAndHeartbeat() {
    KafkaConsumerTimingProperties properties = new KafkaConsumerTimingProperties();

    KafkaConsumerTriangleValidator validator = new KafkaConsumerTriangleValidator(properties);

    assertThatCode(validator::validate).doesNotThrowAnyException();
  }

  @DisplayName("只调大心跳间隔时以默认会话超时 45000 判定违约")
  @Test
  void shouldRejectHeartbeatAboveOneThirdOfDefaultSessionTimeout() {
    KafkaConsumerTimingProperties properties = new KafkaConsumerTimingProperties();
    properties.getProperties().put("heartbeat.interval.ms", "15001");

    KafkaConsumerTriangleValidator validator = new KafkaConsumerTriangleValidator(properties);

    assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("15000ms");
  }
}
