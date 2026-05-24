package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.EffectiveTaskConfig;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.config.OrchestratorTaskClientProperties;
import com.example.batch.worker.core.config.WorkerLeaseProperties;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxCoordinator;
import com.example.batch.worker.core.support.TaskExecutionClient;
import com.example.batch.worker.core.support.TaskLeaseRenewItem;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** 调用 orchestrator 内部任务 API，带超时、瞬态失败有限重试及 Micrometer 失败计数。 */
@Component
@Slf4j
public class HttpTaskExecutionClient
    implements TaskExecutionClient, OrchestratorReportHttpSubmitter {

  private final OrchestratorTaskClientProperties properties;
  private final BatchSecurityProperties securityProperties;
  private final ObjectProvider<RestClient.Builder> restClientBuilderProvider;
  private final Environment environment;
  private final Optional<MeterRegistry> meterRegistry;
  private final ObjectProvider<WorkerReportOutboxCoordinator> reportOutboxCoordinator;
  private final int renewBatchMaxItems;
  // L-2: volatile 保证双重检查锁的可见性，避免其他线程读到未完全构造的 RestClient
  private volatile RestClient restClient;

  public HttpTaskExecutionClient(
      OrchestratorTaskClientProperties properties,
      BatchSecurityProperties securityProperties,
      ObjectProvider<RestClient.Builder> restClientBuilderProvider,
      Environment environment,
      @Autowired(required = false) MeterRegistry meterRegistry,
      ObjectProvider<WorkerReportOutboxCoordinator> reportOutboxCoordinator,
      WorkerLeaseProperties leaseProperties) {
    this.properties = properties;
    this.securityProperties = securityProperties;
    this.restClientBuilderProvider = restClientBuilderProvider;
    this.environment = environment;
    this.meterRegistry = Optional.ofNullable(meterRegistry);
    this.reportOutboxCoordinator = reportOutboxCoordinator;
    this.renewBatchMaxItems = Math.max(1, leaseProperties.getRenewBatchMaxItems());
  }

  /**
   * 旧 orchestrator(P1-2.1 之前)claim 返空 body 时回的占位:fields 全 null,worker 走 fallback 到
   * TaskDispatchMessage 旧字段。语义上等价于"claim 成功但无 fresh config"。
   */
  private static final EffectiveTaskConfig EMPTY_EFFECTIVE_CONFIG =
      new EffectiveTaskConfig(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null, null, null, null, null, null, null, null, null);

  @Override
  public Optional<EffectiveTaskConfig> claim(String tenantId, Long taskId, String workerId) {
    String traceId = currentTraceId();
    String resolvedTraceId = Texts.hasText(traceId) ? traceId : IdGenerator.newTraceId();
    ClaimOutcome<EffectiveTaskConfig> outcome =
        executeClaimLike(
            "claim",
            () ->
                client()
                    .post()
                    .uri("/internal/tasks/{taskId}/claim", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
                    .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, resolvedTraceId)
                    .body(new ClaimRequest(tenantId, workerId, null))
                    .retrieve()
                    .body(EffectiveTaskConfig.class));
    if (!outcome.success()) {
      return Optional.empty();
    }
    return Optional.of(outcome.body() != null ? outcome.body() : EMPTY_EFFECTIVE_CONFIG);
  }

  @Override
  public boolean renewLease(
      String tenantId, Long taskId, String workerId, String partitionInvocationId) {
    String traceId = currentTraceId();
    String resolvedTraceId = Texts.hasText(traceId) ? traceId : IdGenerator.newTraceId();
    return executeClaimLike(
            "renew",
            () ->
                client()
                    .post()
                    .uri("/internal/tasks/{taskId}/renew", taskId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
                    .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, resolvedTraceId)
                    .body(new ClaimRequest(tenantId, workerId, partitionInvocationId))
                    .retrieve()
                    .toBodilessEntity())
        .success();
  }

  @Override
  public Map<Long, Boolean> renewLeasesBatch(List<TaskLeaseRenewItem> items) {
    if (items == null || items.isEmpty()) {
      return Map.of();
    }
    Map<Long, Boolean> out = new LinkedHashMap<>();
    for (int i = 0; i < items.size(); i += renewBatchMaxItems) {
      int end = Math.min(i + renewBatchMaxItems, items.size());
      List<TaskLeaseRenewItem> chunk = items.subList(i, end);
      out.putAll(renewBatchChunkHttpOrFallback(chunk));
    }
    return out;
  }

  /**
   * 单 chunk：优先 {@code POST /leases/renew-batch}；404/400、响应缺项/长度不一致、重试耗尽后降级为逐条 {@link #renewLease}。
   */
  private Map<Long, Boolean> renewBatchChunkHttpOrFallback(List<TaskLeaseRenewItem> chunk) {
    RetryState state =
        RetryState.initial(
            properties.getClaimMaxAttempts(),
            properties.getClaimInitialBackoffMillis(),
            properties.getClaimMaxBackoffMillis());
    while (state.canAttempt()) {
      try {
        List<BatchRenewHttpItem> payload = chunk.stream().map(this::toHttpItem).toList();
        String traceId = currentTraceId();
        String resolvedTraceId = Texts.hasText(traceId) ? traceId : IdGenerator.newTraceId();
        BatchRenewHttpResponse response =
            client()
                .post()
                .uri("/internal/tasks/leases/renew-batch")
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, chunk.get(0).tenantId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, resolvedTraceId)
                .body(new BatchRenewHttpRequest(payload))
                .retrieve()
                .body(BatchRenewHttpResponse.class);
        return mapChunkOrFallback(chunk, response);
      } catch (HttpClientErrorException ex) {
        if (ex.getStatusCode() == HttpStatus.NOT_FOUND
            || ex.getStatusCode() == HttpStatus.BAD_REQUEST) {
          log.debug(
              "renew-batch not supported or bad request ({}), falling back to single renew",
              ex.getStatusCode());
          return fallbackRenewChunk(chunk);
        }
        throw ex;
      } catch (HttpServerErrorException | ResourceAccessException ex) {
        if (state.isLastAttempt()) {
          log.warn(
              "renew-batch transient failure after {} attempts, falling back to single renew: {}",
              state.max(),
              ex.getMessage());
          return fallbackRenewChunk(chunk);
        }
        log.warn(
            "renew-batch transient, retrying: attempt={}/{}, message={}",
            state.attempt(),
            state.max(),
            ex.getMessage());
        sleepBackoff(state.backoff());
        state = state.advance();
      }
    }
    return fallbackRenewChunk(chunk);
  }

  private Map<Long, Boolean> mapChunkOrFallback(
      List<TaskLeaseRenewItem> chunk, BatchRenewHttpResponse response) {
    if (response == null
        || response.results() == null
        || response.results().size() != chunk.size()) {
      log.warn(
          "renew-batch response mismatch: chunk size {}, response results {}",
          chunk.size(),
          response == null || response.results() == null ? null : response.results().size());
      return fallbackRenewChunk(chunk);
    }
    Map<Long, Boolean> m = new LinkedHashMap<>();
    for (int i = 0; i < chunk.size(); i++) {
      BatchRenewHttpResult row = response.results().get(i);
      m.put(chunk.get(i).taskId(), row != null && row.renewed());
    }
    return m;
  }

  private Map<Long, Boolean> fallbackRenewChunk(List<TaskLeaseRenewItem> chunk) {
    Map<Long, Boolean> m = new LinkedHashMap<>();
    for (TaskLeaseRenewItem item : chunk) {
      boolean ok =
          renewLease(item.tenantId(), item.taskId(), item.workerId(), item.partitionInvocationId());
      m.put(item.taskId(), ok);
    }
    return m;
  }

  private BatchRenewHttpItem toHttpItem(TaskLeaseRenewItem item) {
    return new BatchRenewHttpItem(
        item.tenantId(), item.taskId(), item.workerId(), item.partitionInvocationId());
  }

  @Override
  public void report(TaskExecutionReport report) {
    // A-3.6 a: REPORT 不入 pipeline_step_run（保留 step_run 纯业务语义），但需要可观测性。
    // 用 micrometer Timer 记录 REPORT 调用耗时 + 结果 tag，运维可通过
    //   batch.worker.report.duration{tenantId=, workerType=, outcome=success|failure}
    // 的 P95/P99 发现 Orchestrator 侧 report 瓶颈或链路中断。
    //
    // P0 #16 (2026-05-23): 三段式失败链。
    //   1) HTTP 重试耗尽 → 尝试 outbox.enqueue（ADR-015），由 pollDeferredReports 后台重投。
    //   2) outbox 不可用（未启用 / 持久化失败）→ **不再** 把异常抛回 listener。
    //      之前抛 RuntimeException → AbstractTaskConsumer 识别为 transient → return false → Kafka
    //      不提交 offset → 重投 dispatch 消息 → 同 task 在 finally 块清完 lease 后立即被重新 CLAIM,
    //      task 业务 body 被双执行（已落库数据被覆盖 / 重做）。
    //      改为吞掉异常 + ERROR 日志 + worker.report.dropped.total 计数：让 Kafka 正常 ack offset,
    //      丢失的 REPORT 由 orchestrator lease 过期自动 reclaim 兜底（reclaim 是 lease-timeout 后的
    //      延迟重派,不是即时重派,且 orchestrator 视角看到的是 CLAIM 状态超时,可走它自己的
    //      去重/状态机校验,而不是 Kafka 重投绕过状态机）。
    //   双执行从"必然零延迟"降级为"可能延迟 reclaim",配合 outbox 启用时基本消除。
    long reportStartNanos = System.nanoTime();
    String outcome = "success";
    try {
      submitReportOverHttp(report);
    } catch (RuntimeException rex) {
      WorkerReportOutboxCoordinator coordinator = reportOutboxCoordinator.getIfAvailable();
      if (coordinator != null && coordinator.enqueue(report)) {
        outcome = "deferred_outbox";
      } else {
        outcome = "dropped";
        recordReportDropped(coordinator == null ? "outbox_disabled" : "outbox_enqueue_failed");
        log.error(
            "REPORT dropped after HTTP + outbox failure — relying on orchestrator lease reclaim to"
                + " avoid Kafka-redelivery double-execution: tenantId={}, taskId={}, workerId={},"
                + " reason={}, httpError={}",
            report == null ? null : report.getTenantId(),
            report == null ? null : report.getTaskId(),
            report == null ? null : report.getWorkerId(),
            coordinator == null ? "outbox_disabled" : "outbox_enqueue_failed",
            rex.toString());
      }
    } finally {
      recordReportDuration(report, outcome, System.nanoTime() - reportStartNanos);
    }
  }

  private void recordReportDropped(String reason) {
    meterRegistry.ifPresent(
        registry -> registry.counter("worker.report.dropped.total", "reason", reason).increment());
  }

  @Override
  public void submitReportOverHttp(TaskExecutionReport report) {
    reportInternal(report);
  }

  private void recordReportDuration(
      TaskExecutionReport report, String outcome, long durationNanos) {
    meterRegistry.ifPresent(
        registry ->
            Timer.builder("batch.worker.report.duration")
                .tags(
                    Tags.of(
                        "tenantId",
                        report == null || report.getTenantId() == null
                            ? "unknown"
                            : report.getTenantId(),
                        "outcome",
                        outcome))
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS));
  }

  private void reportInternal(TaskExecutionReport report) {
    RetryState state =
        RetryState.initial(
            properties.getReportMaxAttempts(),
            properties.getReportInitialBackoffMillis(),
            properties.getReportMaxBackoffMillis());
    String traceFallback = currentTraceId();
    while (state.canAttempt()) {
      String traceId = firstNonBlank(report.getTraceId(), traceFallback);
      if (!Texts.hasText(traceId)) {
        traceId = IdGenerator.newTraceId();
      }
      try {
        client()
            .post()
            .uri("/internal/tasks/{taskId}/report", report.getTaskId())
            .contentType(MediaType.APPLICATION_JSON)
            .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, report.getTenantId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId)
            .body(report)
            .retrieve()
            .toBodilessEntity();
        return;
      } catch (HttpClientErrorException ex) {
        SwallowedExceptionLogger.info(
            HttpTaskExecutionClient.class, "catch:HttpClientErrorException", ex);

        if (ex.getStatusCode() == HttpStatus.CONFLICT) {
          // 409 STATE_CONFLICT：orchestrator 乐观锁失败，整个事务已回滚，task/partition 仍是 RUNNING，可以重试。
          state = handleRetryableReportFailure("STATE_CONFLICT", "state conflict", state, ex);
          continue;
        }
        if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
          // R6 P0-7：429 是 orchestrator 端 sliding-window 限流的瞬时拒绝，绝不是终态错误。
          // 之前直接 failReportImmediately 等于把 worker 的 REPORT 数据扔掉，task 在 orchestrator
          // 视角永远卡 RUNNING — 只能靠 lease 过期 + 重 CLAIM 兜底，rolling 高峰会把整批 task 都丢丢。
          // 改为按可重试路径走退避：与 503 / IO 超时一致，让 worker 退避后重投 REPORT。
          state = handleRetryableReportFailure("RATE_LIMITED", "rate limited", state, ex);
          continue;
        }
        failReportImmediately("CLIENT_ERROR", state, ex);
      } catch (HttpServerErrorException ex) {
        state = handleRetryableReportFailure("SERVER_ERROR", "transient failure", state, ex);
      } catch (ResourceAccessException ex) {
        SwallowedExceptionLogger.info(
            HttpTaskExecutionClient.class, "catch:ResourceAccessException", ex);

        state = handleRetryableReportFailure("IO_TIMEOUT", "I/O failure", state, ex);
      }
    }
  }

  /**
   * 记录失败指标；若已达最大尝试次数直接抛；否则等待退避并返回下次 attempt 的 state。 {@code reasonCode} 进 metrics，{@code retryHint}
   * 进 log。
   */
  private RetryState handleRetryableReportFailure(
      String reasonCode, String retryHint, RetryState state, RuntimeException ex) {
    recordReportFailure(reasonCode);
    if (state.isLastAttempt()) {
      logStructuredReportFailure(reasonCode, state.attempt(), state.max(), ex);
      throw ex;
    }
    log.warn(
        "orchestrator report {}, will retry: attempt={}/{}, error={}",
        retryHint,
        state.attempt(),
        state.max(),
        ex.getMessage());
    sleepBackoff(state.backoff());
    return state.advance();
  }

  private void failReportImmediately(String reasonCode, RetryState state, RuntimeException ex) {
    recordReportFailure(reasonCode);
    logStructuredReportFailure(reasonCode, state.attempt(), state.max(), ex);
    throw ex;
  }

  private <T> ClaimOutcome<T> executeClaimLike(String operation, Supplier<T> call) {
    RetryState state =
        RetryState.initial(
            properties.getClaimMaxAttempts(),
            properties.getClaimInitialBackoffMillis(),
            properties.getClaimMaxBackoffMillis());
    while (state.canAttempt()) {
      try {
        // body 可能为 null(旧 orchestrator 返 bodyless;或 renew 路径用 toBodilessEntity)。
        // 这里只用 success() 区分"4xx 失败"vs"成功(body 是 null 还是 record 由 caller 处理)"。
        return ClaimOutcome.success(call.get());
      } catch (HttpClientErrorException ex) {
        if (ex.getStatusCode() == HttpStatus.CONFLICT
            || ex.getStatusCode() == HttpStatus.NOT_FOUND) {
          return ClaimOutcome.failed();
        }
        throw ex;
      } catch (HttpServerErrorException | ResourceAccessException ex) {
        if (state.isLastAttempt()) {
          log.warn("{} failed after {} attempts: {}", operation, state.max(), ex.getMessage());
          throw ex instanceof RuntimeException r ? r : new IllegalStateException(ex);
        }
        log.warn(
            "{} transient failure, retrying: attempt={}/{}, message={}",
            operation,
            state.attempt(),
            state.max(),
            ex.getMessage());
        sleepBackoff(state.backoff());
        state = state.advance();
      }
    }
    throw new IllegalStateException(operation + " exhausted retries without outcome");
  }

  /** claim/renew 调用结果:success=false 即 HTTP 4xx;body 仅 success=true 时有效,可能为 null。 */
  private record ClaimOutcome<T>(boolean success, T body) {
    static <T> ClaimOutcome<T> failed() {
      return new ClaimOutcome<>(false, null);
    }

    static <T> ClaimOutcome<T> success(T body) {
      return new ClaimOutcome<>(true, body);
    }
  }

  /** 重试循环的不可变状态：attempt 从 1 起，backoff 每轮乘 2 但夹在 cap 内。 {@link #advance()} 返回下一轮的新 state。 */
  record RetryState(int attempt, int max, long backoff, long cap) {
    static RetryState initial(int configuredMax, long initialBackoff, long maxBackoff) {
      int normalizedMax = Math.max(1, configuredMax);
      long normalizedBackoff = Math.max(1L, initialBackoff);
      long normalizedCap = Math.max(normalizedBackoff, maxBackoff);
      return new RetryState(1, normalizedMax, normalizedBackoff, normalizedCap);
    }

    boolean canAttempt() {
      return attempt <= max;
    }

    boolean isLastAttempt() {
      return attempt >= max;
    }

    RetryState advance() {
      return new RetryState(attempt + 1, max, Math.min(cap, backoff * 2), cap);
    }
  }

  private void recordReportFailure(String reason) {
    meterRegistry.ifPresent(
        reg -> reg.counter("worker.report.failed.total", "reason", reason).increment());
  }

  private void logStructuredReportFailure(String reasonCode, int attempt, int max, Exception ex) {
    log.warn(
        "orchestrator report aborted: reasonCode={}, attempt={}/{}, error={}",
        reasonCode,
        attempt,
        max,
        ex.toString());
  }

  private static void sleepBackoff(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted during orchestrator client backoff", ie);
    }
  }

  private String currentTraceId() {
    String value = MDC.get(StructuredLogField.TRACE_ID);
    return Texts.hasText(value) ? value : null;
  }

  private static String firstNonBlank(String value, String fallback) {
    if (Texts.hasText(value)) {
      return value;
    }
    return Texts.hasText(fallback) ? fallback : null;
  }

  private RestClient client() {
    RestClient current = this.restClient;
    if (current != null) {
      return current;
    }
    synchronized (this) {
      if (this.restClient == null) {
        JdkClientHttpRequestFactory factory =
            new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMillis()))
                    .build());
        factory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMillis()));
        this.restClient =
            restClientBuilderProvider
                .getObject()
                .baseUrl(resolveBaseUrl())
                .defaultHeader("X-Internal-Secret", securityProperties.getInternalSecret())
                .requestFactory(factory)
                .build();
      }
      return this.restClient;
    }
  }

  private String resolveBaseUrl() {
    String configuredBaseUrl = properties.getBaseUrl();
    if (Texts.hasText(configuredBaseUrl) && !configuredBaseUrl.contains("${")) {
      return configuredBaseUrl;
    }
    String localPort = environment.getProperty("local.server.port");
    if (Texts.hasText(localPort)) {
      return "http://127.0.0.1:" + localPort;
    }
    throw new IllegalStateException(
        "Unable to resolve batch.worker.task-client.base-url for task execution client");
  }

  private record ClaimRequest(String tenantId, String workerId, String partitionInvocationId) {}

  private record BatchRenewHttpRequest(List<BatchRenewHttpItem> items) {}

  private record BatchRenewHttpItem(
      String tenantId, Long taskId, String workerId, String partitionInvocationId) {}

  private record BatchRenewHttpResponse(List<BatchRenewHttpResult> results) {}

  private record BatchRenewHttpResult(Long taskId, boolean renewed) {}
}
