package io.github.pinpols.batch.orchestrator.infrastructure.governance;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.utils.AlertLabels;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.AlertmanagerEmitProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * emit 直连 Alertmanager 的旁路 publisher（迁移方案 §6.1，入口那一段）。
 *
 * <p>把刚落库的 {@link AlertEventEntity} 映射成 AM {@code PostableAlert}，异步 {@code POST
 * {endpoint}/api/v2/alerts}。AM 接管「一条 OPEN 告警产生之后 → 通知发出之前」的编排（分组/去重/静默/抑制/路由）。
 *
 * <p><b>失败隔离(硬约束)</b>：整段包在独立 try/catch + 独立线程池，异常只 warn + 打 {@code batch.alert.am_emit}（outcome
 * tag）计数，<b>绝不冒泡污染 emit 事务</b>。DB 落库为准，AM 推送尽力而为 （重复 fire AM 端按 label 集合幂等）。
 *
 * <p><b>事务边界</b>：调用方（{@code DefaultAlertEventService.emit}）在 {@code
 * TransactionSynchronization.afterCommit} 里调本 publisher，保证事务回滚时 AM 不会收到幽灵告警。
 *
 * <p>参照 {@code OpenLineageEmitter} 的 fire-and-forget 形态：不引 AM 官方 client，JDK HttpClient + Jackson 手搓
 * PostableAlert（AM openapi v2）。
 */
@Slf4j
@Component
public class AlertmanagerEmitPublisher {

  private static final String METRIC = "batch.alert.am_emit";
  private static final String ALERTS_PATH = "/api/v2/alerts";

  private final AlertmanagerEmitProperties props;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final HttpClient httpClient;
  private final ExecutorService executor;
  private final String alertsUri;

  public AlertmanagerEmitPublisher(
      AlertmanagerEmitProperties props, ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.props = props;
    this.meterRegistryProvider = meterRegistryProvider;
    if (props.isEnabled() && Texts.hasText(props.getEndpoint())) {
      this.httpClient =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMillis()))
              .build();
      ThreadPoolExecutor pool =
          new ThreadPoolExecutor(
              1,
              Math.max(1, props.getEmitThreads()),
              30L,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(512),
              r -> {
                Thread t = new Thread(r, "am-emit");
                t.setDaemon(true);
                return t;
              },
              new ThreadPoolExecutor.AbortPolicy());
      this.executor = pool;
      this.alertsUri = stripTrailingSlash(props.getEndpoint()) + ALERTS_PATH;
      log.info("AlertmanagerEmitPublisher enabled: endpoint={}", props.getEndpoint());
    } else {
      this.httpClient = null;
      this.executor = null;
      this.alertsUri = null;
      log.info("AlertmanagerEmitPublisher disabled (am-emit.enabled=false or endpoint blank)");
    }
  }

  /** 是否已启用（供 re-emit 调度器短路判断）。 */
  public boolean isEnabled() {
    return executor != null;
  }

  /** 推一条 firing 告警（disabled / null → no-op）。 */
  public void publishFiring(AlertEventEntity entity) {
    if (executor == null || entity == null) {
      return;
    }
    try {
      executor.execute(() -> sendQuietly(entity));
    } catch (RejectedExecutionException ex) {
      record("rejected");
      SwallowedExceptionLogger.info(
          AlertmanagerEmitPublisher.class, "catch:RejectedExecutionException", ex);
    }
  }

  /** 停机时优雅关闭线程池:停收新任务 + 有限等待 drain 已入队的 POST,超时才强制中断(不裸丢队列)。 */
  @PreDestroy
  public void shutdown() {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("AlertmanagerEmitPublisher 线程池未在 5s 内 drain 完,强制中断");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      SwallowedExceptionLogger.info(
          AlertmanagerEmitPublisher.class, "catch:InterruptedException", e);
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void sendQuietly(AlertEventEntity entity) {
    try {
      String body = JsonUtils.toJson(List.of(buildPostableAlert(entity)));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(alertsUri))
              .timeout(Duration.ofMillis(props.getTimeoutMillis()))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
              .build();
      HttpResponse<Void> resp = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      int sc = resp.statusCode();
      if (sc >= 200 && sc < 300) {
        record("success");
      } else {
        record("http_error");
        log.warn("am_emit non-2xx: status={} alertType={}", sc, entity.getAlertType());
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      record("interrupted");
      SwallowedExceptionLogger.info(
          AlertmanagerEmitPublisher.class, "catch:InterruptedException", ex);
    } catch (RuntimeException | java.io.IOException ex) {
      record("failed");
      SwallowedExceptionLogger.warn(AlertmanagerEmitPublisher.class, "catch:am_emit", ex);
    }
  }

  /**
   * 把 alert_event 行映射成 AM PostableAlert（labels + annotations + startsAt）。
   *
   * <p>labels 全是低基数枚举（alertname/tenant/severity/service/alert_group/team），可进 group_by；高基数的
   * resource_key / trace_id / alert_id 一律进 annotations（§4/§8 基数守则）。不带 endsAt = firing。
   */
  Map<String, Object> buildPostableAlert(AlertEventEntity entity) {
    // 与 close 桥接的 resolved 共用同一套规范 label 集(AlertLabels.canonicalLabels),保证 firing/resolved
    // label 集严格一致,否则 AM 匹配不到原 firing(见 canonicalLabels javadoc)。
    Map<String, String> labels = AlertLabels.canonicalLabels(entity);

    Map<String, String> annotations = new LinkedHashMap<>();
    if (Texts.hasText(entity.getTitle())) {
      annotations.put("summary", entity.getTitle());
    }
    if (Texts.hasText(entity.getDetailJson())) {
      annotations.put("description", entity.getDetailJson());
    }
    if (Texts.hasText(entity.getTraceId())) {
      annotations.put("trace_id", entity.getTraceId());
    }
    if (entity.getDedupFingerprint() != null) {
      annotations.put("fingerprint", entity.getDedupFingerprint());
    }
    if (entity.getId() != null) {
      annotations.put("alert_id", String.valueOf(entity.getId()));
    }

    Map<String, Object> alert = new LinkedHashMap<>();
    alert.put("labels", labels);
    alert.put("annotations", annotations);
    // startsAt 用首次/最近观测时间；缺失则由 AM 以接收时刻兜底。不带 endsAt = firing。
    if (entity.getFirstSeenAt() != null) {
      alert.put("startsAt", entity.getFirstSeenAt().toString());
    } else if (entity.getLastSeenAt() != null) {
      alert.put("startsAt", entity.getLastSeenAt().toString());
    }
    return alert;
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private void record(String outcome) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    Counter.builder(METRIC).tags(Tags.of("outcome", outcome)).register(registry).increment();
  }
}
