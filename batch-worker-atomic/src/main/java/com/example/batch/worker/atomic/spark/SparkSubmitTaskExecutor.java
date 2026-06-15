package com.example.batch.worker.atomic.spark;

import com.example.batch.common.exception.BizException;
import com.example.batch.common.security.SensitiveDataValidator;
import com.example.batch.common.spi.task.BatchTaskExecutor;
import com.example.batch.common.spi.task.ResourceKind;
import com.example.batch.common.spi.task.TaskCapability;
import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.worker.atomic.runtime.AtomicErrorCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Atomic Task SPI 骨架:把一个 Spark 作业作为一种 {@code taskType} 提交并跟踪。
 *
 * <p>设计同 {@code ShellTaskExecutor}:command + args 直接走 {@code ProcessBuilder}(execve,**不过 shell 解释器,
 * 无 shell injection**);带超时 / 输出截断 / Lane C 凭据拒入 / ADR-026 dry-run。
 *
 * <p><b>定位与边界(ADR-027)</b>:本执行器只「提交并跟踪」spark-submit 子进程(client 模式), **不管理 Spark
 * 集群**(资源/扩缩容是外部基础设施)。你白拿的是平台的调度 / 编排 / 重试 / 死信 / 审计 / dry-run。
 *
 * <p>parameters 协议(填在 {@code job_definition.parameters}):
 *
 * <ul>
 *   <li>{@code appResource}(required, String):jar 或 .py 路径/URL,受 {@code appResourceAllowlist} 约束
 *   <li>{@code mainClass}(optional, String):jar 入口类(--class)
 *   <li>{@code master}(optional, String):--master,缺省用 {@code defaultMaster}
 *   <li>{@code deployMode}(optional, String):client / cluster,缺省 {@code defaultDeployMode}
 *   <li>{@code name}(optional, String):--name
 *   <li>{@code sparkConf}(optional, Map&lt;String,String&gt;):逐项 --conf k=v,key 受 {@code
 *       allowedConfKeyPrefixes} 约束
 *   <li>{@code appArgs}(optional, List&lt;String&gt;):传给 app 的参数
 *   <li>{@code timeoutSeconds}(optional, Long):覆盖默认超时
 * </ul>
 *
 * <p><b>TODO(启用前按真实集群补全)</b>:
 *
 * <ol>
 *   <li>cluster 模式:driver 在远端,本子进程 spark-submit 立即返回,需轮询 YARN/K8s/REST 拿终态 + {@link
 *       #cancel(String)} 改调 {@code spark-submit --kill <submissionId>}(client 模式当前实现已可用)。
 *   <li>认证:Kerberos/keytab、OAuth、cloud credential —— 走 secret 注入,**禁**进 parameters(已被 Lane C 拦)。
 *   <li>可换 {@code HttpTaskExecutor} 调 Livy / Databricks / EMR / Dataproc REST(纯 client 提交场景)。
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "batch.worker.executors.spark-submit",
    name = "enabled",
    havingValue = "true")
public class SparkSubmitTaskExecutor implements BatchTaskExecutor {

  static final String PARAM_APP_RESOURCE = "appResource";
  static final String PARAM_MAIN_CLASS = "mainClass";
  static final String PARAM_MASTER = "master";
  static final String PARAM_DEPLOY_MODE = "deployMode";
  static final String PARAM_NAME = "name";
  static final String PARAM_SPARK_CONF = "sparkConf";
  static final String PARAM_APP_ARGS = "appArgs";
  static final String PARAM_TIMEOUT_SECONDS = "timeoutSeconds";

  /** 从 spark-submit 输出里抓 application id(YARN/standalone 常见格式),仅用于 output 透传,抓不到不影响成败判定。 */
  private static final Pattern APP_ID =
      Pattern.compile("\\b(application_\\d+_\\d+|app-\\d+-\\d+|driver-\\d+)\\b");

  private final SparkSubmitExecutorProperties props;

  /** taskInstanceId → 运行中的子进程,支持协作式取消(client 模式)。 */
  private final Map<String, Process> running = new ConcurrentHashMap<>();

  @Override
  public String taskType() {
    return props.getTaskType();
  }

  @Override
  public TaskCapability capability() {
    return new TaskCapability(
        Set.of(ResourceKind.CPU, ResourceKind.NET),
        false, // Spark 作业有副作用(写表/写文件),失败需补偿,非幂等
        true, // 支持取消(client 模式 destroy 子进程)
        props.getDefaultTimeout());
  }

