package com.example.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.common.event.DomainEvent;
import com.example.batch.common.event.DomainEventPublisher;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * P2: TaskDispatchOutboxService 业务行为补强,补 TaskDispatchOutboxServiceMandatoryTest 之外的:
 *
 * <ul>
 *   <li>priority band 路由 (HIGH ≤3 / MEDIUM 4-6 / LOW ≥7)
 *   <li>idempotencyKey resolution (partition 优先 / eventKey 兜底 / 派生 fallback)
 *   <li>eventKey 缺省时用 tenant:taskId 形态
 *   <li>priority 字段:task.priority 优先,jobInstance.priority 兜底
 *   <li>payload v2 字段完整性
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TaskDispatchOutboxServiceBehaviorTest {

  @Mock private DomainEventPublisher domainEventPublisher;
  @Mock private JobTaskMapper jobTaskMapper;

  private TaskDispatchOutboxService service;

  @BeforeEach
  void setUp() {
    service = new TaskDispatchOutboxService(domainEventPublisher, jobTaskMapper);
    ReflectionTestUtils.setField(service, "self", service);
  }

  private JobInstanceEntity instance(int priority) {
    JobInstanceEntity ji = new JobInstanceEntity();
    ji.setId(1L);
    ji.setInstanceNo("inst-001");
    ji.setJobCode("JOB_A");
    ji.setPriority(priority);
    return ji;
  }

  private JobTaskEntity task(Integer priority) {
    JobTaskEntity t = new JobTaskEntity();
    t.setId(10L);
    t.setTenantId("ta");
    t.setTaskType("IMPORT");
    t.setJobInstanceId(1L);
    t.setPriority(priority);
    return t;
  }

  private JobPartitionEntity partition(String idempotencyKey) {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setId(100L);
    p.setIdempotencyKey(idempotencyKey);
    return p;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> parseMsg(String json) {
    return (Map<String, Object>) JsonUtils.fromJson(json, Map.class);
  }

  private DomainEvent capture() {
    ArgumentCaptor<DomainEvent> c = ArgumentCaptor.forClass(DomainEvent.class);
    verify(domainEventPublisher).publish(c.capture());
    return c.getValue();
  }

  @Test
  void priorityHighWhenPriorityLe3() {
    service.writeDispatchEvent(instance(2), task(null), partition("k"), "tr", "evt");
    Map<String, Object> msg = parseMsg(capture().payload().toString());
    assertThat(msg).containsEntry("priorityBand", SchedulingPriorityBand.HIGH.code());
  }

  @Test
  void priorityMediumWhenPriorityBetween4And6() {
    service.writeDispatchEvent(instance(5), task(null), partition("k"), "tr", "evt");
    Map<String, Object> msg = parseMsg(capture().payload().toString());
    assertThat(msg).containsEntry("priorityBand", SchedulingPriorityBand.MEDIUM.code());
  }

  @Test
  void priorityLowWhenPriorityGe7() {
    service.writeDispatchEvent(instance(9), task(null), partition("k"), "tr", "evt");
    Map<String, Object> msg = parseMsg(capture().payload().toString());
    assertThat(msg).containsEntry("priorityBand", SchedulingPriorityBand.LOW.code());
  }

  @Test
  void idempotencyKeyShouldPreferPartitionKey() {
    service.writeDispatchEvent(instance(3), task(null), partition("partition-idem-1"), "tr", "evt");
    Map<String, Object> msg = parseMsg(capture().payload().toString());
    assertThat(msg).containsEntry("idempotencyKey", "partition-idem-1");
  }

  @Test
  void idempotencyKeyWithoutPartitionShouldUseEventKeyIfPresent() {
    service.writeDispatchEvent(instance(3), task(null), null, "tr", "custom-event-key");
    Map<String, Object> msg = parseMsg(capture().payload().toString());
    assertThat(msg).containsEntry("idempotencyKey", "custom-event-key");
  }

  @Test
  void idempotencyKeyWithoutPartitionAndBlankEventKeyShouldFallbackToTenantTaskInstance() {
    service.writeDispatchEvent(instance(3), task(null), null, "tr", "");
    Map<String, Object> msg = parseMsg(capture().payload().toString());
    assertThat(msg).containsEntry("idempotencyKey", "ta:task:10:instance:1");
  }

  @Test
  void eventKeyShouldFallbackToTenantTaskIdWhenBlank() {
    service.writeDispatchEvent(instance(3), task(null), partition("k"), "tr", "");
    DomainEvent event = capture();
    assertThat(event.eventKey()).isEqualTo("ta:10");
  }

  @Test
  void eventKeyShouldUseProvidedWhenSet() {
    service.writeDispatchEvent(instance(3), task(null), partition("k"), "tr", "custom-event-key");
    assertThat(capture().eventKey()).isEqualTo("custom-event-key");
  }

  @Test
  void priorityShouldPreferTaskOverInstance() {
    service.writeDispatchEvent(instance(7), task(2), partition("k"), "tr", "evt");
    assertThat(capture().priority()).isEqualTo(2);
  }

  @Test
  void priorityShouldFallbackToInstancePriorityWhenTaskNull() {
    service.writeDispatchEvent(instance(7), task(null), partition("k"), "tr", "evt");
    assertThat(capture().priority()).isEqualTo(7);
  }

  @Test
  void publishStatusIsNewAndAttemptZero() {
    service.writeDispatchEvent(instance(3), task(null), partition("k"), "tr", "evt");
    DomainEvent event = capture();
    assertThat(event.traceId()).isEqualTo("tr");
    assertThat(event.aggregateType()).isEqualTo("JOB_TASK");
    assertThat(event.aggregateId()).isEqualTo(10L);
    assertThat(event.eventType()).isEqualTo("IMPORT");
  }

  @Test
  void noPartitionShouldOmitJobPartitionIdInMessage() {
    service.writeDispatchEvent(instance(3), task(null), null, "tr", "evt");
    Map<String, Object> msg = parseMsg(capture().payload().toString());
    assertThat(msg.get("jobPartitionId")).isNull();
    assertThat(msg).containsEntry("schemaVersion", "v2");
  }

  @Test
  void runModeNullShouldNotTouchJobTaskMapper() {
    service.writeDispatchEvent(instance(3), task(null), partition("k"), "tr", "evt");
    verify(jobTaskMapper, never()).updatePayload(any(), any(), any());
  }
}
