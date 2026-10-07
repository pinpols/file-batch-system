package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Phase 5:enabled-task-types 白名单过滤行为(BatchWorkerAtomicProperties)。 */
@DisplayName("任务执行器注册表白名单过滤:属性缺失, 空集, 命中, 未知类型与两种配置键")
class BatchTaskExecutorRegistryPhase5Test {

  @Test
  @DisplayName("容器中无原子属性配置:不启用白名单, 全部执行器均注册")
  void shouldRegisterAll_whenPropertiesBeanAbsent() {
    // Spring 容器内无 BatchWorkerAtomicProperties bean → 不过滤
    BatchTaskExecutorRegistry registry = new BatchTaskExecutorRegistry(
        List.of(stub("import"), stub("shell"), stub("http")), providerOf(null));

    assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("import", "shell", "http");
  }

  @Test
  @DisplayName("白名单为空集:视为不过滤, 全部执行器均注册")
  void shouldRegisterAll_whenEnabledSetEmpty() {
    BatchWorkerAtomicProperties props = new BatchWorkerAtomicProperties();
    props.setEnabledTaskTypes(Set.of()); // 空集 = 不过滤

    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(List.of(stub("import"), stub("shell")), providerOf(props));

    assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("import", "shell");
  }

  @Test
  @DisplayName("白名单非空:仅注册命中项, 未命中类型无法查找")
  void shouldFilterToWhitelist_whenEnabledSetPresent() {
    BatchWorkerAtomicProperties props = new BatchWorkerAtomicProperties();
    props.setEnabledTaskTypes(Set.of("import", "shell"));

    BatchTaskExecutorRegistry registry = new BatchTaskExecutorRegistry(
        List.of(stub("import"), stub("shell"), stub("http"), stub("sql")), providerOf(props));

    // 只剩白名单的 2 个
    assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("import", "shell");
    assertThat(registry.find("http")).isNull();
    assertThat(registry.find("sql")).isNull();
    assertThat(registry.find("import")).isNotNull();
  }

  @Test
  @DisplayName("白名单含未注册类型:不报错, 只注册两侧都存在的类型")
  void shouldRegisterIntersectionOnly_whenWhitelistHasUnknownType() {
    BatchWorkerAtomicProperties props = new BatchWorkerAtomicProperties();
    // 白名单含一个不存在的 type → 不报错,只注册存在的
    props.setEnabledTaskTypes(Set.of("import", "nonexistent"));

    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(List.of(stub("import"), stub("shell")), providerOf(props));

    assertThat(registry.registeredTypes()).containsExactly("import");
  }

  @Test
  @DisplayName("规范配置键:分隔书写的白名单被正确绑定")
  void shouldBindWhitelist_whenCanonicalPropertyUsed() {
    BatchWorkerAtomicProperties props =
        bind(Map.of("batch.worker.atomic.enabled-task-types", "sql,http"));

    assertThat(props.getEnabledTaskTypes()).containsExactlyInAnyOrder("sql", "http");
  }

  @Test
  @DisplayName("兼容配置键:仍能绑定白名单, 且取值与规范键一致")
  @SuppressWarnings("deprecation")
  void shouldBindWhitelist_whenLegacyPropertyUsed() {
    BatchWorkerAtomicProperties props =
        bind(Map.of("batch.worker.atomic.enabled-types", "sql,http"));

    assertThat(props.getEnabledTaskTypes()).containsExactlyInAnyOrder("sql", "http");
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

  private static BatchWorkerAtomicProperties bind(Map<String, String> properties) {
    return new Binder(new MapConfigurationPropertySource(properties))
        .bind("batch.worker.atomic", Bindable.of(BatchWorkerAtomicProperties.class))
        .orElseThrow(() -> new AssertionError("atomic properties were not bound"));
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> providerOf(T value) {
    ObjectProvider<T> mock = mock(ObjectProvider.class);
    when(mock.getIfAvailable()).thenReturn(value);
    return mock;
  }
}
