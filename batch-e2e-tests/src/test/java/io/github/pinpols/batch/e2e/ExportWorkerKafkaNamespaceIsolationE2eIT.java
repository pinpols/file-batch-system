package io.github.pinpols.batch.e2e;

import io.github.pinpols.batch.common.kafka.BatchKafkaTopicNamespace;
import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.e2e.apps.E2eExportApplication;
import io.github.pinpols.batch.worker.core.support.AbstractTaskConsumer;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

@E2eSpringBootTest(
    classes = E2eExportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.kafka.listener.auto-startup=true",
      "batch.startup-self-check.enabled=false"
    })
@Tag("critical")
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class ExportWorkerKafkaNamespaceIsolationE2eIT
    extends AbstractWorkerKafkaNamespaceIsolationSupport {

  @Autowired
  ExportWorkerKafkaNamespaceIsolationE2eIT(
      List<AbstractTaskConsumer> taskConsumers,
      BatchKafkaTopicNamespace topicNamespace,
      KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry) {
    super(taskConsumers, topicNamespace, kafkaListenerEndpointRegistry);
  }

  @Override
  protected String legacyTopic() {
    return BatchTopics.TASK_DISPATCH_EXPORT;
  }
}
