package io.github.pinpols.batch.common.spi.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class BatchTaskExecutorRegistryTest {

  @Test
  void shouldRegisterAllProvidedExecutors() {
    BatchTaskExecutor shell = new StubExecutor("shell");
    BatchTaskExecutor http = new StubExecutor("http");

    BatchTaskExecutorRegistry registry = new BatchTaskExecutorRegistry(List.of(shell, http));

    assertThat(registry.registeredTypes()).containsExactlyInAnyOrder("shell", "http");
    assertThat(registry.find("shell")).isSameAs(shell);
    assertThat(registry.find("http")).isSameAs(http);
  }

  @Test
  void shouldReturnNullForUnknownType() {
    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(List.of(new StubExecutor("shell")));

    assertThat(registry.find("nope")).isNull();
    assertThat(registry.find(null)).isNull();
  }

  @Test
  void shouldAllowEmptyRegistry() {
    BatchTaskExecutorRegistry registry = new BatchTaskExecutorRegistry(List.of());

    assertThat(registry.registeredTypes()).isEmpty();
    assertThat(registry.find("any")).isNull();
  }

  @Test
  void shouldFailFastOnDuplicateTaskType() {
    BatchTaskExecutor a = new StubExecutor("dup");
    BatchTaskExecutor b = new StubExecutor("dup");

    assertThatThrownBy(() -> new BatchTaskExecutorRegistry(List.of(a, b)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate taskType=dup");
  }

  @Test
  void shouldFailFastOnBlankTaskType() {
    BatchTaskExecutor blank = new StubExecutor("");
    assertThatThrownBy(() -> new BatchTaskExecutorRegistry(List.of(blank)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("null/blank taskType");
  }

  @Test
  void shouldFailFastOnNullTaskType() {
    BatchTaskExecutor nullType = new StubExecutor(null);
    assertThatThrownBy(() -> new BatchTaskExecutorRegistry(List.of(nullType)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("null/blank taskType");
  }

  @Test
  void shouldExposeDumpForDiagnostics() {
    BatchTaskExecutorRegistry registry =
        new BatchTaskExecutorRegistry(List.of(new StubExecutor("shell")));

    assertThat(registry.dumpRegistry())
        .hasSize(1)
        .containsEntry("shell", StubExecutor.class.getName());
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
