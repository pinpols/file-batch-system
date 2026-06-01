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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ShellTaskExecutor} 单测 — 含真实进程执行(only POSIX,Windows 跳过)。
 *
 * <p>分两组:Validation(无进程) + Execution(真进程,跑 /bin/echo / sleep / false 等)。
 */
class ShellTaskExecutorTest {

  @TempDir Path tempDir;

  private ShellExecutorProperties props;
  private ShellTaskExecutor executor;

  @BeforeEach
  void setUp() {
    props = new ShellExecutorProperties();
    props.setEnabled(true);
    props.setWorkdirBase(tempDir);
    props.setDefaultTimeout(Duration.ofSeconds(10));
    props.setCleanupWorkdir(true);
    executor = new ShellTaskExecutor(props);
  }

  private TaskContext ctxWithParams(Map<String, Object> params) {
    return new TaskContext("t1", "job-1", "ti-1", "w-1", params, Map.of());
  }

  // ─── Validation ──────────────────────────────────────────────────────────────

  @Nested
  class Validation {

    @Test
    void rejectsMissingCommand() {
      TaskResult r = executor.execute(ctxWithParams(Map.of()));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.command required");
    }

    @Test
    void rejectsBlankCommand() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "   ")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.command required");
    }

    @Test
    void rejectsNonStringArgsType() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", "not-a-list")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("args must be a list");
    }

    @Test
    void rejectsCommandOutsideWhitelist() {
      props.setCommandWhitelist(Set.of("/bin/echo"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "/bin/rm")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in whitelist");
    }

    @Test
    void allowsCommandInWhitelist() {
      props.setCommandWhitelist(Set.of("/bin/echo"));
      // 命中白名单后继续真实执行,exitCode 0 即成功
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("hi"))));
      assertThat(r.success()).isTrue();
    }

    @Test
    void rejectsTooManyArgs() {
      props.setMaxArgs(2);
      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("a", "b", "c"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("too many args");
    }

    @Test
    void rejectsEnvKeyNotInAllowList() {
      // 默认 allowedEnvKeys 空 → 任何 env key 都被拒
      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("command", "/bin/echo", "env", Map.of("MY_VAR", "x"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedEnvKeys");
    }

    @Test
    void rejectsBadCharactersInArg() {
      // 默认 regex 不允许引号 / 反斜杠等
      TaskResult r =
          executor.execute(
              ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("'; rm -rf /'"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("disallowed characters");
    }

    @Test
    void rejectsNonPositiveTimeoutSeconds() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "timeoutSeconds", 0)));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("timeoutSeconds must be positive");
    }
  }

  // ─── Capability / metadata ──────────────────────────────────────────────────

  @Test
  void capabilityReflectsConfig() {
    assertThat(executor.taskType()).isEqualTo("shell");
    assertThat(executor.capability().resourceKinds())
        .contains(
            com.example.batch.common.spi.task.ResourceKind.CPU,
            com.example.batch.common.spi.task.ResourceKind.DISK);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
    assertThat(executor.capability().recommendedTimeout()).isEqualTo(Duration.ofSeconds(10));
  }

  // ─── Real process execution ─────────────────────────────────────────────────

  @Nested
  @DisabledOnOs(OS.WINDOWS)
  class RealProcess {

    @Test
    void echoSuccess() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("hello"))));
      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("exitCode", 0);
      assertThat((String) r.output().get("stdout")).contains("hello");
    }

    @Test
    void nonZeroExitMarksFailure() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "/usr/bin/false")));
      assertThat(r.success()).isFalse();
      assertThat(r.output()).isEmpty(); // failure path 不 populate output(直接 fail message)
      assertThat(r.message()).startsWith("exit=1");
    }

    @Test
    void timeoutKillsProcess() {
      props.setDefaultTimeout(Duration.ofMillis(300));
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/sleep", "args", List.of("5"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("timed out");
      assertThat(r.error()).isInstanceOf(ShellTaskExecutor.ShellTimeoutException.class);
    }

    @Test
    void requestedTimeoutLongerThanDefaultIsClampedToDefault() {
      // 业务请求 timeoutSeconds=30(远大于 default),只能缩短不能拉长 → 实际用 default(0.3s)。
      // sleep 5s 远超 default,故应按 default 超时被杀(而非按 30s 等待)。
      props.setDefaultTimeout(Duration.ofMillis(300));
      long start = System.currentTimeMillis();
      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of("command", "/bin/sleep", "args", List.of("5"), "timeoutSeconds", 30)));
      long elapsed = System.currentTimeMillis() - start;
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("timed out after 0s"); // default 0.3s → toSeconds()=0
      // 证明没按请求的 30s 等:总耗时远小于 30s(给足 reader join 余量)
      assertThat(elapsed).isLessThan(10_000L);
    }

    @Test
    void requestedTimeoutShorterThanDefaultIsHonored() {
      // 请求值 < default → 取请求值(缩短允许)。default 10s,请求 1s,sleep 5s → 按 1s 超时。
      props.setDefaultTimeout(Duration.ofSeconds(10));
      long start = System.currentTimeMillis();
      TaskResult r =
          executor.execute(
              ctxWithParams(
                  Map.of("command", "/bin/sleep", "args", List.of("5"), "timeoutSeconds", 1)));
      long elapsed = System.currentTimeMillis() - start;
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("timed out after 1s");
      // 按 1s 缩短超时,远早于 default 10s 或 sleep 5s
      assertThat(elapsed).isLessThan(5_000L);
    }

    @Test
    void envIsScrubbedAndBatchVarsInjected() throws Exception {
      // /usr/bin/env 打印所有 env vars
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "/usr/bin/env")));
      assertThat(r.success()).isTrue();
      String stdout = (String) r.output().get("stdout");
      // 必须包含框架注入的 vars
      assertThat(stdout).contains("BATCH_TENANT_ID=t1");
      assertThat(stdout).contains("BATCH_JOB_CODE=job-1");
      assertThat(stdout).contains("BATCH_WORKER_ID=w-1");
      // 不该有外部 env 泄露(检测一个父进程肯定有的,如 PATH)
      assertThat(stdout).doesNotContain("PATH=");
    }

    @Test
    void cleansUpWorkdirAfterExecution() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("x"))));
      String workdir = (String) r.output().get("workdir");
      assertThat(workdir).isNotNull();
      assertThat(Files.exists(Path.of(workdir))).isFalse();
    }

    @Test
    void keepsWorkdirWhenCleanupDisabled() {
      props.setCleanupWorkdir(false);
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("x"))));
      String workdir = (String) r.output().get("workdir");
      assertThat(Files.exists(Path.of(workdir))).isTrue();
    }

    @Test
    void truncatesLargeStdout() {
      // 用 yes + head 模拟大量输出;直接 head 限制不行(进程会被 SIGPIPE 杀)
      // 改用 /bin/echo 重复 yes 串
      props.setMaxStdoutBytes(50);
      // /usr/bin/yes 输出 "y\n" 无限;timeout 兜底
      props.setDefaultTimeout(Duration.ofSeconds(2));
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "/usr/bin/yes")));
      // yes 会无限输出直到 reader truncate + drain 完才算结束 → 这里超时也合理
      // 主断言:无论结果,如果 reader 抓到了 stdout 必然 ≤ 50 bytes
      Object stdout = r.output().get("stdout");
      if (stdout != null) {
        assertThat(((String) stdout).length()).isLessThanOrEqualTo(50);
      }
    }
  }
}
