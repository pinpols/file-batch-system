package io.github.pinpols.batch.orchestrator.config;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/** 测试支撑: 经公开的 listener 容器工厂 Bean 取得其装配的 {@link DefaultErrorHandler}, 避免反射调用私有方法。 */
public final class TriggerLaunchErrorHandlerTestSupport {

  private TriggerLaunchErrorHandlerTestSupport() {}

  /** 取工厂上装配的 trigger launch 错误处理器。 */
  public static DefaultErrorHandler errorHandlerOf(
      OrchestratorKafkaConsumerConfiguration configuration) {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        configuration.triggerLaunchKafkaListenerContainerFactory(
            new DefaultKafkaConsumerFactory<>(Map.of()), ObservationRegistry.NOOP);
    ConcurrentMessageListenerContainer<String, String> container =
        factory.createContainer(BatchTopics.TRIGGER_LAUNCH_V1);
    return (DefaultErrorHandler) container.getCommonErrorHandler();
  }
}
