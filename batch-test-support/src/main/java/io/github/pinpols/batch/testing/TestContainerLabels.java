package io.github.pinpols.batch.testing;

import org.testcontainers.containers.GenericContainer;

/** Testcontainers 的仓库归属标签，供本地清理工具精确识别测试容器。 */
public final class TestContainerLabels {

  public static final String OWNER = "io.github.pinpols.batch.testcontainers.owner";
  public static final String OWNER_VALUE = "file-batch-system";
  public static final String LOCAL_REUSE = "io.github.pinpols.batch.testcontainers.reuse";
  public static final String LOCAL_REUSE_VALUE = "local-opt-in";

  private TestContainerLabels() {}

  public static void markOwned(GenericContainer<?> container) {
    container.withLabel(OWNER, OWNER_VALUE);
  }

  public static void configureLocalReuse(GenericContainer<?> container, boolean enabled) {
    if (enabled) {
      // 该标签参与 Testcontainers 复用指纹，跨运行必须保持稳定。
      container.withLabel(LOCAL_REUSE, LOCAL_REUSE_VALUE);
    }
    container.withReuse(enabled);
  }
}
