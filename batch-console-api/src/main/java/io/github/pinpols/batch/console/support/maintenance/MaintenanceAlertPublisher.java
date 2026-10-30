package io.github.pinpols.batch.console.support.maintenance;

import io.github.pinpols.batch.common.http.OutboundAddressPolicy;
import io.github.pinpols.batch.common.http.OutboundHttpContent;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.support.http.ConsoleOutboundTransport;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 将共享维护状态同步为有限时长的 Alertmanager 抑制源。
 *
 * <p>此信号只控制通知，不关闭规则或修改业务告警状态。固定标签保证跨副本幂等；
 * endsAt 取维护窗口，不能按每次心跳续期，Console 停机后也会到期解除抑制。
 */
@Component
@Slf4j
public class MaintenanceAlertPublisher {

  private static final Duration REPUBLISH_INTERVAL = Duration.ofSeconds(30);
  private final MaintenanceStateHolder stateHolder;
  private final AlertmanagerNotifyProperties.Silence properties;
  private final OutboundHttpTransport transport;
  private final Clock clock;
  private volatile boolean lastPublishSucceeded;
  private long publishedVersion = Long.MIN_VALUE;
  private boolean publishedActive;
  private Instant nextPublishAt = Instant.EPOCH;

  @Autowired
  public MaintenanceAlertPublisher(
      MaintenanceStateHolder stateHolder,
      AlertmanagerNotifyProperties properties,
      @ConsoleOutboundTransport OutboundHttpTransport transport,
      MeterRegistry registry) {
    this(stateHolder, properties, transport, registry, Clock.systemUTC());
  }

  MaintenanceAlertPublisher(
      MaintenanceStateHolder stateHolder,
      AlertmanagerNotifyProperties properties,
      OutboundHttpTransport transport,
      MeterRegistry registry,
      Clock clock) {
    this.stateHolder = stateHolder;
    this.properties = properties.getSilence();
    this.transport = transport;
    this.clock = clock;
    Gauge.builder(
            "batch.console.maintenance.alert_suppression_available",
            this,
            publisher -> publisher.lastPublishSucceeded ? 1.0 : 0.0)
        .description("Whether the last maintenance signal publication succeeded")
        .register(registry);
    Gauge.builder(
            "batch.console.maintenance.alert_suppression_deadline_seconds",
            this,
            MaintenanceAlertPublisher::deadlineSeconds)
        .description("Fixed notification suppression deadline; zero when maintenance is disabled")
        .register(registry);
  }

  /** 使用 Spring 托管调度器；共享状态和 HTTP 客户端仍按原所有权关闭。 */
  @Scheduled(fixedDelay = 5000)
  public synchronized void reconcile() {
    MaintenanceState state = stateHolder.current();
    boolean configured = properties.isEnabled()
        && Texts.hasText(properties.getApiBaseUrl())
        && Texts.hasText(properties.getMaintenanceScope());
    if (!configured || !state.sharedStateAvailable()) {
      lastPublishSucceeded = false;
      return;
    }
    Instant now = clock.instant();
    MaintenanceAlertWindow window =
        MaintenanceAlertWindow.from(state, properties.getDefaultDurationMinutes());
    boolean active = state.enabled() && window.activeAt(now);
    if (publishedVersion == state.version()
        && publishedActive == active
        && now.isBefore(nextPublishAt)) {
      return;
    }
    List<MaintenanceSignal> signals = buildSignals(state, window, now, active);
    try {
      String body = JsonUtils.toJson(signals);
      String baseUrl = properties.getApiBaseUrl().replaceAll("/+$", "");
      Duration connectTimeout = Duration.ofMillis(properties.getConnectTimeoutMillis());
      Duration requestTimeout = Duration.ofMillis(properties.getTimeoutMillis());
      OutboundHttpContent content =
          new OutboundHttpContent(body, "application/json; charset=utf-8");
      OutboundHttpRequest request = OutboundHttpRequest.post(
          baseUrl + "/api/v2/alerts",
          Map.of(),
          content,
          connectTimeout,
          requestTimeout,
          OutboundAddressPolicy.TRUSTED);
      OutboundHttpResponse response = transport.execute(request);
      if (!response.isSuccessful()) {
        lastPublishSucceeded = false;
        log.warn(
            "maintenance alert signal rejected: status={}, version={}",
            response.statusCode(),
            state.version());
        return;
      }
      lastPublishSucceeded = !active || EmptyChecks.isNotEmpty(window.jobs());
      publishedVersion = state.version();
      publishedActive = active;
      nextPublishAt = now.plus(REPUBLISH_INTERVAL);
    } catch (IOException | RuntimeException failure) {
      lastPublishSucceeded = false;
      log.warn(
          "maintenance alert signal publication failed: version={}, cause={}",
          state.version(),
          SwallowedExceptionLogger.summary(failure));
    }
  }

  private List<MaintenanceSignal> buildSignals(
      MaintenanceState state, MaintenanceAlertWindow window, Instant now, boolean active) {
    List<MaintenanceSignal> signals = new ArrayList<>();
    // 每次同时解除不在新范围内的旧信号；重启和多副本无需依赖本地记忆恢复范围。
    for (String job : MaintenanceAlertWindow.APPLICATION_JOBS) {
      boolean suppressed = active && window.jobs().contains(job);
      Instant end = suppressed ? window.endsAt() : now;
      Instant start = window.startsAt().isBefore(end) ? window.startsAt() : end.minusSeconds(1);
      Map<String, String> labels = Map.of(
          "alertname",
          "BatchPlannedMaintenance",
          "job",
          job,
          "maintenance_scope",
          properties.getMaintenanceScope(),
          "severity",
          "info",
          "team",
          "batch-platform",
          "alert_group",
          "maintenance");
      Map<String, String> annotations =
          Map.of("summary", "计划维护通知抑制信号", "maintenance_version", Long.toString(state.version()));
      String startsAt = start.toString();
      String endsAt = end.toString();
      signals.add(new MaintenanceSignal(labels, annotations, startsAt, endsAt));
    }
    return signals;
  }

  private double deadlineSeconds() {
    MaintenanceState state = stateHolder.current();
    if (!state.enabled()) {
      return 0.0;
    }
    MaintenanceAlertWindow window =
        MaintenanceAlertWindow.from(state, properties.getDefaultDurationMinutes());
    return window.endsAt().getEpochSecond();
  }

  private record MaintenanceSignal(
      Map<String, String> labels,
      Map<String, String> annotations,
      String startsAt,
      String endsAt) {}
}
