package com.example.batch.sdk.dispatcher;

import com.example.batch.sdk.client.BatchPlatformClient;
import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.idempotent.Idempotent;
import com.example.batch.sdk.idempotent.SdkIdempotencyStore;
import com.example.batch.sdk.idempotent.SdkIdempotentHandler;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.internal.PlatformHttpException;
import com.example.batch.sdk.internal.ThrottledLogger;
import com.example.batch.sdk.retry.RetryOn;
import com.example.batch.sdk.retry.SdkRetryableHandler;
import com.example.batch.sdk.task.CancellationSignal;
import com.example.batch.sdk.task.ProgressReporter;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * 任务派发器 — Kafka consumer 收到 {@link TaskDispatchMessage} 后:claim → execute handler → report。
 *
 * <p>关键设计:
 *
 * <ul>
 *   <li>**固定线程池**({@link BatchPlatformClientConfig#getMaxConcurrentTasks()} 大小),防 worker 进程被无限并发拖垮
 *   <li>handler 抛任何异常都被 catch + 转 {@link SdkTaskResult#fail(Throwable)} + report failure →
 *       orchestrator 推 FAILED
 *   <li>找不到 handler 的 taskType → report failure "no handler for taskType=X" + log ERROR(诊断用)
 *   <li>graceful shutdown:`stop()` 不接新消息,等当前 in-flight 任务完成(timeout 30s)再关线程池
 * </ul>
 */
@Slf4j
public class TaskDispatcher {

  private final BatchPlatformClientConfig config;
  private final Map<String, SdkTaskHandler> handlers;
  private final PlatformHttpClient httpClient;
  private final ExecutorService executor;
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

  /**
   * Lane J:高频路径(claim 5xx 重试、平台态门控、4xx 持续等)log 节流;同 key 在 60s 内只放行第一条, 其余抑制并计数,下次放行附 suppressed
   * 计数。窗口选 60s 是「人眼可读 + 不丢运维线索」折中。
   */
  private final ThrottledLogger throttledLog = ThrottledLogger.create(log, Duration.ofSeconds(60));

  /**
   * SDK-P4-1:in-flight task 的取消信号表。{@link LeaseRenewalScheduler} 读到 renew response 的 {@code
   * cancelRequested=true} 或 lease 被回收(404/410)时,经 {@link #markCancelled(Long)} 翻转;handler 在长循环里调
   * {@code ctx.isCancelled()} 感知,主动停。
   */
  private final Map<Long, CancellationSignal> cancellations = new ConcurrentHashMap<>();

  /**
   * SDK-P4-2:in-flight task 的进度上报槽表。handler 经 {@code ctx.reportProgress(..)} 写最新快照;{@link
   * LeaseRenewalScheduler} 每次续租 tick 经 {@link #progressSnapshot(Long)} 读出,作为 renew 请求体的 {@code
   * details} 捎给平台。task 结束随 cancellations 一起清理。
   */
  private final Map<Long, ProgressReporter> progressReporters = new ConcurrentHashMap<>();

  /** P0 hardening:stop() 后置 true,onMessage 拒新消息(Zeebe JobWorker.close 模式)。 */
  private final AtomicBoolean draining = new AtomicBoolean(false);

  /**
   * P1-2 fail-fast:CLAIM 收到 401/403 后置 true,后续 onMessage 直接拒收(等 K8s liveness probe 拉起或运维人工介入)。 与
   * {@link #draining} 区分:fatal 是"不可恢复",draining 是"主动 stop"。两者都让 onMessage 静默 drop。
   */
  private final AtomicBoolean fatal = new AtomicBoolean(false);

  /**
   * P7-2:CLAIM / REPORT 连续(非鉴权、非 409)4xx 客户端错误计数。达 {@link
   * BatchPlatformClientConfig#getClientErrorFailFastThreshold()} → 置 {@link #fatal};任一次成功调用归零。
   */
  private final AtomicInteger consecutiveClientErrors = new AtomicInteger(0);

  /**
   * SDK Phase 2 §2.4:平台指令驱动的 4 态状态机。心跳回包(见 {@link HeartbeatDirective})每次 tick 更新此态; {@code PAUSED}
   * / {@code DRAINING} 时 {@link #onMessage} 拒新任务,{@link KafkaTaskConsumer} 据此 pause partition(不丢
   * offset,可恢复)。区别于 {@link #draining}(本地主动 stop)/ {@link #fatal}(不可恢复鉴权失效)。
   */
  private final AtomicReference<WorkerRuntimeState> platformState =
      new AtomicReference<>(WorkerRuntimeState.NORMAL);

  /** MDC keys 公开给测试断言。 */
  static final String MDC_TRACE_ID = "traceId";

  static final String MDC_TENANT_ID = "tenantId";
  static final String MDC_TASK_ID = "taskId";

  /** 当前正在执行的 taskId 快照 — 给 {@link LeaseRenewalScheduler} 读。 */
  public Set<Long> inFlightTaskIds() {
    return Set.copyOf(inFlight);
  }

  /** 当前 in-flight 任务数 — 给 {@link HeartbeatScheduler} 读。 */
  public int inFlightCount() {
    return inFlight.size();
  }

  /**
   * SDK Phase 2 §2.4:心跳回包驱动状态机。由 {@link HeartbeatScheduler} 每次 tick 调用,把平台指令映射到 {@link
   * WorkerRuntimeState};态变化时 log INFO(运维可见 console 暂停 / 排空的秒级生效)。
   */
  public void applyPlatformDirective(HeartbeatDirective directive) {
    if (directive == null) return;
    WorkerRuntimeState next = directive.toRuntimeState();
    WorkerRuntimeState prev = platformState.getAndSet(next);
    if (prev != next) {
      log.info(
          "platform directive: worker runtime state {} -> {} (platformStatus={}, shouldDrain={})",
          prev,
          next,
          directive.platformStatus(),
          directive.shouldDrain());
    }
  }

  /** 当前平台指令态 — 给 {@link KafkaTaskConsumer} backpressure 与测试读。 */
  public WorkerRuntimeState platformState() {
    return platformState.get();
  }

  /** 平台是否允许认领新任务(NORMAL / DEGRADED 是,PAUSED / DRAINING 否)。 */
  public boolean platformAcceptsNewTasks() {
    return platformState.get().acceptsNewTasks();
  }

  public TaskDispatcher(
      BatchPlatformClientConfig config,
      Map<String, SdkTaskHandler> handlers,
      PlatformHttpClient httpClient) {
    this(config, handlers, httpClient, null);
  }

  /**
   * SDK-P5 auto-wrap:注册时自动探测 handler 上的 {@link Idempotent} / {@link RetryOn} 注解并织入装饰器,接入方无需手工
   * {@code wrap}。织入顺序固定 <b>idempotent 外 / retry 内</b>:幂等先查 key,未命中才进重试执行单元,重试到成功后记一次幂等。
   *
   * @param idempotencyStore 声明式幂等所需的去重存储;若没有任何 handler 标 {@link Idempotent} 可传 {@code null},否则在此构造期
   *     fail-fast(见 {@link SdkIdempotentHandler#wrapAround})
   */
  public TaskDispatcher(
      BatchPlatformClientConfig config,
      Map<String, SdkTaskHandler> handlers,
      PlatformHttpClient httpClient,
      SdkIdempotencyStore idempotencyStore) {
    this.config = config;
    Map<String, SdkTaskHandler> decorated = new HashMap<>(handlers.size());
    handlers.forEach((type, handler) -> decorated.put(type, decorate(handler, idempotencyStore)));
    this.handlers = Map.copyOf(decorated);
    this.httpClient = httpClient;
    this.executor =
        Executors.newFixedThreadPool(
            config.getMaxConcurrentTasks(), namedThreadFactory("batch-sdk-dispatch"));
  }

  /**
   * 按声明式注解给单个 handler 织入装饰器链。两个 {@code wrapAround} 的 {@code source} 都传原始 {@code h}(注解只在原始 class 上),
   * {@code delegate} 串起来形成 idempotent(retry(h)) 的链。
   */
  private static SdkTaskHandler decorate(SdkTaskHandler h, SdkIdempotencyStore store) {
    SdkTaskHandler chain = SdkRetryableHandler.wrapAround(h, h); // 内层:retry
    chain = SdkIdempotentHandler.wrapAround(h, chain, store); // 外层:idempotent
    return chain;
  }

  /** 收到一条派单消息 — 提交到线程池异步处理(返回快,Kafka consumer 不阻塞)。 */
  public void onMessage(TaskDispatchMessage msg) {
    if (draining.get() || fatal.get()) {
      // P0 hardening:已发起 stop 或 P1-2 fail-fast 后,不接新消息
      log.info(
          "dispatcher {} , skipping new dispatch msg taskId={}, jobCode={}",
          fatal.get() ? "fatal" : "draining",
          msg == null ? null : msg.taskId(),
          msg == null ? null : msg.jobCode());
      return;
    }
    if (!platformState.get().acceptsNewTasks()) {
      // Phase 2 §2.4:平台 PAUSED / DRAINING — 拒新任务。正常路径下 KafkaTaskConsumer 已 pause partition
      // 不再投递,此处是防御性兜底(pause 生效前可能有消息已在途)。高频路径走 throttledLog 防刷屏。
      throttledLog.info(
          "platform_state_" + platformState.get(),
          "dispatcher platformState={}, skipping new dispatch msg taskId={}",
          platformState.get(),
          msg == null ? null : msg.taskId());
      return;
    }
    try {
      msg.validate();
    } catch (IllegalArgumentException ex) {
      log.warn("skipping invalid dispatch message: {}", ex.getMessage());
      return;
    }
    // Lane J §J1:租户自检 fail-safe。Kafka topic 模式 `batch.task.dispatch.<tenant>.*` + consumer group
    // + ACL 已隔离;若 consumer group 配置失误或 ACL 漂移导致拿到非本租户消息,这里 ERROR + drop,
    // 不 ack offset 留给后续 redeliver / 人工介入,本进程不处理避免串任务。
    if (!config.getTenantId().equals(msg.tenantId())) {
      log.error(
          "tenant_mismatch_drop: configured={} got={} taskId={} — possible ACL drift or consumer"
              + " group misconfiguration",
          config.getTenantId(),
          msg.tenantId(),
          msg.taskId());
      return;
    }
    executor.execute(() -> processInWorkerThread(msg));
  }

  /** 单消息处理:claim → execute → report。所有异常都被 catch。 */
  void processInWorkerThread(TaskDispatchMessage msg) {
    // P0 hardening:把 trace 信息塞 MDC,所有 handler 日志(claim/execute/report)自动带 traceId/tenantId/taskId
    setupMdc(msg);
    try {
      processCore(msg);
    } finally {
      clearMdc();
    }
  }

  private void processCore(TaskDispatchMessage msg) {
    SdkTaskHandler handler = handlers.get(msg.taskType());
    if (handler == null) {
      log.error(
          "no SdkTaskHandler for taskType={} (registered: {}); reporting failure",
          msg.taskType(),
          handlers.keySet());
      reportFailure(msg, "no handler registered for taskType=" + msg.taskType(), null);
      return;
    }

    // CLAIM — body 对齐 TaskController.TaskClaimRequest(tenantId/workerId/partitionInvocationId)
    String idemClaim = BatchPlatformClient.newIdempotencyKey();
    Map<String, Object> claimBody = new HashMap<>();
    claimBody.put("tenantId", msg.tenantId());
    claimBody.put(
        "workerId", config.getWorkerCode()); // ADR-035 §9:workerId==workerCode(P4 后 server 分配)
    if (msg.runtimeAttributes() != null) {
      Object pInv = msg.runtimeAttributes().get("partitionInvocationId");
      if (pInv != null) claimBody.put("partitionInvocationId", pInv.toString());
    }
    if (!claimWithRetry(msg, idemClaim, claimBody)) {
      return;
    }
    inFlight.add(msg.taskId());
    CancellationSignal cancellation = new CancellationSignal();
    cancellations.put(msg.taskId(), cancellation);
    ProgressReporter progress = new ProgressReporter();
    progressReporters.put(msg.taskId(), progress);

    // EXECUTE
    SdkTaskContext ctx =
        new SdkTaskContext(
            msg.tenantId(),
            msg.jobCode(),
            msg.taskInstanceId(),
            msg.taskId(),
            config.getWorkerCode(),
            msg.parameters() == null ? Map.of() : msg.parameters(),
            msg.runtimeAttributes() == null ? Map.of() : msg.runtimeAttributes(),
            msg.schedulingContext(),
            cancellation,
            progress);

    SdkTaskResult result;
    try {
      result = handler.execute(ctx);
      if (result == null) {
        log.warn(
            "handler {} returned null SdkTaskResult, treating as failure",
            handler.getClass().getName());
        result = SdkTaskResult.fail("handler returned null");
      }
    } catch (Throwable t) {
      // 任意异常兜底(含 OOM / 业务 RuntimeException)— 防 dispatcher 线程因业务问题死
      log.error(
          "handler {} threw exception on taskId={}", handler.getClass().getName(), msg.taskId(), t);
      result = SdkTaskResult.fail(t);
    }

    // REPORT — body 对齐 TaskExecutionReportDto(taskId/tenantId/workerId/success/message/outputs/...)
    String idemReport = BatchPlatformClient.newIdempotencyKey();
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("taskId", msg.taskId());
      body.put("tenantId", msg.tenantId());
      body.put("workerId", config.getWorkerCode());
      body.put("success", result.success());
      body.put("message", result.message());
      body.put("outputs", result.output()); // 对齐 TaskExecutionReportDto.outputs
      if (result.error() != null) {
        body.put("errorCode", result.error().getClass().getSimpleName());
        body.put("resultSummary", result.error().getMessage());
      }
      httpClient.report(msg.taskId(), idemReport, body);
      resetClientErrorStreak();
    } catch (PlatformHttpException httpEx) {
      // REPORT 失败:orchestrator 会因 lease 超时自动 retry 派单。非鉴权 4xx 计入连续错误,持续则 fail-fast(P7-2)。
      log.error(
          "report failed (HTTP {}) for taskId={}, orchestrator will retry on lease timeout",
          httpEx.statusCode(),
          msg.taskId(),
          httpEx);
      if (httpEx.isClientError() && !httpEx.isAuthError() && !httpEx.isConflict()) {
        recordClientError(httpEx.statusCode(), msg.taskId(), "REPORT");
      }
    } catch (Exception reportEx) {
      // 传输层 / 其它错误:orchestrator lease 超时兜底重派,记 error 给运维查。
      log.error(
          "report failed for taskId={}, orchestrator will retry on lease timeout",
          msg.taskId(),
          reportEx);
    } finally {
      inFlight.remove(msg.taskId());
      cancellations.remove(msg.taskId());
      progressReporters.remove(msg.taskId());
    }
  }

  /**
   * SDK-P4-1:平台请求取消(renew response {@code cancelRequested=true} / lease 被回收)时,由 {@link
   * LeaseRenewalScheduler} 调用翻转对应 task 的取消信号。task 不在 in-flight(已结束 / 未知)时静默忽略。
   *
   * @param reason 取消来源,仅日志用(如 "platform-cancel" / "lease-revoked")
   */
  public void markCancelled(Long taskId, String reason) {
    if (taskId == null) {
      return;
    }
    CancellationSignal signal = cancellations.get(taskId);
    if (signal != null && !signal.isCancelled()) {
      signal.cancel();
      log.info("task cancellation signalled: taskId={} reason={}", taskId, reason);
    }
  }

  /**
   * SDK-P4-2:读出某 in-flight task 最近一次 {@code ctx.reportProgress(..)} 的快照,给 {@link
   * LeaseRenewalScheduler} 作为续租 {@code details} 捎给平台。task 不在 in-flight 或从未上报 → 返回 null(续租体不带
   * details)。
   */
  public Map<String, Object> progressSnapshot(Long taskId) {
    if (taskId == null) {
      return null;
    }
    ProgressReporter reporter = progressReporters.get(taskId);
    return reporter == null ? null : reporter.latest();
  }

  /**
   * P1-2:CLAIM 重试 + fail-fast 分类。
   *
   * <ul>
   *   <li>401/403 → fail-fast,标记 dispatcher fatal(后续 onMessage 拒收),log ERROR;返回 false
   *   <li>409 → peer 已 claim(正常竞争),log INFO,返回 false(不 report)
   *   <li>其它 4xx → 客户端构造错误(非鉴权),log WARN,返回 false(重试无益)
   *   <li>5xx / 传输错误 → 指数退避重试 {@link BatchPlatformClientConfig#getClaimMax5xxRetries()} 次,
   *       仍失败则放弃(orchestrator 自然会因 lease 超时重派)
   * </ul>
   *
   * @return true=CLAIM 成功可进入 EXECUTE;false=已分类处理,不应继续
   */
  boolean claimWithRetry(TaskDispatchMessage msg, String idemKey, Map<String, Object> body) {
    int maxRetries = Math.max(0, config.getClaimMax5xxRetries());
    long baseDelayMs = Math.max(0L, config.getClaimRetryBaseDelay().toMillis());
    int attempt = 0;
    while (true) {
      try {
        httpClient.claim(msg.taskId(), idemKey, body);
        resetClientErrorStreak();
        return true;
      } catch (PlatformHttpException httpEx) {
        if (httpEx.isAuthError()) {
          // 鉴权失败:apiKey 配错 / 已 revoke → 重试无益,fail-fast 让运维介入(K8s liveness probe 拉起)
          fatal.set(true);
          log.error(
              "CLAIM auth failed (HTTP {}) for taskId={}, marking dispatcher FATAL — "
                  + "check apiKey / tenant ACL; SDK will reject subsequent dispatches",
              httpEx.statusCode(),
              msg.taskId());
          return false;
        }
        if (httpEx.isConflict()) {
          // 409:peer worker 已 claim,正常竞争,不 report(orchestrator 已 owned by peer)
          log.info("CLAIM 409 for taskId={} (taken by peer), skipping", msg.taskId());
          return false;
        }
        if (httpEx.isServerError()) {
          // 5xx:平台侧问题,指数退避重试。耗尽场景在平台抖动时会同时被 N 个 worker 命中,走 throttledLog 防刷屏。
          if (attempt >= maxRetries) {
            throttledLog.warn(
                "claim_5xx_exhausted",
                "CLAIM 5xx (HTTP {}) for taskId={} exhausted {} retries, giving up "
                    + "(orchestrator will redispatch on lease timeout)",
                httpEx.statusCode(),
                msg.taskId(),
                maxRetries);
            return false;
          }
          long delayMs = baseDelayMs << attempt; // 200 / 400 / 800 ms ...
          log.info(
              "CLAIM 5xx (HTTP {}) for taskId={} attempt={} retry in {}ms",
              httpEx.statusCode(),
              msg.taskId(),
              attempt + 1,
              delayMs);
          if (!sleepInterruptible(delayMs)) return false;
          attempt++;
          continue;
        }
        // 其它 4xx(400 / 404 / 422 ...):客户端构造问题,重试无益。SDK/合约不匹配场景下每条 dispatch
        // 都会触发,走 throttledLog 节流(同时 recordClientError 仍正常计数,到阈值会 fatal)。
        throttledLog.warn(
            "claim_client_error_" + httpEx.statusCode(),
            "CLAIM client error (HTTP {}) for taskId={}, giving up: {}",
            httpEx.statusCode(),
            msg.taskId(),
            httpEx.getMessage());
        recordClientError(httpEx.statusCode(), msg.taskId(), "CLAIM");
        return false;
      } catch (IOException ioEx) {
        // 传输层错误(socket / read timeout / 中断包装)— 当 5xx 一样退避重试。网络故障同样会 N worker
        // 同时刷屏,走 throttledLog 节流。
        if (attempt >= maxRetries) {
          throttledLog.warn(
              "claim_transport_exhausted",
              "CLAIM transport error for taskId={} exhausted {} retries, giving up: {}",
              msg.taskId(),
              maxRetries,
              ioEx.getMessage());
          return false;
        }
        long delayMs = baseDelayMs << attempt;
        log.info(
            "CLAIM transport error for taskId={} attempt={} retry in {}ms: {}",
            msg.taskId(),
            attempt + 1,
            delayMs,
            ioEx.getMessage());
        if (!sleepInterruptible(delayMs)) return false;
        attempt++;
      }
    }
  }

  /**
   * P7-2:记一次(非鉴权、非 409)4xx 客户端错误。连续达阈值 → fatal。{@code op} 仅用于日志(CLAIM / REPORT)。 阈值 ≤ 0
   * 时关闭(只计数不触发)。
   */
  private void recordClientError(int statusCode, long taskId, String op) {
    int threshold = config.getClientErrorFailFastThreshold();
    int count = consecutiveClientErrors.incrementAndGet();
    if (threshold > 0 && count >= threshold && fatal.compareAndSet(false, true)) {
      log.error(
          "{} client error (HTTP {}) for taskId={} reached {} consecutive 4xx — marking dispatcher"
              + " FATAL; likely SDK/contract mismatch, SDK will reject subsequent dispatches and"
              + " report unhealthy for K8s restart",
          op,
          statusCode,
          taskId,
          count);
    }
  }

  /** P7-2:一次成功的 CLAIM / REPORT 归零连续 4xx 计数。 */
  private void resetClientErrorStreak() {
    consecutiveClientErrors.set(0);
  }

  /** 可被 interrupt 打断的 sleep;true=正常 sleep 完,false=被打断(应放弃重试)。 */
  private static boolean sleepInterruptible(long ms) {
    if (ms <= 0) return true;
    try {
      Thread.sleep(ms);
      return true;
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void reportFailure(TaskDispatchMessage msg, String message, Throwable error) {
    String idem = BatchPlatformClient.newIdempotencyKey();
    try {
      Map<String, Object> body = new HashMap<>();
      body.put("taskId", msg.taskId());
      body.put("tenantId", msg.tenantId());
      body.put("workerId", config.getWorkerCode());
      body.put("success", false);
      body.put("message", message);
      if (error != null) {
        body.put("errorCode", error.getClass().getSimpleName());
        body.put("resultSummary", error.getMessage());
      }
      httpClient.report(msg.taskId(), idem, body);
    } catch (Exception ex) {
      log.error("reportFailure failed for taskId={}: {}", msg.taskId(), ex.getMessage());
    }
  }

  /** 优雅停 — 默认 30s 超时,内部转 {@link #stop(Duration)}。 */
  public void stop() {
    stop(Duration.ofSeconds(30));
  }

  /**
   * 优雅停 — 不接新任务(draining flag),等 in-flight 完成(超时由调用方给定),强制关。
   *
   * <p>P0 hardening(配合 {@link com.example.batch.sdk.client.BatchPlatformClient#stop(Duration)}):
   * 超时未结束时打 WARN 并列出未完成的 in-flight task id,便于运维排查 K8s SIGKILL 前哪些任务被打断。
   */
  public void stop(Duration timeout) {
    draining.set(true); // P0 hardening:立刻拒新消息(Kafka 已 polled 出还没 dispatch 的会走 onMessage skip)
    long millis = Math.max(0L, timeout == null ? 0L : timeout.toMillis());
    log.info(
        "TaskDispatcher draining + stopping, in-flight={}, timeoutMs={}", inFlight.size(), millis);
    executor.shutdown();
    try {
      if (!executor.awaitTermination(millis, TimeUnit.MILLISECONDS)) {
        log.warn(
            "TaskDispatcher drain timeout {}ms exceeded, forcing shutdown;"
                + " unfinished inFlightTaskIds={}",
            millis,
            inFlightTaskIds());
        executor.shutdownNow();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
  }

  /** P0 hardening — MDC trace 透传。 */
  private static void setupMdc(TaskDispatchMessage msg) {
    if (msg == null) return;
    if (msg.runtimeAttributes() != null) {
      Object trace = msg.runtimeAttributes().get(MDC_TRACE_ID);
      if (trace != null) MDC.put(MDC_TRACE_ID, trace.toString());
    }
    if (msg.tenantId() != null) MDC.put(MDC_TENANT_ID, msg.tenantId());
    if (msg.taskId() != null) MDC.put(MDC_TASK_ID, String.valueOf(msg.taskId()));
  }

  private static void clearMdc() {
    MDC.remove(MDC_TRACE_ID);
    MDC.remove(MDC_TENANT_ID);
    MDC.remove(MDC_TASK_ID);
  }

  /** 暴露给测试 + 调用方:draining 状态(stop() 已发起,等待 in-flight 跑完)。 */
  public boolean isDraining() {
    return draining.get();
  }

  /** 暴露给测试 + 调用方:fatal 状态(401/403 触发,不可恢复)。 */
  public boolean isFatal() {
    return fatal.get();
  }

  /** P7-2:当前连续(非鉴权、非 409)4xx 客户端错误计数,暴露给测试断言。 */
  int consecutiveClientErrors() {
    return consecutiveClientErrors.get();
  }

  private static ThreadFactory namedThreadFactory(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }
}
