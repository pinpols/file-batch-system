package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.dto.WorkerTaskCapabilityDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务执行器注册表:注册查找, 空表容忍与非法类型快速失败")
class BatchTaskExecutorRegistryTest {

  @Test
  @DisplayName("注册全部执行器:类型集合完整, 且按类型可查到同一实例")
  void shouldRegisterAllProvidedExecutors() {
    BatchTaskExecutor shell = new StubExecutor("shell");
    BatchTaskExecutor http = new StubExecutor("http");

    BatchTaskExecutorRegistry registry = new BatchTaskExecutorRegistry(List.of(shell, http));

    assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("shell", "http");
    assertThat(registry.find("shell")).isSameAs(shell);
    assertThat(registry.find("http")).isSameAs(http);
  }

  @Test
  @DisplayName("查找未知类型与空类型:均返回空值, 不抛异常")
  void shouldReturnNullForUnknownType() {
    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(List.of(new StubExecutor("shell")));

    assertThat(registry.find("nope")).isNull();
    assertThat(registry.find(null)).isNull();
  }

  @Test
  @DisplayName("空注册表:类型集合为空, 任意查找均返回空值")
  void shouldAllowEmptyRegistry() {
    BatchTaskExecutorRegistry registry = new BatchTaskExecutorRegistry(List.of());

    assertThat(registry.registeredTypes()).isEmpty();
    assertThat(registry.find("any")).isNull();
  }

  @Test
  @DisplayName("重复任务类型:构造时快速失败, 并提示类型重复")
  void shouldFailFastOnDuplicateTaskType() {
    BatchTaskExecutor a = new StubExecutor("dup");
    BatchTaskExecutor b = new StubExecutor("dup");

    assertThatThrownBy(() -> new BatchTaskExecutorRegistry(List.of(a, b)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate taskType=dup");
  }

  @Test
  @DisplayName("任务类型为空白串:构造时快速失败")
  void shouldFailFastOnBlankTaskType() {
    BatchTaskExecutor blank = new StubExecutor("");
    assertThatThrownBy(() -> new BatchTaskExecutorRegistry(List.of(blank)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("null/blank taskType");
  }

  @Test
  @DisplayName("任务类型为空值:构造时快速失败")
  void shouldFailFastOnNullTaskType() {
    BatchTaskExecutor nullType = new StubExecutor(null);
    assertThatThrownBy(() -> new BatchTaskExecutorRegistry(List.of(nullType)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("null/blank taskType");
  }

  @Test
  @DisplayName("诊断导出:按类型列出执行器实现类全名")
  void shouldExposeDumpForDiagnostics() {
    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(List.of(new StubExecutor("shell")));

    assertThat(registry.dumpRegistry())
        .hasSize(1)
        .containsEntry("shell", StubExecutor.class.getName());
  }

  @Test
  @DisplayName("能力快照按任务类型排序并转换为展示 DTO")
  void shouldExposeStableCapabilitySnapshot() {
    BatchTaskExecutorRegistry registry = new BatchTaskExecutorRegistry(
        List.of(new StubExecutor("z_task"), new StubExecutor("a_task")));

    assertThat(registry.capabilitySnapshot())
        .extracting(WorkerTaskCapabilityDto::taskType)
        .containsExactly("a_task", "z_task");
    assertThat(registry.capabilitySnapshot().getFirst().resourceKinds()).containsExactly("CPU");
  }

  // ─── helpers ─────────────────────────────────────────────────────────────────

  private static final class StubExecutor implements BatchTaskExecutor {
    private final String type;

    StubExecutor(String type) {
      this.type = type;
    }

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
  }
}
