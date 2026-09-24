package io.github.pinpols.batch.testing;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redis 兼容测试容器统一工厂，集中维护 Valkey 镜像和服务端口。
 *
 * <p>容器生命周期由调用方或 Spring Testcontainers 扩展管理，本工厂只负责创建实例。
 */
@SuppressWarnings({"java:S2095", "java:S1452"})
public final class TestValkeyContainers {

  public static final int REDIS_PORT = 6379;

  private TestValkeyContainers() {}

  public static GenericContainer<?> create() {
    return new GenericContainer<>(DockerImageName.parse(TestContainerImages.VALKEY))
        .withExposedPorts(REDIS_PORT);
  }

  public static GenericContainer<?> createPersistent() {
    return create().withCommand("redis-server", "--appendonly", "yes");
  }
}
