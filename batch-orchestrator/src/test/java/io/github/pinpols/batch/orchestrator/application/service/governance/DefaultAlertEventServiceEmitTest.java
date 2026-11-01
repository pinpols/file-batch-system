package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.domain.command.AlertEmitCommand;
import io.github.pinpols.batch.orchestrator.infrastructure.governance.AlertmanagerEmitPublisher;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("emit 直连 AM 旁路")
class DefaultAlertEventServiceEmitTest {

  @Mock
  private AlertEventMapper alertEventMapper;

  @Mock
  private AlertmanagerEmitPublisher publisher;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private DefaultAlertEventService service() {
    return new DefaultAlertEventService(alertEventMapper, meterRegistry, publisher);
  }

  @Test
  @DisplayName("落库后把告警推给 AM publisher(无活跃事务时直推)")
  void emit_publishesToAlertmanagerAfterInsert() {
    AlertEmitCommand request = AlertEmitCommand.builder()
        .tenantId("ta")
        .alertType("JOB_SLA_BREACH")
        .severity("CRITICAL")
        .build();

    AlertEventEntity persisted = new AlertEventEntity();
    persisted.setId(42L);
    persisted.setStatus("OPEN");
    persisted.setFirstSeenAt(Instant.parse("2026-10-25T00:00:00Z"));
    when(alertEventMapper.selectByFingerprint(anyString(), anyString())).thenReturn(persisted);

    service().emit(request);

    verify(alertEventMapper).insertOrMerge(any(AlertEventEntity.class));
    verify(publisher).publishFiring(persisted);
  }

  @Test
  @DisplayName("重复信号命中已关闭告警时不重新发送发生通知")
  void shouldNotRepublishClosedAlert() {
    AlertEventEntity persisted = new AlertEventEntity();
    persisted.setStatus("CLOSED");
    when(alertEventMapper.selectByFingerprint(anyString(), anyString())).thenReturn(persisted);
    AlertEmitCommand request = AlertEmitCommand.builder()
        .tenantId("ta")
        .alertType("JOB_RUNNING_TOO_LONG")
        .resourceKey("42")
        .build();

    service().emit(request);

    verify(publisher, never()).publishFiring(any());
  }

  @Test
  @DisplayName("无效请求(缺 tenant/alertType)不落库也不推 AM")
  void emit_skipsInvalidRequest() {
    AlertEmitCommand invalidRequest = AlertEmitCommand.builder().alertType("X").build();
    service().emit(invalidRequest);

    verify(alertEventMapper, never()).insertOrMerge(any());
    verify(publisher, never()).publishFiring(any());
  }
}
