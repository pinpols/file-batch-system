package io.github.pinpols.batch.orchestrator.infrastructure.governance;

import static io.github.pinpols.batch.testing.TestHttpTransports.failOnRequest;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.config.AlertmanagerEmitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

@DisplayName("AlertmanagerEmitPublisher: PostableAlert 映射 + 开关")
class AlertmanagerEmitPublisherTest {

  @SuppressWarnings("unchecked")
  private ObjectProvider<MeterRegistry> meterProvider(MeterRegistry registry) {
    StaticListableBeanFactory bf = new StaticListableBeanFactory();
    if (registry != null) {
      bf.addBean("meterRegistry", registry);
    }
    return (ObjectProvider<MeterRegistry>) bf.getBeanProvider(MeterRegistry.class);
  }

  private AlertmanagerEmitPublisher publisher(boolean enabled) {
    AlertmanagerEmitProperties props = new AlertmanagerEmitProperties();
    props.setEnabled(enabled);
    props.setEndpoint("http://localhost:9093");
    return new AlertmanagerEmitPublisher(
        props, meterProvider(new SimpleMeterRegistry()), failOnRequest());
  }

  private AlertEventEntity entity() {
    AlertEventEntity e = new AlertEventEntity();
    e.setId(42L);
    e.setTenantId("ta");
    e.setServiceName("batch-orchestrator");
    e.setAlertType("JOB_SLA_BREACH");
    e.setSeverity("CRITICAL");
    e.setTitle("SLA breached");
    e.setDetailJson("{\"jobId\":7}");
    e.setDedupFingerprint("fp-123");
    e.setTraceId("trace-xyz");
    e.setFirstSeenAt(Instant.parse("2026-07-15T00:00:00Z"));
    return e;
  }

  @Test
  @DisplayName("labels 保留实体指纹与 severity 词形;追踪信息进 annotations")
  void buildPostableAlert_mapsLabelsAndAnnotations() {
    Map<String, Object> alert = publisher(true).buildPostableAlert(entity());

    @SuppressWarnings("unchecked")
    Map<String, String> labels = (Map<String, String>) alert.get("labels");
    assertThat(labels)
        .containsEntry("alertname", "JOB_SLA_BREACH")
        .containsEntry("tenant", "ta")
        .containsEntry("severity", "critical")
        .containsEntry("service", "batch-orchestrator")
        .containsEntry("alert_group", "sla")
        .containsEntry("team", "batch-sla");
    assertThat(labels).containsEntry("fingerprint", "fp-123");
    assertThat(labels).doesNotContainKeys("resource", "trace_id", "alert_id");

    @SuppressWarnings("unchecked")
    Map<String, String> ann = (Map<String, String>) alert.get("annotations");
    assertThat(ann)
        .containsEntry("summary", "SLA breached")
        .containsEntry("description", "{\"jobId\":7}")
        .containsEntry("trace_id", "trace-xyz")
        .containsEntry("fingerprint", "fp-123")
        .containsEntry("alert_id", "42");

    // firing:带 startsAt,不带 endsAt
    assertThat(alert).containsKey("startsAt").doesNotContainKey("endsAt");
  }

  @Test
  @DisplayName("恢复重试保持原标签、首次时刻和持久化关闭时间")
  void shouldKeepResolvedIdentityAndTimeStable() {
    AlertmanagerEmitPublisher publisher = publisher(false);
    AlertEventEntity entity = entity();
    entity.setStatus("CLOSED");
    entity.setClosedAt(Instant.parse("2026-10-25T01:00:00Z"));
    Map<String, Object> firing = publisher.buildPostableAlert(entity);
    Map<String, Object> resolved = publisher.buildResolvedAlert(entity);
    Map<String, Object> retried = publisher.buildResolvedAlert(entity);
    assertThat(resolved)
        .containsEntry("labels", firing.get("labels"))
        .containsEntry("startsAt", firing.get("startsAt"))
        .containsEntry("endsAt", "2026-10-25T01:00:00Z");
    assertThat(retried).isEqualTo(resolved);
  }

  @Test
  @DisplayName("disabled 时 isEnabled=false 且 publishFiring no-op(不抛)")
  void disabledPublisher_isNoOp() {
    AlertmanagerEmitPublisher p = publisher(false);
    assertThat(p.isEnabled()).isFalse();
    p.publishFiring(entity()); // 不应抛异常
  }
}
