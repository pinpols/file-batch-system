package com.example.batch.worker.core.infrastructure;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.worker.core.config.OrchestratorTaskClientProperties;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.support.TaskExecutionClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import com.example.batch.common.utils.Texts;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** 调用 orchestrator 内部任务 API，带超时、瞬态失败有限重试及 Micrometer 失败计数。 */
@Component
@Slf4j
public class HttpTaskExecutionClient implements TaskExecutionClient {

  private final OrchestratorTaskClientProperties properties;
  private final BatchSecurityProperties securityProperties;
  private final RestClient.Builder builder;
  private final Environment environment;
  private final Optional<MeterRegistry> meterRegistry;
  // L-2: volatile 保证双重检查锁的可见性，避免其他线程读到未完全构造的 RestClient
  private volatile RestClient restClient;

  public HttpTaskExecutionClient(
      OrchestratorTaskClientProperties properties,
      BatchSecurityProperties securityProperties,
      RestClient.Builder builder,
      Environment environment,
      @Autowired(required = false) MeterRegistry meterRegistry) {
    this.properties = properties;
    this.securityProperties = securityProperties;
    this.builder = builder;
    this.environment = environment;
    this.meterRegistry = Optional.ofNullable(meterRegistry);
  }

  @Override
  public boolean claim(String tenantId, Long taskId, String workerId) {
    String traceId = currentTraceId();
    String resolvedTraceId = Texts.hasText(traceId) ? traceId : IdGenerator.newTraceId();
    return executeClaimLike(
        "claim",
        () ->
            client()
                .post()
                .uri("/internal/tasks/{taskId}/claim", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenantId)
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, resolvedTraceId)
                .body(new ClaimRequest(tenantId, workerId))
                .retrieve()
                .toBodilessEntity());
  }

  @Override
  public boolean renewLease(String tenantId, Long taskId, String workerId) {
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
                .body(new ClaimRequest(tenantId, workerId))
                .retrieve()
                .toBodilessEntity());
  }

  @Override
  public void report(TaskExecutionReport report) {
    // A-3.6 a: REPORT 不入 pipeline_step_run（保留 step_run 纯业务语义），但需要可观测性。
    // 用 micrometer Timer 记录 REPORT 调用耗时 + 结果 tag，运维可通过
    //   batch.worker.report.duration{tenantId=, workerType=, outcome=success|failure}
    // 的 P95/P99 发现 Orchestrator 侧 report 瓶颈或链路中断。
    long reportStartNanos = System.nanoTime();
    String outcome = "success";
    try {
      reportInternal(report);
    } catch (RuntimeException rex) {
      outcome = "failure";
      throw rex;
    } finally {
      recordReportDuration(report, outcome, System.nanoTime() - reportStartNanos);
    }
  }

  private void recordReportDuration(TaskExecutionReport report, String outcome, long durationNanos) {
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
    int max = Math.max(1, properties.getReportMaxAttempts());
    long backoff = Math.max(1L, properties.getReportInitialBackoffMillis());
    long cap = Math.max(backoff, properties.getReportMaxBackoffMillis());
    String traceFallback = currentTraceId();
    for (int attempt = 1; attempt <= max; attempt++) {
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
        if (ex.getStatusCode() == HttpStatus.CONFLICT) {
          // 409 STATE_CONFLICT：orchestrator 乐观锁失败，整个事务已回滚，task/partition 仍是 RUNNING，可以重试。
          recordReportFailure("STATE_CONFLICT");
          if (attempt >= max) {
            logStructuredReportFailure("STATE_CONFLICT", attempt, max, ex);
            throw ex;
          }
          log.warn("orchestrator report state conflict, will retry: attempt={}/{}", attempt, max);
          sleepBackoff(backoff);
          backoff = Math.min(cap, backoff * 2);
          continue;
        }
        if (ex.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
          recordReportFailure("RATE_LIMITED");
          logStructuredReportFailure("RATE_LIMITED", attempt, max, ex);
          throw ex;
        }
        recordReportFailure("CLIENT_ERROR");
        logStructuredReportFailure("CLIENT_ERROR", attempt, max, ex);
        throw ex;
      } catch (HttpServerErrorException ex) {
        recordReportFailure("SERVER_ERROR");
        if (attempt >= max) {
          logStructuredReportFailure("SERVER_ERROR", attempt, max, ex);
          throw ex;
        }
        log.warn(
            "orchestrator report transient failure, will retry: attempt={}/{}, status={},"
                + " message={}",
            attempt,
            max,
            ex.getStatusCode(),
            ex.getMessage());
        sleepBackoff(backoff);
        backoff = Math.min(cap, backoff * 2);
      } catch (ResourceAccessException ex) {
        recordReportFailure("IO_TIMEOUT");
        if (attempt >= max) {
          logStructuredReportFailure("IO_TIMEOUT", attempt, max, ex);
          throw ex;
        }
        log.warn(
            "orchestrator report I/O failure, will retry: attempt={}/{}, message={}",
            attempt,
            max,
            ex.getMessage());
        sleepBackoff(backoff);
        backoff = Math.min(cap, backoff * 2);
      }
    }
  }

  private boolean executeClaimLike(String operation, Runnable call) {
    int max = Math.max(1, properties.getClaimMaxAttempts());
    long backoff = Math.max(1L, properties.getClaimInitialBackoffMillis());
    long cap = Math.max(backoff, properties.getClaimMaxBackoffMillis());
    for (int attempt = 1; attempt <= max; attempt++) {
      try {
        call.run();
        return true;
      } catch (HttpClientErrorException ex) {
        if (ex.getStatusCode() == HttpStatus.CONFLICT
            || ex.getStatusCode() == HttpStatus.NOT_FOUND) {
          return false;
        }
        throw ex;
      } catch (HttpServerErrorException | ResourceAccessException ex) {
        if (attempt >= max) {
          log.warn("{} failed after {} attempts: {}", operation, max, ex.getMessage());
          throw ex instanceof RuntimeException r ? r : new IllegalStateException(ex);
        }
        log.warn(
            "{} transient failure, retrying: attempt={}/{}, message={}",
            operation,
            attempt,
            max,
            ex.getMessage());
        sleepBackoff(backoff);
        backoff = Math.min(cap, backoff * 2);
      }
    }
    throw new IllegalStateException(operation + " exhausted retries without outcome");
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
            builder
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

  private record ClaimRequest(String tenantId, String workerId) {}
}
