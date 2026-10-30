package io.github.pinpols.batch.console.support.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.http.OutboundHttpRequest;
import io.github.pinpols.batch.common.http.OutboundHttpResponse;
import io.github.pinpols.batch.common.http.OutboundHttpTransport;
import io.github.pinpols.batch.console.config.AlertmanagerNotifyProperties;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceStateHolder.MaintenanceState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("维护抑制信号：范围隔离、提前结束与失败重试")
class MaintenanceAlertPublisherTest {
  private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
  private MaintenanceStateHolder holder;
  private OutboundHttpTransport transport;
  private AlertmanagerNotifyProperties properties;
  private SimpleMeterRegistry registry;
  private MaintenanceAlertPublisher publisher;
  private final ObjectMapper objectMapper = new ObjectMapper();

  @BeforeEach
  void setUp() throws IOException {
    holder = mock(MaintenanceStateHolder.class);
    transport = mock(OutboundHttpTransport.class);
    properties = new AlertmanagerNotifyProperties();
    properties.getSilence().setApiBaseUrl("http://alertmanager.example.invalid:9093");
    properties.getSilence().setMaintenanceScope("test-deployment");
    registry = new SimpleMeterRegistry();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    publisher = new MaintenanceAlertPublisher(holder, properties, transport, registry, clock);
  }

