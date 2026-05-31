package com.example.batch.sdk.handler.atomic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ShellAtomicHandler — 开箱即用 shell 原子执行(不走 shell 解释器,白名单 + 超时 + 截断)")
class ShellAtomicHandlerTest {

  private static final String ECHO = "/bin/echo";
  private static final String CAT = "/bin/cat";
  private static final String SLEEP = "/bin/sleep";

  /** /bin/false (Linux/CI) 或 /usr/bin/false (macOS),取存在者;都没有则返 null → 用例 skip。 */
  private static String falseBin() {
    if (Files.isExecutable(Path.of("/bin/false"))) {
      return "/bin/false";
    }
    if (Files.isExecutable(Path.of("/usr/bin/false"))) {
      return "/usr/bin/false";
    }
    return null;
  }

  private static boolean exists(String absPath) {
    return Files.isExecutable(Path.of(absPath));
  }

  private static SdkTaskContext ctx(Map<String, Object> params) {
    return new SdkTaskContext("tx", "j", "ti", 1L, "w-1", params, Map.of());
  }

  private static ShellAtomicHandler handler() {
    return new ShellAtomicHandler(ShellAtomicConfig.defaults("shell"));
  }

  @Test
  @DisplayName("/bin/echo hello → success, exitCode=0, stdout 含 'hello'")
  void shouldRunEchoSuccessfully_whenCommandValid() {
    assumeTrue(exists(ECHO), "/bin/echo 不存在,跳过");

    // arrange
    var ctx = ctx(Map.of("command", ECHO, "args", List.of("hello")));

    // act
    SdkTaskResult result = handler().execute(ctx);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("exitCode", 0);
    assertThat((String) result.output().get("stdout")).contains("hello");
    assertThat(result.output()).containsEntry("stdoutTruncated", false);
  }

  @Test
  @DisplayName("缺 command 参数 → fail")
  void shouldFail_whenCommandMissing() {
    // act
    SdkTaskResult result = handler().execute(ctx(Map.of("args", List.of("x"))));

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.message()).contains("command");
  }

  @Test
  @DisplayName("白名单命中外:allowed={/bin/echo} 但 command=/bin/cat → SecurityException → fail")
  void shouldFail_whenCommandNotInAllowlist() {
    // arrange
    var config = new ShellAtomicConfig("shell", Set.of(ECHO), 60, 64 * 1024, true);
    var ctx = ctx(Map.of("command", CAT, "args", List.of()));

    // act
    SdkTaskResult result = new ShellAtomicHandler(config).execute(ctx);

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.error()).isInstanceOf(SecurityException.class);
    assertThat(result.message()).contains("allowedCommands");
  }

  @Test
  @DisplayName("白名单命中内:allowed={/bin/echo} + command=/bin/echo → 放行 success")
  void shouldPass_whenCommandInAllowlist() {
    assumeTrue(exists(ECHO), "/bin/echo 不存在,跳过");

    // arrange
    var config = new ShellAtomicConfig("shell", Set.of(ECHO), 60, 64 * 1024, true);
    var ctx = ctx(Map.of("command", ECHO, "args", List.of("ok")));

    // act
    SdkTaskResult result = new ShellAtomicHandler(config).execute(ctx);

    // assert
    assertThat(result.success()).isTrue();
    assertThat((String) result.output().get("stdout")).contains("ok");
  }

  @Test
  @DisplayName("非零退出码:/bin/false → exitCode=1 但 handler 仍 success(业务自判 exitCode)")
  void shouldReturnNonZeroExitCode_butStillSuccess() {
    String falseBin = falseBin();
    assumeTrue(falseBin != null, "/bin/false 与 /usr/bin/false 都不存在,跳过");

    // act
    SdkTaskResult result = handler().execute(ctx(Map.of("command", falseBin)));

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("exitCode", 1);
  }

  @Test
  @DisplayName("注入字面量:args=['a;rm -rf /'] → 输出含字面 'a;rm -rf /'(证明不走 shell,; 未被解释)")
  void shouldTreatShellMetacharsAsLiteral_whenNotUsingShell() {
    assumeTrue(exists(ECHO), "/bin/echo 不存在,跳过");

    // arrange — 若走了 sh -c,';' 会断句导致 'rm -rf /' 被执行;不走 shell 则整串是 echo 的字面参数
    var payload = "a;rm -rf /";
    var ctx = ctx(Map.of("command", ECHO, "args", List.of(payload)));

    // act
    SdkTaskResult result = handler().execute(ctx);

    // assert
    assertThat(result.success()).isTrue();
    assertThat((String) result.output().get("stdout")).contains(payload);
  }

  @Test
  @DisplayName("timeout:/bin/sleep 10 + timeoutSeconds=1 → destroyForcibly + fail(timeout)")
  void shouldFailWithTimeout_whenProcessExceedsLimit() {
    assumeTrue(exists(SLEEP), "/bin/sleep 不存在,跳过");

    // arrange
    var config = new ShellAtomicConfig("shell", Set.of(), 1, 64 * 1024, true);
    var ctx = ctx(Map.of("command", SLEEP, "args", List.of("10")));

    // act
    SdkTaskResult result = new ShellAtomicHandler(config).execute(ctx);

    // assert
    assertThat(result.success()).isFalse();
    assertThat(result.message()).containsIgnoringCase("timeout");
  }

  @Test
  @DisplayName("maxOutputBytes 截断:输出超限 → stdoutTruncated=true 且 stdout 字节不超上限")
  void shouldTruncateStdout_whenOutputExceedsLimit() {
    assumeTrue(exists(ECHO), "/bin/echo 不存在,跳过");

    // arrange — echo 一段长字符串,maxOutputBytes 设极小
    var longArg = "x".repeat(500);
    var config = new ShellAtomicConfig("shell", Set.of(), 60, 16, true);
    var ctx = ctx(Map.of("command", ECHO, "args", List.of(longArg)));

    // act
    SdkTaskResult result = new ShellAtomicHandler(config).execute(ctx);

    // assert
    assertThat(result.success()).isTrue();
    assertThat(result.output()).containsEntry("stdoutTruncated", true);
    assertThat(((String) result.output().get("stdout")).getBytes()).hasSizeLessThanOrEqualTo(16);
  }
}
