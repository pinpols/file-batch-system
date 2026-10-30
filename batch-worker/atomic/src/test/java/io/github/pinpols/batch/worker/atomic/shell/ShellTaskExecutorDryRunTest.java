package io.github.pinpols.batch.worker.atomic.shell;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * ADR-026 §dry-run 守护:dry-run 上下文下 Shell executor 必须不 fork 进程、不建 workdir,直接回 success +
 * plannedAction。
 */
@DisplayName("命令执行器 dry-run 短路: 不启动进程与工作目录")
@ExtendWith(OutputCaptureExtension.class)
class ShellTaskExecutorDryRunTest {

  @TempDir
  Path tempDir;

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
  @DisplayName("dry-run 上下文下应返回计划动作, 不启动进程, 不创建工作目录且环境变量取值不外泄")
  void shouldShortCircuit_whenDryRun_andNotForkProcessOrCreateWorkdir() {
    // 准备:用一个绝对不存在的命令,如果真 fork 一定 fail
    TaskContext ctx = new TaskContext(
        "t1",
        "job-1",
        "ti-1",
        "w-1",
        Map.of(
            "command", "/definitely/not/here/should-not-be-forked",
            "args", List.of("--flag"),
            "env", Map.of("FOO", "secret-value")),
        Map.of(PipelineRuntimeKeys.DRY_RUN, true));

    // 执行
    TaskResult result = executor.execute(ctx);

    // 断言
    assertThat(result.success()).isTrue();
    assertThat(result.message()).startsWith("dry-run:");
    assertThat(result.output())
        .containsEntry(PipelineRuntimeKeys.DRY_RUN, true)
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

  @Test
  @DisplayName("参数列表含内联令牌时即使 dry-run 也拒绝执行且不泄漏")
  void shouldRejectCredentialInPlannedArguments(CapturedOutput output) {
    TaskContext ctx = new TaskContext(
        "t1",
        "job-1",
        "ti-1",
        "w-1",
        Map.of(
            "command",
            "/definitely/not/here/should-not-be-forked",
            "args",
            List.of("--token=probe-secret", "--mode=summary")),
        Map.of(PipelineRuntimeKeys.DRY_RUN, true));

    TaskResult result = executor.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.output()).containsEntry("error_code", "SECURITY_REJECTED");
    assertThat(result.message()).doesNotContain("probe-secret");
    assertThat(output.getOut()).doesNotContain("probe-secret");
    assertThat(Files.exists(props.getWorkdirBase())).isFalse();
  }

  @Test
  @DisplayName("带令牌查询参数的 Shell 实参先拒入，不由字符校验回显原值")
  void shouldRejectCredentialBeforeArgumentRegex(CapturedOutput output) {
    TaskContext ctx = new TaskContext(
        "t1",
        "job-1",
        "ti-1",
        "w-1",
        Map.of(
            "command",
            "/definitely/not/here/should-not-be-forked",
            "args",
            List.of("https://example.com?token=probe-secret")),
        Map.of(PipelineRuntimeKeys.DRY_RUN, true));

    TaskResult result = executor.execute(ctx);

    assertThat(result.success()).isFalse();
    assertThat(result.output()).containsEntry("error_code", "SECURITY_REJECTED");
    assertThat(result.message()).doesNotContain("probe-secret");
    assertThat(output.getOut()).doesNotContain("probe-secret");
  }
}
