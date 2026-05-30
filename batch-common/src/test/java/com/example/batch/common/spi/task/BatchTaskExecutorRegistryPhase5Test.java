package com.example.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** Phase 5:enabled-task-types 白名单过滤行为(BatchWorkerSpiProperties)。 */
class BatchTaskExecutorRegistryPhase5Test {

  @Test
  void noPropsBeanRegistersAll() {
    // Spring 容器内无 BatchWorkerSpiProperties bean → 不过滤
    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(
            List.of(stub("import"), stub("shell"), stub("http")), providerOf(null));

    assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("import", "shell", "http");
  }

  @Test
  void emptyEnabledSetRegistersAll() {
    BatchWorkerSpiProperties props = new BatchWorkerSpiProperties();
    props.setEnabledTaskTypes(Set.of()); // 空集 = 不过滤

    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(List.of(stub("import"), stub("shell")), providerOf(props));

    assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("import", "shell");
  }

  @Test
  void enabledSetFiltersToWhitelist() {
    BatchWorkerSpiProperties props = new BatchWorkerSpiProperties();
    props.setEnabledTaskTypes(Set.of("import", "shell"));

    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(
            List.of(stub("import"), stub("shell"), stub("http"), stub("sql")), providerOf(props));

    // 只剩白名单的 2 个
    assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("import", "shell");
    assertThat(registry.find("http")).isNull();
    assertThat(registry.find("sql")).isNull();
    assertThat(registry.find("import")).isNotNull();
  }

  @Test
  void enabledSetWithUnknownTypesRegistersIntersectionOnly() {
    BatchWorkerSpiProperties props = new BatchWorkerSpiProperties();
    // 白名单含一个不存在的 type → 不报错,只注册存在的
    props.setEnabledTaskTypes(Set.of("import", "nonexistent"));

    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(List.of(stub("import"), stub("shell")), providerOf(props));

    assertThat(registry.registeredTypes()).containsExactly("import");
  }

  // ─── helpers ────────────────────────────────────────────────────────────────

  private static BatchTaskExecutor stub(String type) {
    return new BatchTaskExecutor() {
      @Override
      public String taskType() {
        return type;
      }

      @Override
      public TaskCapability capability() {
        return TaskCapability.of(ResourceKind.CPU);
      }

      @Override
      public TaskResult execute(TaskContext ctx) {
        return TaskResult.ok();
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T value) {
    ObjectProvider<T> mock = mock(ObjectProvider.class);
    when(mock.getIfAvailable()).thenReturn(value);
    return mock;
  }
}
