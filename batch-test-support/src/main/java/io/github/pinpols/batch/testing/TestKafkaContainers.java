package io.github.pinpols.batch.testing;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Kafka 测试容器统一工厂，集中维护测试镜像。 */
public final class TestKafkaContainers {

  private TestKafkaContainers() {}

  public static KafkaContainer create() {
    return new KafkaContainer(DockerImageName.parse(TestContainerImages.KAFKA));
  }
}
