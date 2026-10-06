package io.github.pinpols.batch.trigger.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.trigger.config.TriggerOutboxRelayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaAdmin;

class KafkaTriggerLaunchLagQueryAdapterTest {

  /** 关闭后不得再创建 AdminClient：停机路径必须直接失败，避免停机期新建 Kafka 连接。 */
  @Test
  void stoppedAdapterRefusesToSampleLag() {
    KafkaTriggerLaunchLagQueryAdapter adapter = new KafkaTriggerLaunchLagQueryAdapter(
        mock(KafkaAdmin.class), new TriggerOutboxRelayProperties());

    adapter.closeAdminClient();

    assertThatThrownBy(adapter::sampleLag).isInstanceOf(IllegalStateException.class);
  }
}
