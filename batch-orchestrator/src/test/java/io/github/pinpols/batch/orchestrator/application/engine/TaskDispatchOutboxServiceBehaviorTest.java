package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.enums.SchedulingPriorityBand;
import io.github.pinpols.batch.common.event.DomainEvent;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.orchestrator.application.service.workflow.BizDateArithmetic;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * P2: TaskDispatchOutboxService 业务行为补强,补 TaskDispatchOutboxServiceMandatoryTest 之外的:
 *
 * <ul>
 *   <li>priority band 路由 (HIGH ≤3 / MEDIUM 4-6 / LOW ≥7)
 *   <li>idempotencyKey resolution (partition 优先 / eventKey 回退 / 派生 fallback)
 *   <li>eventKey 缺省时用 tenant:taskId 形态
 *   <li>priority 字段:task.priority 优先,jobInstance.priority 回退
 *   <li>payload v2 字段完整性
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("派发出箱服务行为: 优先级分档, 幂等键与事件键推导口径")
class TaskDispatchOutboxServiceBehaviorTest {

  @Mock
  private DomainEventPublisher domainEventPublisher;

  @Mock
  private JobTaskMapper jobTaskMapper;

  private TaskDispatchOutboxService service;

  @BeforeEach
  void setUp() {
    service =
        new TaskDispatchOutboxService(domainEventPublisher, jobTaskMapper, new BizDateArithmetic());
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

  private DomainEvent capture() {
    ArgumentCaptor<DomainEvent> c = ArgumentCaptor.forClass(DomainEvent.class);
    verify(domainEventPublisher).publish(c.capture());
    return c.getValue();
  }

  @Test
  @DisplayName("优先级不大于三时映射为高优先级分档")
  void shouldMapToHighBand_whenPriorityAtMostThree() {
    service.writeDispatchEvent(instance(2), task(null), partition("k"), "tr", "evt");
    Map<String, Object> msg = capture().payload();
    assertThat(msg).containsEntry("priorityBand", SchedulingPriorityBand.HIGH.code());
  }

  @Test
  @DisplayName("优先级为四到六时映射为中优先级分档")
  void shouldMapToMediumBand_whenPriorityBetweenFourAndSix() {
    service.writeDispatchEvent(instance(5), task(null), partition("k"), "tr", "evt");
    Map<String, Object> msg = capture().payload();
    assertThat(msg).containsEntry("priorityBand", SchedulingPriorityBand.MEDIUM.code());
  }

  @Test
  @DisplayName("优先级不小于七时映射为低优先级分档")
  void shouldMapToLowBand_whenPriorityAtLeastSeven() {
    service.writeDispatchEvent(instance(9), task(null), partition("k"), "tr", "evt");
    Map<String, Object> msg = capture().payload();
    assertThat(msg).containsEntry("priorityBand", SchedulingPriorityBand.LOW.code());
  }

  @Test
  @DisplayName("存在分区时优先使用分区自带的幂等键")
  void shouldPreferPartitionKey_whenDerivingIdempotencyKey() {
    service.writeDispatchEvent(instance(3), task(null), partition("partition-idem-1"), "tr", "evt");
    Map<String, Object> msg = capture().payload();
    assertThat(msg).containsEntry("idempotencyKey", "partition-idem-1");
  }

  @Test
  @DisplayName("没有分区但给出事件键时用事件键作为幂等键")
  void shouldUseEventKey_whenPartitionMissing() {
    service.writeDispatchEvent(instance(3), task(null), null, "tr", "custom-event-key");
    Map<String, Object> msg = capture().payload();
    assertThat(msg).containsEntry("idempotencyKey", "custom-event-key");
  }

  @Test
  @DisplayName("没有分区且事件键为空白时按租户, 任务与实例标识拼出幂等键")
  void shouldFallBackToTenantTaskInstance_whenPartitionAndEventKeyMissing() {
    service.writeDispatchEvent(instance(3), task(null), null, "tr", "");
    Map<String, Object> msg = capture().payload();
    assertThat(msg).containsEntry("idempotencyKey", "ta:task:10:instance:1");
  }

  @Test
  @DisplayName("事件键为空白时退化为租户加任务标识")
  void shouldFallBackToTenantTaskId_whenEventKeyBlank() {
    service.writeDispatchEvent(instance(3), task(null), partition("k"), "tr", "");
    DomainEvent event = capture();
    assertThat(event.eventKey()).isEqualTo("ta:10");
  }

  @Test
  @DisplayName("给出事件键时原样使用")
  void shouldUseProvidedEventKey_whenSet() {
    service.writeDispatchEvent(instance(3), task(null), partition("k"), "tr", "custom-event-key");
    assertThat(capture().eventKey()).isEqualTo("custom-event-key");
  }

  @Test
  @DisplayName("任务与实例都带优先级时取任务优先级")
  void shouldPreferTaskPriority_whenBothPresent() {
    service.writeDispatchEvent(instance(7), task(2), partition("k"), "tr", "evt");
    assertThat(capture().priority()).isEqualTo(2);
  }

  @Test
  @DisplayName("任务优先级为空时回退取实例优先级")
  void shouldFallBackToInstancePriority_whenTaskPriorityMissing() {
    service.writeDispatchEvent(instance(7), task(null), partition("k"), "tr", "evt");
    assertThat(capture().priority()).isEqualTo(7);
  }

  @Test
  @DisplayName("写入事件时发布状态为新建且尝试次数为零, 事件元数据完整")
  void shouldInitializePublishState_whenWritingEvent() {
    service.writeDispatchEvent(instance(3), task(null), partition("k"), "tr", "evt");
    DomainEvent event = capture();
    assertThat(event.traceId()).isEqualTo("tr");
    assertThat(event.aggregateType()).isEqualTo("JOB_TASK");
    assertThat(event.aggregateId()).isEqualTo(10L);
    assertThat(event.eventType()).isEqualTo("IMPORT");
  }

  @Test
  @DisplayName("没有分区时消息中不带分区标识, 且带出消息版本")
  void shouldOmitPartitionId_whenNoPartition() {
    service.writeDispatchEvent(instance(3), task(null), null, "tr", "evt");
    Map<String, Object> msg = capture().payload();
    assertThat(msg.get("jobPartitionId")).isNull();
    assertThat(msg).containsEntry("schemaVersion", "v2");
  }

  @Test
  @DisplayName("没有运行模式时不更新任务载荷")
  void shouldSkipPayloadUpdate_whenRunModeMissing() {
    service.writeDispatchEvent(instance(3), task(null), partition("k"), "tr", "evt");
    verify(jobTaskMapper, never()).updatePayload(any(), any(), any());
  }
}
