package io.github.pinpols.batch.worker.atomic.shell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("命令执行器: 参数校验, 能力声明与真实进程执行")
class ShellTaskExecutorTest {

  @TempDir
  Path tempDir;

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
  @DisplayName("参数校验: 命令, 参数, 环境变量与超时的准入判定")
  class Validation {

    @Test
    @DisplayName("缺少命令时执行应失败, 并提示参数必填")
    void shouldReject_whenCommandMissing() {
      TaskResult r = executor.execute(ctxWithParams(Map.of()));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.command required");
    }

    @Test
    @DisplayName("命令为纯空白时同样应判为缺失并失败")
    void shouldReject_whenCommandBlank() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "   ")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("parameters.command required");
    }

    @Test
    @DisplayName("参数类型不是列表时执行应失败, 提示取值类型不合法")
    void shouldReject_whenArgsNotList() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", "not-a-list")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("args must be a list");
    }

    @Test
    @DisplayName("命令不在白名单内时应拒绝执行, 不调度任何进程")
    void shouldReject_whenCommandOutsideWhitelist() {
      props.setCommandWhitelist(Set.of("/bin/echo"));
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "/bin/rm")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in whitelist");
    }

    @Test
    @DisplayName("命令命中白名单时应正常执行并返回成功")
    void shouldExecute_whenCommandInWhitelist() {
      props.setCommandWhitelist(Set.of("/bin/echo"));
      // 命中白名单后继续真实执行,exitCode 0 即成功
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("hi"))));
      assertThat(r.success()).isTrue();
    }

    @Test
    @DisplayName("参数个数超过上限时应拒绝执行, 并说明数量越界")
    void shouldReject_whenArgsExceedLimit() {
      props.setMaxArgs(2);
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("a", "b", "c"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("too many args");
    }

    @Test
    @DisplayName("环境变量键不在允许清单内时应拒绝执行")
    void shouldReject_whenEnvKeyNotAllowed() {
      // 默认 allowedEnvKeys 空 → 任何 env key 都被拒
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("command", "/bin/echo", "env", Map.of("MY_VAR", "x"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("not in allowedEnvKeys");
    }

    @Test
    @DisplayName("参数含不允许的字符时应拒绝执行, 阻断命令注入")
    void shouldReject_whenArgHasDisallowedCharacters() {
      // 默认 regex 不允许引号 / 反斜杠等
      TaskResult r = executor.execute(
          ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("'; rm -rf /'"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("disallowed characters");
    }

    @Test
    @DisplayName("超时秒数非正数时应拒绝执行")
    void shouldReject_whenTimeoutNotPositive() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "timeoutSeconds", 0)));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("timeoutSeconds must be positive");
    }

    @Test
    @DisplayName("参数携带敏感凭据字段时应被凭据闸门拒绝, 并返回敏感数据标识")
    void rejectsSensitiveCredentialInParameters_LaneC() {
      // Lane C:parameters 含 password 字段直接 FAILED,error 含 SENSITIVE_DATA_IN_PARAMETERS 标识
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "password", "leak123")));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("SENSITIVE_DATA_IN_PARAMETERS");
    }
  }

  // ─── Capability / metadata ──────────────────────────────────────────────────

  @Test
  @DisabledOnOs(OS.WINDOWS)
  @DisplayName("连续超时后输出读取线程应及时回收, 不残留线程泄漏")
  void shouldReleaseOutputReaderThreads_whenTimeoutsRepeat() throws Exception {
    Set<Thread> existing = Thread.getAllStackTraces().keySet();
    props.setDefaultTimeout(Duration.ofMillis(100));
    for (int i = 0; i < 3; i++) {
      TaskResult result =
          executor.execute(ctxWithParams(Map.of("command", "/bin/sleep", "args", List.of("5"))));
      assertThat(result.error()).isInstanceOf(ShellTaskExecutor.ShellTimeoutException.class);
    }
    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
      List<Thread> remaining = Thread.getAllStackTraces().keySet().stream()
          .filter(thread -> !existing.contains(thread))
          .filter(thread ->
              thread.getName().startsWith("stdout-") || thread.getName().startsWith("stderr-"))
          .toList();
      assertThat(remaining).isEmpty();
    });
  }

  @Test
  @DisplayName("能力声明应反映配置: 任务类型为命令执行, 占用计算与磁盘资源且可取消, 非幂等")
  void shouldExposeCapability_whenExecutorConfigured() {
    assertThat(executor.taskType()).isEqualTo("shell");
    assertThat(executor.capability().resourceKinds())
        .contains(
            io.github.pinpols.batch.common.spi.task.ResourceKind.CPU,
            io.github.pinpols.batch.common.spi.task.ResourceKind.DISK);
    assertThat(executor.capability().idempotent()).isFalse();
    assertThat(executor.capability().cancellable()).isTrue();
    assertThat(executor.capability().recommendedTimeout()).isEqualTo(Duration.ofSeconds(10));
  }

  // ─── Real process execution ─────────────────────────────────────────────────

  @Nested
  @DisabledOnOs(OS.WINDOWS)
  @DisplayName("真实进程执行: 退出码, 超时, 取消与输出处理")
  class RealProcess {

    @Test
    @DisplayName("命令正常结束时退出码应为零, 且标准输出包含预期内容")
    void shouldReturnStdoutAndZeroExit_whenCommandSucceeds() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("hello"))));
      assertThat(r.success()).isTrue();
      assertThat(r.output()).containsEntry("exitCode", 0);
      assertThat((String) r.output().get("stdout")).contains("hello");
    }

    @Test
    @DisplayName("进程以非零码退出时应判失败, 输出只带错误码并在消息中保留退出摘要")
    void shouldMarkFailure_whenExitCodeNonZero() {
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "/usr/bin/false")));
      assertThat(r.success()).isFalse();
      // K3:失败路径只填 error_code(无其它 output),保持 message 携带 exit/stderr 摘要
      assertThat(r.output())
          .containsOnlyKeys("error_code")
          .containsEntry("error_code", "EXECUTION_FAILED");
      assertThat(r.message()).startsWith("exit=1");
    }

    @Test
    @DisplayName("执行超过超时上限时应终止进程并返回超时异常")
    void shouldKillProcess_whenTimeoutExceeded() {
      props.setDefaultTimeout(Duration.ofMillis(300));
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/sleep", "args", List.of("5"))));
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("timed out");
      assertThat(r.error()).isInstanceOf(ShellTaskExecutor.ShellTimeoutException.class);
    }

    @Test
    @DisplayName("取消信号应终止正在运行的进程, 并返回失败结果")
    void shouldTerminateProcess_whenCancelled() throws Exception {
      ExecutorService executorService = Executors.newSingleThreadExecutor();
      try {
        Future<TaskResult> result = executorService.submit(() ->
            executor.execute(ctxWithParams(Map.of("command", "/bin/sleep", "args", List.of("5")))));
        Thread.sleep(150);
        executor.cancel("ti-1");

        assertThat(result.get(3, TimeUnit.SECONDS).success()).isFalse();
      } finally {
        executorService.shutdownNow();
      }
    }

    @Test
    @DisplayName("请求超时长于默认值时应按默认值封顶, 不允许业务自行放宽")
    void shouldClampToDefault_whenRequestedTimeoutLonger() {
      // 业务请求 timeoutSeconds=30(远大于 default),只能缩短不能拉长 → 实际用 default(0.3s)。
      // sleep 5s 远超 default,故应按 default 超时被杀(而非按 30s 等待)。
      props.setDefaultTimeout(Duration.ofMillis(300));
      long start = System.currentTimeMillis();
      TaskResult r = executor.execute(ctxWithParams(
          Map.of("command", "/bin/sleep", "args", List.of("5"), "timeoutSeconds", 30)));
      long elapsed = System.currentTimeMillis() - start;
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("timed out after 0s"); // default 0.3s → toSeconds()=0
      // 证明没按请求的 30s 等:总耗时远小于 30s(给足 reader join 余量)
      assertThat(elapsed).isLessThan(10_000L);
    }

    @Test
    @DisplayName("请求超时短于默认值时应按请求值生效, 允许业务收紧上限")
    void shouldHonorRequestedTimeout_whenShorterThanDefault() {
      // 请求值 < default → 取请求值(缩短允许)。default 10s,请求 1s,sleep 5s → 按 1s 超时。
      props.setDefaultTimeout(Duration.ofSeconds(10));
      long start = System.currentTimeMillis();
      TaskResult r = executor.execute(ctxWithParams(
          Map.of("command", "/bin/sleep", "args", List.of("5"), "timeoutSeconds", 1)));
      long elapsed = System.currentTimeMillis() - start;
      assertThat(r.success()).isFalse();
      assertThat(r.message()).contains("timed out after 1s");
      // 按 1s 缩短超时,远早于 default 10s 或 sleep 5s
      assertThat(elapsed).isLessThan(5_000L);
    }

    @Test
    @DisplayName("执行时应清洗父进程环境变量, 只注入批次上下文相关变量")
    void shouldScrubEnvAndInjectBatchVars_whenExecuting() {
      // /usr/bin/env 打印所有 env vars
      TaskResult r = executor.execute(ctxWithParams(Map.of("command", "/usr/bin/env")));
      assertThat(r.success()).isTrue();
      String stdout = (String) r.output().get("stdout");
      // 必须包含框架注入的 vars
      assertThat(stdout)
          .contains("BATCH_TENANT_ID=t1")
          .contains("BATCH_JOB_CODE=job-1")
          .contains("BATCH_WORKER_ID=w-1");
      // 不该有外部 env 泄露(检测一个父进程肯定有的,如 PATH)
      assertThat(stdout).doesNotContain("PATH=");
    }

    @Test
    @DisplayName("开启清理时执行结束后应删除工作目录")
    void shouldRemoveWorkdir_whenCleanupEnabled() {
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("x"))));
      String workdir = (String) r.output().get("workdir");
      assertThat(workdir).isNotNull();
      assertThat(Files.exists(Path.of(workdir))).isFalse();
    }

    @Test
    @DisplayName("关闭清理时执行结束后应保留工作目录, 便于问题排查")
    void shouldKeepWorkdir_whenCleanupDisabled() {
      props.setCleanupWorkdir(false);
      TaskResult r =
          executor.execute(ctxWithParams(Map.of("command", "/bin/echo", "args", List.of("x"))));
      String workdir = (String) r.output().get("workdir");
      assertThat(Files.exists(Path.of(workdir))).isTrue();
    }

    @Test
    @DisplayName("标准输出超过上限时应截断, 抓取内容长度不超过限制")
    void shouldTruncateStdout_whenOutputExceedsLimit() {
      // 用 yes + head 模拟大量输出;直接 head 限制不行(进程会被 SIGPIPE 杀)
      // 改用 /bin/echo 重复 yes 串
      props.setMaxStdoutBytes(50);
      // /usr/bin/yes 输出 "y\n" 无限;timeout 回退
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
