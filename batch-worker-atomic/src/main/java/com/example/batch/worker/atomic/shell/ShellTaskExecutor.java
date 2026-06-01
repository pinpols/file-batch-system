package com.example.batch.worker.atomic.shell;

import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Shell task SPI 实现 — 用 {@link ProcessBuilder} 执行外部命令,带超时 / 隔离 workdir / 输出截断 / 白名单。
 *
 * <p><b>不通过 shell 解释器</b>:command + args 直接走 execve,不会 fork shell,**没有** shell injection 风险(参数中 的
 * `;` / `&&` / `|` 都被当字面量)。需要 shell 特性请显式 `sh -c "..."` 并自行 review 参数。
 *
 * <p>启用方式:{@code batch.worker.executors.shell.enabled=true}(默认 false → bean 不注册,SPI registry 找不到
 * "shell" type)。
 *
 * <p>parameters 协议(用户在 job_definition.parameters 里填):
 *
 * <ul>
 *   <li>{@code command} (required, String):程序路径,如 {@code "/usr/bin/python3"}
 *   <li>{@code args} (optional, List&lt;String&gt;):程序参数
 *   <li>{@code timeoutSeconds} (optional, Long):覆盖默认超时,只能缩短不能延长
 *   <li>{@code env} (optional, Map&lt;String,String&gt;):额外环境变量,需进 {@link
 *       ShellExecutorProperties#getAllowedEnvKeys()} 白名单
 * </ul>
 *
 * <p>output 协议(在 {@link TaskResult#output()} 里):
 *
 * <ul>
 *   <li>{@code exitCode} (Integer):进程退出码
 *   <li>{@code stdout} (String):截断后的标准输出
 *   <li>{@code stderr} (String):截断后的标准错误
 *   <li>{@code stdoutTruncated} / {@code stderrTruncated} (Boolean):是否被截断
 *   <li>{@code durationMillis} (Long):执行耗时
 *   <li>{@code workdir} (String):执行使用的工作目录(若 {@code cleanupWorkdir=false} 还存在)
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "batch.worker.executors.shell",
    name = "enabled",
    havingValue = "true")
public class ShellTaskExecutor implements BatchTaskExecutor {

  static final String PARAM_COMMAND = "command";
  static final String PARAM_ARGS = "args";
  static final String PARAM_TIMEOUT_SECONDS = "timeoutSeconds";
  static final String PARAM_ENV = "env";

  /** 匹配真正的 {@code ..} 路径段(行首/分隔符 + ".." + 分隔符/行尾),不误伤 {@code foo..bar}。 */
  static final Pattern PARENT_DIR_REF = Pattern.compile("(^|[/\\\\])\\.\\.([/\\\\]|$)");

  private final ShellExecutorProperties props;

  /** 包私有测试钩子:value 是否包含真正的 {@code ..} 父目录段。 */
  static boolean hasParentDirRef(String arg) {
    return arg != null && PARENT_DIR_REF.matcher(arg).find();
  }

  /**
   * 每次 invocation 唯一的 reader-map / 线程命名前缀。用 AtomicLong 自增,避免 PID 复用导致两个任务的 stdout 串台(reader map
   * 是实例级共享单例)。
   */
  private static final AtomicLong INVOCATION_SEQ = new AtomicLong();

  /** 包私有测试钩子:生成下一个 invocation id(单调递增,两次调用必不相同)。 */
  static String nextInvocationId() {
    return Long.toString(INVOCATION_SEQ.incrementAndGet());
  }

  @Override
  public String taskType() {
    return props.getTaskType();
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        Set.of(ResourceKind.CPU, ResourceKind.DISK),
        false, // shell 任务不视为幂等(side-effects 未知),失败需人工 review
        true, // 支持 cancel(destroyForcibly)
        props.getDefaultTimeout());
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    try {
      ShellInvocation inv = parseInvocation(ctx);
      Path workdir = createIsolatedWorkdir(ctx);
      try {
        return runProcess(ctx, inv, workdir);
      } finally {
        if (props.isCleanupWorkdir()) {
          cleanupWorkdir(workdir);
        }
      }
    } catch (ShellValidationException ex) {
      return TaskResult.fail(ex.getMessage());
    } catch (RuntimeException ex) {
      log.error(
          "shell executor unexpected error: tenantId={}, jobCode={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex);
      return TaskResult.fail(ex);
    }
  }

  // ─── parsing + validation ────────────────────────────────────────────────────

  private ShellInvocation parseInvocation(TaskContext ctx) {
    Map<String, Object> params = ctx.parameters();

    Object cmdObj = params.get(PARAM_COMMAND);
    if (!(cmdObj instanceof String) || ((String) cmdObj).isBlank()) {
      throw new ShellValidationException("parameters.command required (non-blank string)");
    }
    String command = ((String) cmdObj).trim();

    // 白名单
    if (!props.getCommandWhitelist().isEmpty() && !props.getCommandWhitelist().contains(command)) {
      throw new ShellValidationException(
          "command not in whitelist: " + command + ", allowed=" + props.getCommandWhitelist());
    }
    validateAgainstRegex(command, "command");
    validateNoParentDirRef(command, "command");

    // args
    List<String> args = parseArgs(params.get(PARAM_ARGS));
    if (args.size() > props.getMaxArgs()) {
      throw new ShellValidationException(
          "too many args: " + args.size() + " > maxArgs=" + props.getMaxArgs());
    }
    for (int i = 0; i < args.size(); i++) {
      validateAgainstRegex(args.get(i), "args[" + i + "]");
      validateNoParentDirRef(args.get(i), "args[" + i + "]");
    }

    // timeout(只能缩短)
    Duration timeout = props.getDefaultTimeout();
    Object timeoutObj = params.get(PARAM_TIMEOUT_SECONDS);
    if (timeoutObj instanceof Number) {
      long sec = ((Number) timeoutObj).longValue();
      if (sec <= 0) {
        throw new ShellValidationException("timeoutSeconds must be positive");
      }
      Duration requested = Duration.ofSeconds(sec);
      // 只允许缩短;请求 > default → 取 default(防业务越权拉长)
      timeout =
          requested.compareTo(props.getDefaultTimeout()) < 0
              ? requested
              : props.getDefaultTimeout();
    }

    // env
    Map<String, String> env = parseEnv(params.get(PARAM_ENV));

    return new ShellInvocation(command, args, timeout, env);
  }

  @SuppressWarnings("unchecked")
  private static List<String> parseArgs(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (raw instanceof List<?>) {
      List<String> out = new ArrayList<>();
      for (Object o : (List<?>) raw) {
        if (o == null) {
          throw new ShellValidationException("args contains null element");
        }
        out.add(String.valueOf(o));
      }
      return out;
    }
    throw new ShellValidationException("parameters.args must be a list of strings");
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> parseEnv(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?>)) {
      throw new ShellValidationException("parameters.env must be a map of string→string");
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
      String k = String.valueOf(e.getKey());
      if (!props.getAllowedEnvKeys().contains(k)) {
        throw new ShellValidationException(
            "env key not in allowedEnvKeys: " + k + ", allowed=" + props.getAllowedEnvKeys());
      }
      out.put(k, e.getValue() == null ? "" : String.valueOf(e.getValue()));
    }
    return out;
  }

  private void validateAgainstRegex(String value, String fieldName) {
    if (props.getArgRegexAllowlist().isEmpty()) {
      return;
    }
    for (String regex : props.getArgRegexAllowlist()) {
      if (Pattern.matches(regex, value)) {
        return;
      }
    }
    throw new ShellValidationException(
        fieldName + " contains disallowed characters: \"" + value + "\"");
  }

  private void validateNoParentDirRef(String value, String fieldName) {
    if (props.isRejectParentDirRefs() && hasParentDirRef(value)) {
      throw new ShellValidationException(
          fieldName + " contains parent-dir reference (..): \"" + value + "\"");
    }
  }

  // ─── workdir ────────────────────────────────────────────────────────────────

  private Path createIsolatedWorkdir(TaskContext ctx) {
    try {
      Files.createDirectories(props.getWorkdirBase());
      String subdir =
          String.format(
              "%s-%s-%s",
              safeForPath(ctx.tenantId()),
              safeForPath(ctx.jobCode()),
              UUID.randomUUID().toString().substring(0, 8));
      Path dir = props.getWorkdirBase().resolve(subdir);
      Files.createDirectory(dir);
      return dir;
    } catch (IOException e) {
      throw new RuntimeException("create workdir failed: " + e.getMessage(), e);
    }
  }

  private void cleanupWorkdir(Path workdir) {
    if (workdir == null || !Files.exists(workdir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(workdir)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  log.warn("cleanup workdir failed: {}", p, e);
                }
              });
    } catch (IOException e) {
      log.warn("cleanup workdir walk failed: {}", workdir, e);
    }
  }

  private static String safeForPath(String s) {
    return s == null ? "_" : s.replaceAll("[^A-Za-z0-9._\\-]", "_");
  }

  // ─── process exec ───────────────────────────────────────────────────────────

  private TaskResult runProcess(TaskContext ctx, ShellInvocation inv, Path workdir) {
    List<String> cmd = new ArrayList<>(1 + inv.args.size());
    cmd.add(inv.command);
    cmd.addAll(inv.args);

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.directory(workdir.toFile());
    Map<String, String> env = pb.environment();
    env.clear(); // scrub:不继承父进程 env
    env.put("BATCH_TENANT_ID", Objects.toString(ctx.tenantId(), ""));
    env.put("BATCH_JOB_CODE", Objects.toString(ctx.jobCode(), ""));
    env.put("BATCH_WORKER_ID", Objects.toString(ctx.workerId(), ""));
    env.put("BATCH_WORKDIR", workdir.toString());
    env.putAll(inv.env); // 业务 env 在白名单内才到这步

    long start = System.currentTimeMillis();
    Process proc;
    try {
      proc = pb.start();
    } catch (IOException e) {
      return TaskResult.fail("process start failed: " + e.getMessage(), e);
    }

    // 每次 invocation 唯一 id,避免 PID 复用导致 reader map key 串台
    String invocationId = nextInvocationId();
    try {
      // 异步读 stdout / stderr,防 buffer full block
      Thread stdoutThread =
          startReaderThread(
              proc.getInputStream(), props.getMaxStdoutBytes(), "stdout-" + invocationId);
      Thread stderrThread =
          startReaderThread(
              proc.getErrorStream(), props.getMaxStderrBytes(), "stderr-" + invocationId);

      boolean finished = proc.waitFor(inv.timeout.toMillis(), TimeUnit.MILLISECONDS);
      stdoutThread.join(1000);
      stderrThread.join(1000);

      ReaderResult stdout = readerResults.remove(stdoutThread.getName());
      ReaderResult stderr = readerResults.remove(stderrThread.getName());

      long duration = System.currentTimeMillis() - start;

      if (!finished) {
        proc.destroyForcibly();
        return TaskResult.fail(
            "timed out after " + inv.timeout.toSeconds() + "s",
            new ShellTimeoutException(inv.timeout));
      }

      Map<String, Object> output = new HashMap<>();
      output.put("exitCode", proc.exitValue());
      output.put("stdout", stdout == null ? "" : stdout.text);
      output.put("stderr", stderr == null ? "" : stderr.text);
      output.put("stdoutTruncated", stdout != null && stdout.truncated);
      output.put("stderrTruncated", stderr != null && stderr.truncated);
      output.put("durationMillis", duration);
      output.put("workdir", workdir.toString());

      if (proc.exitValue() == 0) {
        return TaskResult.ok("exit=0", output);
      }
      return TaskResult.fail("exit=" + proc.exitValue() + " stderr=" + summarize(stderr));
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      proc.destroyForcibly();
      return TaskResult.fail("interrupted", ie);
    }
  }

  // 用 ConcurrentMap 接收异步 reader 结果(简化:仅 2 个线程,小 map 够用)
  private final java.util.concurrent.ConcurrentHashMap<String, ReaderResult> readerResults =
      new java.util.concurrent.ConcurrentHashMap<>();

  private Thread startReaderThread(InputStream in, int maxBytes, String name) {
    Thread t =
        new Thread(
            () -> {
              ByteArrayOutputStream buf = new ByteArrayOutputStream();
              boolean truncated = false;
              byte[] tmp = new byte[4096];
              try {
                int n;
                while ((n = in.read(tmp)) != -1) {
                  if (buf.size() + n > maxBytes) {
                    int allowed = maxBytes - buf.size();
                    if (allowed > 0) {
                      buf.write(tmp, 0, allowed);
                    }
                    truncated = true;
                    log.warn("{}: output truncated at {} bytes", name, maxBytes);
                    // 继续 drain 防 pipe buffer 满阻塞 child
                    while (in.read(tmp) != -1) {
                      // discard
                    }
                    break;
                  }
                  buf.write(tmp, 0, n);
                }
              } catch (IOException e) {
                log.warn("{}: reader IO error: {}", name, e.getMessage());
              }
              readerResults.put(name, new ReaderResult(buf.toString(), truncated));
            },
            name);
    t.setDaemon(true);
    t.start();
    return t;
  }

  private static String summarize(ReaderResult r) {
    if (r == null || r.text == null) {
      return "";
    }
    return r.text.length() <= 200 ? r.text : r.text.substring(0, 200) + "...";
  }

  // ─── helper records / exceptions ────────────────────────────────────────────

  private record ShellInvocation(
      String command, List<String> args, Duration timeout, Map<String, String> env) {}

  private record ReaderResult(String text, boolean truncated) {}

  /** 业务参数校验失败,转 TaskResult.fail 友好 message(不抛栈)。 */
  static final class ShellValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ShellValidationException(String message) {
      super(message);
    }
  }

  /** 进程超时,作为 error 透传到 TaskResult。 */
  static final class ShellTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ShellTimeoutException(Duration timeout) {
      super("shell process timed out after " + timeout.toSeconds() + "s");
    }
  }

  /** 让 properties 自动配置在引入 ShellTaskExecutor 时生效。 */
  @Configuration
  @EnableConfigurationProperties(ShellExecutorProperties.class)
  static class PropertiesConfig {}
}
