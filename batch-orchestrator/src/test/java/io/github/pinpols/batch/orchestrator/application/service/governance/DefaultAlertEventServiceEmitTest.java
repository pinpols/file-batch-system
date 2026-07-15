package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.infrastructure.governance.AlertmanagerEmitPublisher;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("emit 直连 AM 旁路")
class DefaultAlertEventServiceEmitTest {

  @Mock private AlertEventMapper alertEventMapper;
  @Mock private AlertmanagerEmitPublisher publisher;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private DefaultAlertEventService service() {
    return new DefaultAlertEventService(alertEventMapper, meterRegistry, publisher);
  }

  @Test
  @DisplayName("落库后把告警推给 AM publisher(无活跃事务时直推)")
  void emit_publishesToAlertmanagerAfterInsert() {
    AlertEmitRequest request =
        AlertEmitRequest.builder()
            .tenantId("ta")
            .alertType("JOB_SLA_BREACH")
            .severity("CRITICAL")
            .build();

    service().emit(request);

    verify(alertEventMapper).insertOrMerge(any(AlertEventEntity.class));
    verify(publisher).publishFiring(any(AlertEventEntity.class));
  }

  @Test
  @DisplayName("无效请求(缺 tenant/alertType)不落库也不推 AM")
  void emit_skipsInvalidRequest() {
    service().emit(AlertEmitRequest.builder().alertType("X").build());

    verify(alertEventMapper, never()).insertOrMerge(any());
    verify(publisher, never()).publishFiring(any());
  }
}
