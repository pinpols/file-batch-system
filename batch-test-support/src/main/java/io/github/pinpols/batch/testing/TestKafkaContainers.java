package io.github.pinpols.batch.testing;

import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** Kafka 测试容器统一工厂，集中维护测试镜像。 */
public final class TestKafkaContainers {

  private TestKafkaContainers() {}

  // 资源生命周期由调用方管理：容器分别由 Testcontainers 扩展或共享集成测试基类关闭。
  @SuppressWarnings("resource")
  public static KafkaContainer create() {
    return new KafkaContainer(DockerImageName.parse(TestContainerImages.KAFKA))
        .withEnv("LANG", TestContainerImages.UTF8_LOCALE)
        .withEnv("LC_ALL", TestContainerImages.UTF8_LOCALE);
  }
}
