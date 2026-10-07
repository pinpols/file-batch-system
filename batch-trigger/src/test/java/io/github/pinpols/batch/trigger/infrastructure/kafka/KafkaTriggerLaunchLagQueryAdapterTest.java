package io.github.pinpols.batch.trigger.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

@DisplayName("Kafka 触发延迟查询适配器:停机后拒绝继续采样 lag,避免停机期新建 AdminClient 连接")
class KafkaTriggerLaunchLagQueryAdapterTest {

  /** 关闭后不得再创建 AdminClient：停机路径必须直接失败，避免停机期新建 Kafka 连接。 */
  @Test
  @DisplayName("关闭 AdminClient 后再采样 lag 必须直接抛 IllegalStateException,不得重新建立 Kafka 连接")
  void stoppedAdapter_refusesToSampleLag() {
    KafkaTriggerLaunchLagQueryAdapter adapter = new KafkaTriggerLaunchLagQueryAdapter(
        mock(KafkaAdmin.class), new TriggerOutboxRelayProperties());

    adapter.closeAdminClient();

    assertThatThrownBy(adapter::sampleLag).isInstanceOf(IllegalStateException.class);
  }
}
