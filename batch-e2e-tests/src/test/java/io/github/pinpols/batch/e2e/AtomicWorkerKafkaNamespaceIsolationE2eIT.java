package io.github.pinpols.batch.e2e;

import io.github.pinpols.batch.common.kafka.BatchTopics;
import io.github.pinpols.batch.e2e.apps.E2eAtomicApplication;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;

@E2eSpringBootTest(
    classes = E2eAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.kafka.listener.auto-startup=true",
      "batch.startup-self-check.enabled=false"
    })
@Tag("critical")
@SuppressWarnings("PMD.TestClassWithoutTestCases")
class AtomicWorkerKafkaNamespaceIsolationE2eIT
    extends AbstractWorkerKafkaNamespaceIsolationSupport {

  @Override
  protected String legacyTopic() {
    return BatchTopics.TASK_DISPATCH_ATOMIC;
  }
}
