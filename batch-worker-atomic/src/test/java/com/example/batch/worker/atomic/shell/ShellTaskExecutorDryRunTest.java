package com.example.batch.worker.atomic.shell;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ADR-026 §dry-run 守护:dry-run 上下文下 Shell executor 必须不 fork 进程、不建 workdir,直接回 success +
 * plannedAction。
 */
class ShellTaskExecutorDryRunTest {

  @TempDir Path tempDir;

  private ShellExecutorProperties props;
  private ShellTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new ShellExecutorProperties();
    props.setEnabled(true);
    props.setWorkdirBase(tempDir.resolve("workdirs"));
    props.setDefaultTimeout(Duration.ofSeconds(10));
    props.setAllowedEnvKeys(Set.of("FOO"));
    executor = new ShellTaskExecutor(props);
  }

  @Test
  void shouldShortCircuit_whenDryRun_andNotForkProcessOrCreateWorkdir() {
    // arrange:用一个绝对不存在的命令,如果真 fork 一定 fail
    TaskContext ctx =
        new TaskContext(
            "t1",
            "job-1",
            "ti-1",
            "w-1",
            Map.of(
                "command", "/definitely/not/here/should-not-be-forked",
                "args", List.of("--flag"),
                "env", Map.of("FOO", "secret-value")),
            Map.of("dryRun", true));

    // act
    TaskResult result = executor.execute(ctx);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.message()).startsWith("dry-run:");
    assertThat(result.output())
        .containsEntry("dryRun", true)
        .containsEntry("plannedAction", "shell")
        .containsEntry("command", "/definitely/not/here/should-not-be-forked")
        .containsEntry("args", List.of("--flag"))
        .containsEntry("envKeys", List.of("FOO"))
        .containsKey("timeoutSeconds");
    // env value 不能进 output(防泄密)
    assertThat(result.output().values()).noneMatch(v -> "secret-value".equals(String.valueOf(v)));
    // workdir 没有被创建
    assertThat(Files.exists(props.getWorkdirBase())).isFalse();
  }
}
