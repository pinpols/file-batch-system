package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.AlertEventEntity;
import io.github.pinpols.batch.orchestrator.mapper.AlertEventMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("告警升级 sweep")
class DefaultAlertEventServiceEscalationTest {

  @Mock private AlertEventMapper alertEventMapper;

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private DefaultAlertEventService service() {
    return new DefaultAlertEventService(alertEventMapper, meterRegistry);
  }

  private static AlertEventEntity overdueAlert(long id, Integer tier) {
    AlertEventEntity entity = new AlertEventEntity();
    entity.setId(id);
    entity.setTenantId("t1");
    entity.setAlertType("SLA_BREACH");
    entity.setSeverity("WARN");
    entity.setStatus("OPEN");
    entity.setEscalationTier(tier);
    return entity;
  }

  @Test
  @DisplayName("逐条升级并按受影响行数计数")
  void shouldEscalateEachOverdueAlert_whenCasSucceeds() {
    // arrange
    when(alertEventMapper.selectOverdueForEscalation(30, 3, 200))
        .thenReturn(List.of(overdueAlert(1L, 0), overdueAlert(2L, 1)));
    when(alertEventMapper.markEscalated(eq(1L), eq("t1"), eq(0))).thenReturn(1);
    when(alertEventMapper.markEscalated(eq(2L), eq("t1"), eq(1))).thenReturn(1);

    // act
    int escalated = service().escalateOverdue(30, 3, 200);

    // assert
    assertThat(escalated).isEqualTo(2);
    assertThat(meterRegistry.find("batch.alert.escalations").counters()).isNotEmpty();
  }

  @Test
  @DisplayName("CAS 落空(被并发 ack/抢先升级)不计入")
  void shouldSkipAlert_whenCasReturnsZero() {
    // arrange
    when(alertEventMapper.selectOverdueForEscalation(30, 3, 200))
        .thenReturn(List.of(overdueAlert(1L, 0), overdueAlert(2L, 0)));
    when(alertEventMapper.markEscalated(eq(1L), eq("t1"), eq(0))).thenReturn(0);
    when(alertEventMapper.markEscalated(eq(2L), eq("t1"), eq(0))).thenReturn(1);

    // act
    int escalated = service().escalateOverdue(30, 3, 200);

    // assert
    assertThat(escalated).isEqualTo(1);
  }

  @Test
  @DisplayName("null tier 视为 0,expectedTier 守护传 0")
  void shouldTreatNullTierAsZero() {
    // arrange
    when(alertEventMapper.selectOverdueForEscalation(30, 3, 200))
        .thenReturn(List.of(overdueAlert(1L, null)));
    when(alertEventMapper.markEscalated(eq(1L), eq("t1"), eq(0))).thenReturn(1);

    // act
    int escalated = service().escalateOverdue(30, 3, 200);

    // assert
    assertThat(escalated).isEqualTo(1);
    verify(alertEventMapper).markEscalated(1L, "t1", 0);
  }

  @Test
  @DisplayName("非法参数直接短路,不查库")
  void shouldShortCircuit_whenParamsInvalid() {
    // act
    int escalated = service().escalateOverdue(0, 3, 200);

    // assert
    assertThat(escalated).isZero();
    verify(alertEventMapper, never()).selectOverdueForEscalation(anyInt(), anyInt(), anyInt());
  }
}
