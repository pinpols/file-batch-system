package com.example.batch.sdk.dispatcher;

import com.example.batch.sdk.client.BatchPlatformClient;
import com.example.batch.sdk.client.BatchPlatformClientConfig;
import com.example.batch.sdk.internal.PlatformHttpClient;
import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

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

  /** 当前正在执行的 taskId 快照 — 给 {@link LeaseRenewalScheduler} 读。 */
  public Set<Long> inFlightTaskIds() {
    return Set.copyOf(inFlight);
  }

  /** 当前 in-flight 任务数 — 给 {@link HeartbeatScheduler} 读。 */
  public int inFlightCount() {
    return inFlight.size();
  }

  public TaskDispatcher(
      BatchPlatformClientConfig config,
      Map<String, SdkTaskHandler> handlers,
      PlatformHttpClient httpClient) {
    this.config = config;
    this.handlers = Map.copyOf(handlers);
    this.httpClient = httpClient;
    this.executor =
        Executors.newFixedThreadPool(
            config.getMaxConcurrentTasks(), namedThreadFactory("batch-sdk-dispatch"));
  }

  /** 收到一条派单消息 — 提交到线程池异步处理(返回快,Kafka consumer 不阻塞)。 */
  public void onMessage(TaskDispatchMessage msg) {
    try {
      msg.validate();
    } catch (IllegalArgumentException ex) {
      log.warn("skipping invalid dispatch message: {}", ex.getMessage());
      return;
    }
    executor.execute(() -> processInWorkerThread(msg));
  }

  /** 单消息处理:claim → execute → report。所有异常都被 catch。 */
  void processInWorkerThread(TaskDispatchMessage msg) {
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
    try {
      Map<String, Object> claimBody = new HashMap<>();
      claimBody.put("tenantId", msg.tenantId());
      claimBody.put(
          "workerId", config.getWorkerCode()); // ADR-035 §9:workerId==workerCode(P4 后 server 分配)
      if (msg.runtimeAttributes() != null) {
        Object pInv = msg.runtimeAttributes().get("partitionInvocationId");
        if (pInv != null) claimBody.put("partitionInvocationId", pInv.toString());
      }
      httpClient.claim(msg.taskId(), idemClaim, claimBody);
      inFlight.add(msg.taskId());
    } catch (Exception claimEx) {
      // CLAIM 失败通常是别 worker 已 claim 走,正常竞争,不 report(orchestrator 已 owned)
      log.info(
          "claim failed for taskId={} (likely taken by peer): {}",
          msg.taskId(),
          claimEx.getMessage());
      return;
    }

    // EXECUTE
    SdkTaskContext ctx =
        new SdkTaskContext(
            msg.tenantId(),
            msg.jobCode(),
            msg.taskInstanceId(),
            msg.taskId(),
            config.getWorkerCode(),
            msg.parameters() == null ? Map.of() : msg.parameters(),
            msg.runtimeAttributes() == null ? Map.of() : msg.runtimeAttributes());

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
    } catch (Exception reportEx) {
      // REPORT 失败:orchestrator 会因为 lease 超时自动 retry 派单。我们已尽力,记 error 给运维查。
      log.error(
          "report failed for taskId={}, orchestrator will retry on lease timeout",
          msg.taskId(),
          reportEx);
    } finally {
      inFlight.remove(msg.taskId());
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

  /** 优雅停 — 不接新任务,等 in-flight 完成(timeout 30s),强制关。 */
  public void stop() {
    log.info("TaskDispatcher stopping, draining in-flight tasks");
    executor.shutdown();
    try {
      if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
        log.warn("TaskDispatcher drain timeout 30s, forcing shutdown");
        executor.shutdownNow();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      executor.shutdownNow();
    }
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
