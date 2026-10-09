package io.github.pinpols.batch.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

@DisplayName("Testcontainers 容器所有权与本地复用标签")
class TestContainerLabelsTest {

  @Test
  @DisplayName("统一工厂创建的容器标记当前仓库所有权")
  void factories_markContainersAsOwnedByThisRepository() {
    assertOwned(TestPostgresContainers.create());
    assertOwned(TestKafkaContainers.create());
    assertOwned(TestValkeyContainers.create());
    assertOwned(TestObjectStoreContainers.create());
  }

  @Test
  @DisplayName("仅显式启用复用时添加本地复用标签")
  void localReuseLabel_appearsOnlyWhenReuseIsEnabled() {
    GenericContainer<?> reusable = TestValkeyContainers.create();
    TestContainerLabels.configureLocalReuse(reusable, true);

    assertThat(reusable.getLabels())
        .containsEntry(TestContainerLabels.LOCAL_REUSE, TestContainerLabels.LOCAL_REUSE_VALUE);

    GenericContainer<?> ordinary = TestValkeyContainers.create();
    TestContainerLabels.configureLocalReuse(ordinary, false);

    assertThat(ordinary.getLabels()).doesNotContainKey(TestContainerLabels.LOCAL_REUSE);
  }

  private static void assertOwned(GenericContainer<?> container) {
    assertThat(container.getLabels())
        .containsEntry(TestContainerLabels.OWNER, TestContainerLabels.OWNER_VALUE);
  }
}
