package io.github.pinpols.batch.e2e;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.e2e.apps.E2eProcessApplication;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

@E2eSpringBootTest(
    classes = E2eProcessApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.kafka.listener.auto-startup=true",
      "batch.startup-self-check.enabled=false"
    })
@Tag("critical")
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class ProcessWorkerKafkaNamespaceIsolationE2eIT extends AbstractWorkerKafkaNamespaceIsolationE2eIT {

  @Override
  protected String legacyTopic() {
    return BatchTopics.TASK_DISPATCH_PROCESS;
  }
}