  @Override
  public TaskResult execute(TaskContext ctx) {
    try {
      // Lane C:凭据静态拒入(密钥/账密禁进 parameters,走 secret 注入)
      SensitiveDataValidator.rejectIfContainsSensitiveKeys(
          ctx.parameters(), "atomic.spark.parameters");
      SparkInvocation inv = parseInvocation(ctx);

      if (ctx.isDryRun()) {
        // ADR-026:演练不 fork,仅回传将执行的 spark-submit argv(凭据已被 Lane C 拦,argv 里无密钥)。
        Map<String, Object> planned = new LinkedHashMap<>();
        planned.put("dryRun", true);
        planned.put("plannedAction", "spark-submit");
        planned.put("argv", inv.argv);
        log.info(
            "spark executor dry-run skipped submit: tenantId={}, jobCode={}, appResource={}",
            ctx.tenantId(),
            ctx.jobCode(),
            inv.appResource);
        return TaskResult.ok("dry-run: spark-submit argv built (not forked)", planned);
      }
      return runSubmit(ctx, inv);
    } catch (SparkValidationException ex) {
      return AtomicErrorCode.fail(AtomicErrorCode.CONFIG_INVALID, ex.getMessage());
    } catch (BizException ex) {
      // Lane C 凭据拒入 → 任务 FAILED(SECURITY_REJECTED)
      log.warn(
          "spark executor rejected by SensitiveDataValidator: tenantId={}, jobCode={}",
          ctx.tenantId(),
          ctx.jobCode());
      return AtomicErrorCode.fail(
          AtomicErrorCode.SECURITY_REJECTED, "SENSITIVE_DATA_IN_PARAMETERS: " + ex.getMessage());
    } catch (RuntimeException ex) {
      log.error(
          "spark executor unexpected error: tenantId={}, jobCode={}",
          ctx.tenantId(),
          ctx.jobCode(),
          ex);
      return AtomicErrorCode.fail(
          AtomicErrorCode.EXECUTION_FAILED,
          ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
          ex);
    }
  }

  @Override
  public void cancel(String taskInstanceId) {
    Process p = running.get(taskInstanceId);
    if (p != null) {
      // client 模式:driver 即本子进程,destroy 即取消。cluster 模式见类注释 TODO(需 spark-submit --kill)。
      p.destroyForcibly();
      log.info("spark executor cancel requested: taskInstanceId={}", taskInstanceId);
    }
  }

  // ─── parse + 校验 ────────────────────────────────────────────────────────────

  private SparkInvocation parseInvocation(TaskContext ctx) {
    Map<String, Object> params = ctx.parameters();

    String appResource = requireString(params.get(PARAM_APP_RESOURCE), PARAM_APP_RESOURCE);
    validateAppResourceAllowed(appResource);

    String master = optionalString(params.get(PARAM_MASTER), props.getDefaultMaster());
    if (master.isBlank()) {
      throw new SparkValidationException("master 未指定且无 defaultMaster 配置");
    }
    String deployMode = optionalString(params.get(PARAM_DEPLOY_MODE), props.getDefaultDeployMode());
    String mainClass = optionalString(params.get(PARAM_MAIN_CLASS), "");
    String name = optionalString(params.get(PARAM_NAME), "");

    List<String> argv = new ArrayList<>();
    argv.add(props.getSparkSubmitBin());
    argv.add("--master");
    argv.add(master);
    argv.add("--deploy-mode");
    argv.add(deployMode);
    if (!name.isBlank()) {
      argv.add("--name");
      argv.add(name);
    }
    if (!mainClass.isBlank()) {
      argv.add("--class");
      argv.add(mainClass);
    }
    for (Map.Entry<String, String> e : parseConf(params.get(PARAM_SPARK_CONF)).entrySet()) {
      argv.add("--conf");
      argv.add(e.getKey() + "=" + e.getValue());
    }
    argv.add(appResource); // appResource 必须在 spark-submit 选项之后、app args 之前
    argv.addAll(parseAppArgs(params.get(PARAM_APP_ARGS)));

    Duration timeout = parseTimeout(params.get(PARAM_TIMEOUT_SECONDS));
    return new SparkInvocation(appResource, argv, timeout);
  }

