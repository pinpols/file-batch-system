package io.github.pinpols.batch.testing;

/** MinIO 测试容器统一工厂，避免测试直接绑定容器实现。 */
public final class TestObjectStoreContainers {

  private TestObjectStoreContainers() {}

  public static ObjectStoreContainer create() {
    return new ObjectStoreContainer();
  }
}
