package com.example.batch.orchestrator.infrastructure.lineage;

import com.example.batch.common.enums.WorkflowRunStatus;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.config.OpenLineageProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * OpenLineage 血缘 emitter(v0.1)。在 workflow_run 终态 emit 一条 spec-compliant OpenLineage RunEvent
 * (COMPLETE / FAIL),fire-and-forget 异步 POST 到 {@link OpenLineageProperties#getEndpoint()}。
 *
 * <p><b>不引 openlineage-java 客户端</b>:SB4 + JDK25 兼容性未验,且事件格式是文档化的 JSON,用 Jackson 手搓 spec-compliant
 * payload + JDK HttpClient 发送即可,零新依赖。后续要 facets / Marquez 深度集成再换官方 client。
 *
 * <p><b>绝不阻塞主链</b>:disabled 时方法直接 return;enabled 时提交到独立线程池,池满({@link
 * RejectedExecutionException})即丢,所有异常 swallow。血缘是 observability,丢几条不影响业务正确性。
 *
 * <p>runId 由 workflow_run id 确定性派生(name-based UUID),将来补 START 事件时与 COMPLETE 同 runId 成对。
 */
@Slf4j
@Component
public class OpenLineageEmitter {

  private static final String SCHEMA_URL =
      "https://openlineage.io/spec/2-0-2/OpenLineage.json#/$defs/RunEvent";
  private static final String METRIC = "openlineage.emit";

  private final OpenLineageProperties props;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final HttpClient httpClient;
  private final ExecutorService executor;

  public OpenLineageEmitter(
      OpenLineageProperties props, ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.props = props;
    this.meterRegistryProvider = meterRegistryProvider;
    if (props.isEnabled() && !props.getEndpoint().isBlank()) {
      this.httpClient =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
              .build();
      ThreadPoolExecutor pool =
          new ThreadPoolExecutor(
              1,
              Math.max(1, props.getEmitThreads()),
              30L,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(256),
              r -> {
                Thread t = new Thread(r, "openlineage-emit");
                t.setDaemon(true);
                return t;
              },
              new ThreadPoolExecutor.AbortPolicy());
      this.executor = pool;
      log.info(
          "OpenLineageEmitter enabled: endpoint={}, namespace={}",
          props.getEndpoint(),
          props.getNamespace());
    } else {
      this.httpClient = null;
      this.executor = null;
    }
  }

  /** workflow_run 进入终态时调用。disabled / 非终态 / endpoint 空 → no-op。 */
  public void emitWorkflowTerminal(
      WorkflowRunEntity run, String terminalStatus, Instant finishedAt) {
    if (executor == null || run == null || terminalStatus == null) {
      return;
    }
    try {
      executor.execute(() -> sendQuietly(run, terminalStatus, finishedAt));
    } catch (RejectedExecutionException ex) {
      // 池满:丢弃 + 计数,不回压主链。
      record("rejected", terminalStatus);
      SwallowedExceptionLogger.info(
          OpenLineageEmitter.class, "catch:RejectedExecutionException", ex);
    }
  }

  private void sendQuietly(WorkflowRunEntity run, String terminalStatus, Instant finishedAt) {
    try {
      String body = JsonUtils.toJson(buildRunEvent(run, terminalStatus, finishedAt));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(props.getEndpoint()))
              .timeout(Duration.ofMillis(props.getRequestTimeoutMs()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<Void> resp = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      int sc = resp.statusCode();
      if (sc >= 200 && sc < 300) {
        record("success", terminalStatus);
      } else {
        record("http_error", terminalStatus);
        log.warn("OpenLineage emit non-2xx: status={}, workflowRunId={}", sc, run.getId());
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      record("interrupted", terminalStatus);
      SwallowedExceptionLogger.info(OpenLineageEmitter.class, "catch:InterruptedException", ex);
    } catch (RuntimeException | java.io.IOException ex) {
      record("error", terminalStatus);
      SwallowedExceptionLogger.warn(OpenLineageEmitter.class, "catch:emit", ex);
    }
  }

  /** 构建 spec-compliant OpenLineage RunEvent(COMPLETE / FAIL)。 */
  Map<String, Object> buildRunEvent(
      WorkflowRunEntity run, String terminalStatus, Instant finishedAt) {
    boolean success =
        WorkflowRunStatus.SUCCESS.code().equals(terminalStatus)
            || WorkflowRunStatus.SUCCESS_DRY_RUN.code().equals(terminalStatus);
    Instant eventTime = finishedAt != null ? finishedAt : Instant.now();

    Map<String, Object> nominalTime = new LinkedHashMap<>();
    if (run.getStartedAt() != null) {
      nominalTime.put("nominalStartTime", run.getStartedAt().toString());
    }
    if (finishedAt != null) {
      nominalTime.put("nominalEndTime", finishedAt.toString());
    }

    Map<String, Object> runFacets = new LinkedHashMap<>();
    if (!nominalTime.isEmpty()) {
      nominalTime.put("_producer", props.getProducer());
      nominalTime.put(
          "_schemaURL",
          "https://openlineage.io/spec/facets/1-0-0/NominalTimeRunFacet.json#/$defs/NominalTimeRunFacet");
      runFacets.put("nominalTime", nominalTime);
    }

    Map<String, Object> runNode = new LinkedHashMap<>();
    runNode.put("runId", deterministicRunId(run.getId()));
    if (!runFacets.isEmpty()) {
      runNode.put("facets", runFacets);
    }

    Map<String, Object> jobFacets = new LinkedHashMap<>();
    Map<String, Object> docFacet = new LinkedHashMap<>();
    docFacet.put("_producer", props.getProducer());
    docFacet.put(
        "_schemaURL",
        "https://openlineage.io/spec/facets/1-0-0/DocumentationJobFacet.json#/$defs/DocumentationJobFacet");
    docFacet.put(
        "description",
        "workflow_run id="
            + run.getId()
            + " tenant="
            + run.getTenantId()
            + " bizDate="
            + run.getBizDate()
            + " traceId="
            + run.getTraceId());
    jobFacets.put("documentation", docFacet);

    Map<String, Object> jobNode = new LinkedHashMap<>();
    jobNode.put("namespace", props.getNamespace());
    jobNode.put("name", jobName(run));
    jobNode.put("facets", jobFacets);

    Map<String, Object> event = new LinkedHashMap<>();
    event.put("eventType", success ? "COMPLETE" : "FAIL");
    event.put("eventTime", eventTime.toString());
    event.put("run", runNode);
    event.put("job", jobNode);
    event.put("inputs", List.of());
    event.put("outputs", List.of());
    event.put("producer", props.getProducer());
    event.put("schemaURL", SCHEMA_URL);
    return event;
  }

  private String jobName(WorkflowRunEntity run) {
    String tenant = run.getTenantId() == null ? "unknown" : run.getTenantId();
    return "workflow." + tenant + ".def" + run.getWorkflowDefinitionId();
  }

  static String deterministicRunId(Long workflowRunId) {
    return UUID.nameUUIDFromBytes(
            ("file-batch-system:workflow-run:" + workflowRunId).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private void record(String outcome, String terminalStatus) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    Counter.builder(METRIC)
        .tags(Tags.of("outcome", outcome, "status", terminalStatus))
        .register(registry)
        .increment();
  }
}