  @Test
  @DisplayName("活动窗口只发布指定服务的抑制源，其他服务同时解除旧信号")
  void shouldPublishBoundedSignalForSelectedJob() throws IOException {
    MaintenanceState state =
        state(true, NOW.minusSeconds(60), NOW.plusSeconds(600), List.of("trigger"), 1L, true);
    when(holder.current()).thenReturn(state);
    when(transport.execute(any())).thenReturn(new OutboundHttpResponse(200, ""));
    publisher.reconcile();
    JsonNode signals = capturedSignals();
    assertThat(signals.size()).isEqualTo(8);
    JsonNode trigger = signalFor(signals, "batch-trigger");
    assertThat(trigger.path("endsAt").asText()).isEqualTo("2026-10-26T10:10:00Z");
    assertThat(trigger.path("labels").path("maintenance_scope").asText())
        .isEqualTo("test-deployment");
    JsonNode worker = signalFor(signals, "batch-worker-import");
    assertThat(worker.path("endsAt").asText()).isEqualTo(NOW.toString());
    assertThat(available()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("提前结束维护即发送相同身份的解除信号，不关闭原业务告警")
  void shouldResolveSignalsAfterMaintenanceDisabled() throws IOException {
    MaintenanceState state =
        state(false, NOW.minusSeconds(60), NOW.plusSeconds(600), List.of("trigger"), 2L, true);
    when(holder.current()).thenReturn(state);
    when(transport.execute(any())).thenReturn(new OutboundHttpResponse(200, ""));
    publisher.reconcile();
    JsonNode signals = capturedSignals();
    for (JsonNode signal : signals) {
      assertThat(signal.path("labels").path("alertname").asText())
          .isEqualTo("BatchPlannedMaintenance");
      assertThat(signal.path("endsAt").asText()).isEqualTo(NOW.toString());
    }
  }

  @Test
  @DisplayName("计划开始前和维护截止后都不发布活动抑制信号")
  void shouldNotSuppressBeforeStartOrAfterDeadline() throws IOException {
    MaintenanceState future =
        state(true, NOW.plusSeconds(60), NOW.plusSeconds(600), List.of("trigger"), 3L, true);
    when(holder.current()).thenReturn(future);
    when(transport.execute(any())).thenReturn(new OutboundHttpResponse(200, ""));
    publisher.reconcile();
    JsonNode signals = capturedSignals();
    assertThat(signalFor(signals, "batch-trigger").path("endsAt").asText())
        .isEqualTo(NOW.toString());
  }

  @Test
  @DisplayName("缺少部署范围或共享状态未确认时，不发送可能扩大范围的抑制")
  void shouldKeepAlertsWhenScopeOrSharedStateMissing() throws IOException {
    MaintenanceState state =
        state(true, NOW.minusSeconds(60), NOW.plusSeconds(600), List.of("trigger"), 1L, true);
    when(holder.current()).thenReturn(state);
    properties.getSilence().setMaintenanceScope("");
    publisher.reconcile();
    assertThat(available()).isZero();
    verify(transport, never()).execute(any());
    properties.getSilence().setMaintenanceScope("test-deployment");
    MaintenanceState unavailable =
        state(true, NOW.minusSeconds(60), NOW.plusSeconds(600), List.of("trigger"), 1L, false);
    when(holder.current()).thenReturn(unavailable);
    publisher.reconcile();
    verify(transport, never()).execute(any());
  }

  @Test
  @DisplayName("同步失败不记录成功，下次轮询重试相同固定截止时间")
  void shouldRetryAfterHttpFailureWithoutExtendingDeadline() throws IOException {
    MaintenanceState state =
        state(true, NOW.minusSeconds(60), NOW.plusSeconds(600), List.of("trigger"), 1L, true);
    when(holder.current()).thenReturn(state);
    when(transport.execute(any()))
        .thenReturn(new OutboundHttpResponse(503, ""), new OutboundHttpResponse(200, ""));
    publisher.reconcile();
    assertThat(available()).isZero();
    publisher.reconcile();
    assertThat(available()).isEqualTo(1.0);
    ArgumentCaptor<OutboundHttpRequest> requests =
        ArgumentCaptor.forClass(OutboundHttpRequest.class);
    verify(transport, times(2)).execute(requests.capture());
    assertThat(requests.getAllValues().get(0).body())
        .isEqualTo(requests.getAllValues().get(1).body());
  }

  @Test
  @DisplayName("窗口到期后只发送解除信号，不延长原始截止时间")
  void shouldResolveExpiredWindowAndExposeUnknownScope() throws IOException {
    MaintenanceState expired =
        state(true, NOW.minusSeconds(600), NOW, List.of("trigger"), 4L, true);
    when(holder.current()).thenReturn(expired);
    when(transport.execute(any())).thenReturn(new OutboundHttpResponse(200, ""));
    publisher.reconcile();
    JsonNode signals = capturedSignals();
    assertThat(signalFor(signals, "batch-trigger").path("endsAt").asText())
        .isEqualTo(NOW.toString());
  }

  @Test
  @DisplayName("未知服务范围不能被上报为已确认的活动抑制")
  void shouldExposeUnknownServiceScope() throws IOException {
    MaintenanceState unknown =
        state(true, NOW.minusSeconds(60), NOW.plusSeconds(600), List.of("database"), 4L, true);
    when(holder.current()).thenReturn(unknown);
    when(transport.execute(any())).thenReturn(new OutboundHttpResponse(200, ""));
    publisher.reconcile();
    assertThat(available()).isZero();
  }

  @Test
  @DisplayName("网络异常保留未确认状态且后续重试可恢复")
  void shouldRetryAfterTransportFailure() throws IOException {
    MaintenanceState active =
        state(true, NOW.minusSeconds(60), NOW.plusSeconds(600), List.of("trigger"), 4L, true);
    when(holder.current()).thenReturn(active);
    when(transport.execute(any()))
        .thenThrow(new IOException("fixture failure"))
        .thenReturn(new OutboundHttpResponse(200, ""));
    publisher.reconcile();
    assertThat(available()).isZero();
    publisher.reconcile();
    assertThat(available()).isEqualTo(1.0);
  }

  private JsonNode capturedSignals() throws IOException {
    ArgumentCaptor<OutboundHttpRequest> request =
        ArgumentCaptor.forClass(OutboundHttpRequest.class);
    verify(transport).execute(request.capture());
    return objectMapper.readTree(request.getValue().body());
  }

  private JsonNode signalFor(JsonNode signals, String job) {
    for (JsonNode signal : signals) {
      if (job.equals(signal.path("labels").path("job").asText())) {
        return signal;
      }
    }
    throw new AssertionError("缺少服务信号：" + job);
  }

  private double available() {
    return registry
        .get("batch.console.maintenance.alert_suppression_available")
        .gauge()
        .value();
  }

  private MaintenanceState state(
      boolean enabled,
      Instant startsAt,
      Instant etaAt,
      List<String> jobs,
      long version,
      boolean available) {
    return new MaintenanceState(
        enabled, false, "维护", etaAt, startsAt, jobs, version, NOW.minusSeconds(60), available);
  }
}
