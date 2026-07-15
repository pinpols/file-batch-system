package io.github.pinpols.batch.console.domain.notification.service;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.common.utils.AlertLabels;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
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
import java.time.Instant;
import java.util.ArrayList;
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
 * console silence/close → Alertmanager 单向桥接(迁移方案 §3.5 / §6.1)。
 *
 * <p>自研升级 notifier 退役后,AM 成为通知编排的唯一执行方。console 侧对单条 {@code alert_event} 的治理动作必须同步到 AM, 否则只改 fbs 状态而
 * AM 照样 repeat 通知:
 *
 * <ul>
 *   <li><b>silence</b>:{@code POST /api/v2/silences} 建 label matcher(alertname=alert_type +
 *       tenant), 时长取请求或默认;AM 在时窗内压住这一类告警。
 *   <li><b>close</b>:{@code POST /api/v2/alerts} 带 {@code endsAt=now} 发 resolved,让 AM 立即消解,不必等
 *       {@code resolve_timeout}(5m)。
 * </ul>
 *
 * <p><b>失败隔离</b>:异步 + swallow,绝不影响 console 主流程(silence/close 的 fbs 状态流转与审计已在事务内完成)。 反向(AM UI
 * silence 回写 console)不做,属已知语义差,约定 silence 统一从 console 操作。
 */
@Slf4j
@Component
public class AlertmanagerSilenceBridge {

  private static final String METRIC = "batch.alert.am_silence";
  private static final String SILENCES_PATH = "/api/v2/silences";
  private static final String ALERTS_PATH = "/api/v2/alerts";
  private static final String CREATED_BY = "batch-console";

  private final AlertmanagerNotifyProperties.Silence props;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;
  private final HttpClient httpClient;
  private final ExecutorService executor;

  public AlertmanagerSilenceBridge(
      AlertmanagerNotifyProperties properties,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.props = properties.getSilence();
    this.meterRegistryProvider = meterRegistryProvider;
    if (props.isEnabled() && Texts.hasText(props.getApiBaseUrl())) {
      this.httpClient =
          HttpClient.newBuilder()
              .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMillis()))
              .build();
      ThreadPoolExecutor pool =
          new ThreadPoolExecutor(
              1,
              1,
              30L,
              TimeUnit.SECONDS,
              new LinkedBlockingQueue<>(256),
              r -> {
                Thread t = new Thread(r, "am-silence-bridge");
                t.setDaemon(true);
                return t;
              },
              new ThreadPoolExecutor.AbortPolicy());
      this.executor = pool;
      log.info("AlertmanagerSilenceBridge enabled: apiBaseUrl={}", props.getApiBaseUrl());
    } else {
      this.httpClient = null;
      this.executor = null;
      log.info("AlertmanagerSilenceBridge disabled (silence.enabled=false or apiBaseUrl blank)");
    }
  }

  /** console silence → AM silence(disabled / null → no-op)。durationMinutes null 用默认。 */
  public void silence(AlertEventEntity alert, Integer durationMinutes) {
    if (executor == null || alert == null || !Texts.hasText(alert.getAlertType())) {
      return;
    }
    submit(() -> sendSilence(alert, durationMinutes), "silence");
  }

  /** console close → AM resolved(disabled / null → no-op)。 */
  public void resolve(AlertEventEntity alert) {
    if (executor == null || alert == null || !Texts.hasText(alert.getAlertType())) {
      return;
    }
    submit(() -> sendResolved(alert), "resolve");
  }

  /** 停机时优雅关闭线程池:停收新任务 + 有限等待 drain 已入队的 silence/resolved POST,超时才强制中断。 */
  @PreDestroy
  public void shutdown() {
    if (executor == null) {
      return;
    }
    executor.shutdown();
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("AlertmanagerSilenceBridge 线程池未在 5s 内 drain 完,强制中断");
        executor.shutdownNow();
      }
    } catch (InterruptedException e) {
      SwallowedExceptionLogger.info(
          AlertmanagerSilenceBridge.class, "catch:InterruptedException", e);
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  private void submit(Runnable task, String action) {
    try {
      executor.execute(task);
    } catch (RejectedExecutionException ex) {
      record("rejected", action);
      SwallowedExceptionLogger.info(
          AlertmanagerSilenceBridge.class, "catch:RejectedExecutionException", ex);
    }
  }

  private void sendSilence(AlertEventEntity alert, Integer durationMinutes) {
    int minutes =
        durationMinutes != null && durationMinutes > 0
            ? durationMinutes
            : props.getDefaultDurationMinutes();
    Instant now = Instant.now();
    List<Map<String, Object>> matchers = new ArrayList<>();
    matchers.add(matcher("alertname", alert.getAlertType()));
    if (Texts.hasText(alert.getTenantId())) {
      matchers.add(matcher("tenant", alert.getTenantId()));
    }
    Map<String, Object> silence = new LinkedHashMap<>();
    silence.put("matchers", matchers);
    silence.put("startsAt", now.toString());
    silence.put("endsAt", now.plusSeconds((long) minutes * 60).toString());
    silence.put("createdBy", CREATED_BY);
    silence.put(
        "comment",
        "console silence for alert_event id=" + alert.getId() + " type=" + alert.getAlertType());
    post(stripTrailingSlash(props.getApiBaseUrl()) + SILENCES_PATH, silence, "silence");
  }

  private void sendResolved(AlertEventEntity alert) {
    Instant now = Instant.now();
    // I-1:必须与 emit publisher 的 firing label 集严格一致(含 service),否则 AM 匹配不到原 firing,只新建
    // 幽灵 resolved,原告警仍等 resolve_timeout。共用 AlertLabels.canonicalLabels 保证一致。
    Map<String, String> labels = AlertLabels.canonicalLabels(alert);
    Map<String, Object> resolved = new LinkedHashMap<>();
    resolved.put("labels", labels);
    // endsAt=now → AM 立即判定 resolved,不必等 resolve_timeout。
    resolved.put("endsAt", now.toString());
    post(stripTrailingSlash(props.getApiBaseUrl()) + ALERTS_PATH, List.of(resolved), "resolve");
  }

  private Map<String, Object> matcher(String name, String value) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("value", value);
    m.put("isRegex", false);
    m.put("isEqual", true);
    return m;
  }

  private void post(String uri, Object body, String action) {
    try {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(uri))
              .timeout(Duration.ofMillis(props.getTimeoutMillis()))
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      JsonUtils.toJson(body), StandardCharsets.UTF_8))
              .build();
      HttpResponse<Void> resp = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      int sc = resp.statusCode();
      if (sc >= 200 && sc < 300) {
        record("success", action);
      } else {
        record("http_error", action);
        log.warn("am_silence bridge non-2xx: action={} status={}", action, sc);
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      record("interrupted", action);
      SwallowedExceptionLogger.info(
          AlertmanagerSilenceBridge.class, "catch:InterruptedException", ex);
    } catch (RuntimeException | java.io.IOException ex) {
      record("failed", action);
      SwallowedExceptionLogger.warn(AlertmanagerSilenceBridge.class, "catch:am_silence", ex);
    }
  }

  private static String stripTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  private void record(String outcome, String action) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    Counter.builder(METRIC)
        .tags(Tags.of("outcome", outcome, "action", action))
        .register(registry)
        .increment();
  }
}