  private void validateAppResourceAllowed(String appResource) {
    List<String> allow = props.getAppResourceAllowlist();
    if (allow.isEmpty()) {
      return; // 未配置 = 不校验(仅限本地/联调;生产务必收紧)
    }
    boolean ok = allow.stream().anyMatch(appResource::startsWith);
    if (!ok) {
      throw new SparkValidationException(
          "appResource 不在允许前缀白名单内: " + appResource + ", allowed=" + allow);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> parseConf(Object raw) {
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new SparkValidationException("sparkConf 必须是 map of string→string");
    }
    Set<String> allowedPrefixes = props.getAllowedConfKeyPrefixes();
    Map<String, String> conf = new LinkedHashMap<>();
    for (Map.Entry<?, ?> e : map.entrySet()) {
      String k = String.valueOf(e.getKey());
      if (!allowedPrefixes.isEmpty() && allowedPrefixes.stream().noneMatch(k::startsWith)) {
        throw new SparkValidationException("sparkConf key 不在允许前缀白名单内: " + k);
      }
      conf.put(k, String.valueOf(e.getValue()));
    }
    return conf;
  }

  private List<String> parseAppArgs(Object raw) {
    if (raw == null) {
      return List.of();
    }
    if (!(raw instanceof List<?> list)) {
      throw new SparkValidationException("appArgs 必须是 list of string");
    }
    if (list.size() > props.getMaxAppArgs()) {
      throw new SparkValidationException(
          "appArgs 个数超限: " + list.size() + " > " + props.getMaxAppArgs());
    }
    List<String> args = new ArrayList<>(list.size());
    for (Object o : list) {
      if (o == null) {
        throw new SparkValidationException("appArgs 含 null 元素");
      }
      args.add(String.valueOf(o));
    }
    return args;
  }

  private Duration parseTimeout(Object raw) {
    if (raw == null) {
      return props.getDefaultTimeout();
    }
    long seconds;
    try {
      seconds = Long.parseLong(String.valueOf(raw));
    } catch (NumberFormatException ex) {
      throw new SparkValidationException("timeoutSeconds 必须是整数: " + raw);
    }
    if (seconds <= 0) {
      throw new SparkValidationException("timeoutSeconds 必须为正");
    }
    return Duration.ofSeconds(seconds);
  }

  // ─── 提交 + 跟踪 ──────────────────────────────────────────────────────────────

  private TaskResult runSubmit(TaskContext ctx, SparkInvocation inv) {
    ProcessBuilder pb = new ProcessBuilder(inv.argv);
    Process process;
    try {
      process = pb.start();
    } catch (IOException e) {
      throw new UncheckedIOException("spark-submit 启动失败: " + e.getMessage(), e);
    }
    String taskId = ctx.taskInstanceId() == null ? ("anon-" + process.pid()) : ctx.taskInstanceId();
    running.put(taskId, process);
    try {
      StreamCollector outCollector =
          StreamCollector.start(process.getInputStream(), props.getMaxStdoutBytes());
      StreamCollector errCollector =
          StreamCollector.start(process.getErrorStream(), props.getMaxStderrBytes());

      boolean finished = process.waitFor(inv.timeout.toSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return AtomicErrorCode.fail(
            AtomicErrorCode.TIMEOUT, "spark-submit 超时 " + inv.timeout.toSeconds() + "s 被强制终止");
      }
      int exit = process.exitValue();
      String stdout = outCollector.await();
      String stderr = errCollector.await();

      Map<String, Object> output = new LinkedHashMap<>();
      output.put("exitCode", exit);
      output.put("stdout", stdout);
      output.put("stderr", stderr);
      findApplicationId(stdout, stderr).ifPresent(id -> output.put("applicationId", id));

      if (exit == 0) {
        log.info(
            "spark-submit ok: tenantId={}, jobCode={}, appResource={}",
            ctx.tenantId(),
            ctx.jobCode(),
            inv.appResource);
        return TaskResult.ok("spark-submit exit=0", output);
      }
      return AtomicErrorCode.fail(
          AtomicErrorCode.EXECUTION_FAILED, "spark-submit exit=" + exit, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return AtomicErrorCode.fail(AtomicErrorCode.KILLED, "spark-submit 被中断");
    } finally {
      running.remove(taskId);
    }
  }

  private static Optional<String> findApplicationId(String stdout, String stderr) {
    Matcher m = APP_ID.matcher(stdout + "\n" + stderr);
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }

  // ─── 小工具 ──────────────────────────────────────────────────────────────────

  private static String requireString(Object v, String key) {
    if (!(v instanceof String s) || s.isBlank()) {
      throw new SparkValidationException("parameters." + key + " required (non-blank string)");
    }
    return s.trim();
  }

  private static String optionalString(Object v, String dflt) {
    return v instanceof String s && !s.isBlank() ? s.trim() : dflt;
  }

  /** 后台线程把子进程的一路输出读进有界缓冲(防 buffer 写满导致子进程阻塞 + 防日志爆内存)。 */
  private static final class StreamCollector {
    private final Thread thread;
    private final StringBuilder buf = new StringBuilder();

    private StreamCollector(InputStream in, int maxBytes) {
      this.thread =
          new Thread(
              () -> {
                try (BufferedReader r =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                  String line;
                  while ((line = r.readLine()) != null) {
                    if (buf.length() < maxBytes) {
                      buf.append(line).append('\n');
                    }
                  }
                } catch (IOException ignored) {
                  // 进程结束/流关闭时读异常可忽略
                }
              });
      this.thread.setDaemon(true);
    }

    static StreamCollector start(InputStream in, int maxBytes) {
      StreamCollector c = new StreamCollector(in, maxBytes);
      c.thread.start();
      return c;
    }

    String await() throws InterruptedException {
      thread.join(TimeUnit.SECONDS.toMillis(5));
      return buf.toString();
    }
  }

  /** parameters 校验失败 → CONFIG_INVALID。 */
  private static final class SparkValidationException extends RuntimeException {
    SparkValidationException(String message) {
      super(message);
    }
  }

  /** 单次提交的不可变上下文。 */
  private record SparkInvocation(String appResource, List<String> argv, Duration timeout) {}
}
