package com.example.batch.sdk.handler.atomic;

import com.example.batch.sdk.handler.SdkAbstractAtomicHandler;
import com.example.batch.sdk.task.SdkTaskContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;

/**
 * 开箱即用的 shell 原子执行 handler。
 *
 * <p><b>安全设计</b>(对应 ADR-029 dual-use RCE 隔离思路,SDK 侧投影):
 *
 * <ul>
 *   <li><b>不走 shell 解释器</b> — 直接 {@code new ProcessBuilder(command, args...)}(底层 {@code execve}),不
 *       fork {@code sh -c "..."}。因此 args 里的 {@code ;} {@code |} {@code &&} 都是**字面量参数**,无 shell 注入。
 *   <li><b>command 白名单</b> — {@link ShellAtomicConfig#allowedCommands()} 非空时强制精确匹配绝对路径。
 *   <li><b>timeout 杀进程</b> — 超时 {@code destroyForcibly()} 并转 fail。
 *   <li><b>workdir 隔离</b> — 每次执行建临时目录,执行后按配置递归清理。
 *   <li><b>输出截断</b> — stdout / stderr 各按 {@code maxOutputBytes} 截断。
 * </ul>
 *
 * <p>入参(来自 {@link SdkTaskContext#parameters()}):
 *
 * <ul>
 *   <li>{@code command}(String,必需)— 程序绝对路径,如 {@code "/bin/echo"}
 *   <li>{@code args}(List&lt;String&gt;,可空)— 命令参数,逐个作为字面量传给进程
 * </ul>
 *
 * <p>输出 Map:{@code exitCode}(int) / {@code stdout}(String) / {@code stderr}(String) / {@code
 * stdoutTruncated}(bool) / {@code stderrTruncated}(bool)。<br>
 * 注意:{@code exitCode != 0} **不**代表 handler fail —— 进程正常退出即 success,业务自行判断 exitCode。
 */
@Slf4j
public class ShellAtomicHandler extends SdkAbstractAtomicHandler<Map<String, Object>> {

  private final ShellAtomicConfig config;

  public ShellAtomicHandler(ShellAtomicConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("config must not be null");
    }
    this.config = config;
  }

  @Override
  public String taskType() {
    return config.taskType();
  }

  @Override
  protected Map<String, Object> doInvoke(SdkTaskContext ctx) throws Exception {
    String command = readCommand(ctx);
    List<String> args = readArgs(ctx);

    if (!config.allowedCommands().isEmpty() && !config.allowedCommands().contains(command)) {
      throw new SecurityException("command not in allowedCommands: " + command);
    }

    List<String> cmd = new ArrayList<>(1 + args.size());
    cmd.add(command);
    cmd.addAll(args);

    Path workdir = Files.createTempDirectory("sdk-shell-");
    try {
      return runProcess(cmd, workdir);
    } finally {
      if (config.cleanupWorkdir()) {
        deleteRecursively(workdir);
      }
    }
  }

  @Override
  protected Map<String, Object> asOutput(Map<String, Object> r) {
    return r;
  }

  private static String readCommand(SdkTaskContext ctx) {
    Object raw = ctx.parameters().get("command");
    if (!(raw instanceof String s) || s.isBlank()) {
      throw new IllegalArgumentException("parameter 'command' is required (absolute program path)");
    }
    return s;
  }

  private static List<String> readArgs(SdkTaskContext ctx) {
    Object raw = ctx.parameters().get("args");
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("parameter 'args' must be a List<String>");
    }
    List<String> out = new ArrayList<>(list.size());
    for (Object o : list) {
      out.add(String.valueOf(o));
    }
    return out;
  }

  private Map<String, Object> runProcess(List<String> cmd, Path workdir)
      throws IOException, InterruptedException, TimeoutException {
    ProcessBuilder pb = new ProcessBuilder(cmd).directory(workdir.toFile());
    pb.redirectErrorStream(false);

    Process process = pb.start();

    // 并发读 stdout / stderr,避免任一管道缓冲填满导致子进程阻塞 / 死锁。
    OutputCapture stdoutCap = new OutputCapture(process.getInputStream(), config.maxOutputBytes());
    OutputCapture stderrCap = new OutputCapture(process.getErrorStream(), config.maxOutputBytes());
    Thread stdoutThread = new Thread(stdoutCap, "sdk-shell-stdout");
    Thread stderrThread = new Thread(stderrCap, "sdk-shell-stderr");
    stdoutThread.start();
    stderrThread.start();

    boolean finished = process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      stdoutThread.join();
      stderrThread.join();
      throw new TimeoutException(
          "shell command timeout after " + config.timeoutSeconds() + "s: " + cmd.get(0));
    }

    stdoutThread.join();
    stderrThread.join();

    int exitCode = process.exitValue();
    return Map.of(
        "exitCode", exitCode,
        "stdout", stdoutCap.text(),
        "stderr", stderrCap.text(),
        "stdoutTruncated", stdoutCap.truncated(),
        "stderrTruncated", stderrCap.truncated());
  }

  private static void deleteRecursively(Path dir) {
    try (var paths = Files.walk(dir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  log.warn("failed to delete temp path {}: {}", p, e.getMessage());
                }
              });
    } catch (IOException e) {
      log.warn("failed to cleanup workdir {}: {}", dir, e.getMessage());
    }
  }

  /** 读取一个流到字节上限,超出则截断并置位 truncated。 */
  private static final class OutputCapture implements Runnable {

    private final InputStream in;
    private final int maxBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private volatile boolean truncated;

    OutputCapture(InputStream in, int maxBytes) {
      this.in = in;
      this.maxBytes = maxBytes;
    }

    @Override
    public void run() {
      byte[] chunk = new byte[8192];
      try (InputStream stream = in) {
        int n;
        while ((n = stream.read(chunk)) != -1) {
          int remaining = maxBytes - buffer.size();
          if (remaining <= 0) {
            truncated = true;
            continue; // drain残余字节,避免子进程在满管道上阻塞
          }
          int toWrite = Math.min(n, remaining);
          buffer.write(chunk, 0, toWrite);
          if (toWrite < n) {
            truncated = true;
          }
        }
      } catch (IOException e) {
        log.warn("failed reading process output: {}", e.getMessage());
      }
    }

    String text() {
      return buffer.toString(StandardCharsets.UTF_8);
    }

    boolean truncated() {
      return truncated;
    }
  }
}
