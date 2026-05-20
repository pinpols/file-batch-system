package com.example.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.common.enums.OutboxPublishStatus;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.SchedulingPriorityBand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * 守护 task dispatch outbox 关键写入语义:
 *
 * <ul>
 *   <li>aggregate_type=JOB_TASK / event_type=task.taskType
 *   <li>eventKey 兜底: 缺失时退化 tenantId:taskId
 *   <li>idempotencyKey 优先来自 partition,无 partition 时用 tenantId:task:taskId:instance:instId
 *   <li>priority 取 task.priority,缺时回退 jobInstance.priority
 *   <li>priorityBand 映射: ≤3→HIGH, 4-6→MEDIUM, ≥7→LOW
 *   <li>RunMode 覆写 → 触发 job_task.payload 更新 + 同步内存对象
 * </ul>
 */
class TaskDispatchOutboxServiceTest {

  @Mock private OutboxEventMapper outboxEventMapper;
  @Mock private JobTaskMapper jobTaskMapper;

  private TaskDispatchOutboxService service;

  @BeforeEach
  void setUp() throws Exception {
    MockitoAnnotations.openMocks(this);
    service = new TaskDispatchOutboxService(outboxEventMapper, jobTaskMapper);
    // @Lazy self 单测注入指向自己,绕过 Spring 代理
    Field selfField = TaskDispatchOutboxService.class.getDeclaredField("self");
    selfField.setAccessible(true);
    selfField.set(service, service);
  }

  // ===== eventKey 兜底 =====

  @Test
  @DisplayName("eventKey 缺失 → 退化为 tenantId:taskId")
  void event_key_falls_back_to_tenant_task() {
    service.writeDispatchEvent(instance(100L, 5), task(500L, null), null, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getEventKey()).isEqualTo("ta:500");
  }

  @Test
  @DisplayName("eventKey 显式给出 → 透传")
  void event_key_passthrough() {
    service.writeDispatchEvent(instance(100L, 5), task(500L, null), null, "trace", "custom-key");

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getEventKey()).isEqualTo("custom-key");
  }

  // ===== priority =====

  @Test
  @DisplayName("priority 取 task.priority,缺失时回退 jobInstance.priority")
  void priority_falls_back_to_instance_when_task_null() {
    JobTaskEntity t = task(500L, null);
    t.setPriority(null);
    service.writeDispatchEvent(instance(100L, 7), t, null, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getPriority()).isEqualTo(7);
  }

  @Test
  @DisplayName("priority 优先用 task.priority(覆盖 instance.priority)")
  void priority_prefers_task() {
    JobTaskEntity t = task(500L, null);
    t.setPriority(2);
    service.writeDispatchEvent(instance(100L, 7), t, null, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getPriority()).isEqualTo(2);
  }

  // ===== aggregate / event type =====

  @Test
  @DisplayName("aggregate_type=JOB_TASK / event_type=task.taskType / publishStatus=NEW")
  void event_metadata_fields_correct() {
    JobTaskEntity t = task(500L, null);
    t.setTaskType("EXECUTION");
    service.writeDispatchEvent(instance(100L, 5), t, null, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    OutboxEventEntity e = cap.getValue();
    assertThat(e.getAggregateType()).isEqualTo("JOB_TASK");
    assertThat(e.getEventType()).isEqualTo("EXECUTION");
    assertThat(e.getAggregateId()).isEqualTo(500L);
    assertThat(e.getPublishStatus()).isEqualTo(OutboxPublishStatus.NEW.code());
    assertThat(e.getPublishAttempt()).isZero();
    assertThat(e.getTraceId()).isEqualTo("trace");
  }

  // ===== payload priorityBand =====

  @Test
  @DisplayName("priorityBand 映射: 1/2/3 → HIGH")
  void priority_band_high() {
    JobTaskEntity t = task(500L, null);
    t.setPriority(1);
    service.writeDispatchEvent(instance(100L, 1), t, null, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getPayloadJson())
        .contains("\"" + SchedulingPriorityBand.HIGH.code() + "\"");
  }

  @Test
  @DisplayName("priorityBand 映射: 5 → MEDIUM")
  void priority_band_medium() {
    JobTaskEntity t = task(500L, null);
    t.setPriority(5);
    service.writeDispatchEvent(instance(100L, 5), t, null, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getPayloadJson())
        .contains("\"" + SchedulingPriorityBand.MEDIUM.code() + "\"");
  }

  @Test
  @DisplayName("priorityBand 映射: 10 → LOW")
  void priority_band_low() {
    JobTaskEntity t = task(500L, null);
    t.setPriority(10);
    service.writeDispatchEvent(instance(100L, 10), t, null, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getPayloadJson())
        .contains("\"" + SchedulingPriorityBand.LOW.code() + "\"");
  }

  // ===== RunMode override 持久化 =====

  @Test
  @DisplayName("runModeOverride=null → 不写 task.payload")
  void run_mode_null_skips_payload_update() {
    service.writeDispatchEvent(instance(100L, 5), task(500L, null), null, "trace", null);
    verify(jobTaskMapper, never()).updatePayload(anyString(), anyLong(), anyString());
  }

  @Test
  @DisplayName("runModeOverride 非 null → 更新 task.payload + 同步内存对象")
  void run_mode_override_persists_to_payload() {
    JobTaskEntity t = task(500L, null);
    t.setTaskPayload("{}"); // 初始空 payload
    service.writeDispatchEvent(instance(100L, 5), t, null, "trace", null, RunMode.RECOVER);

    ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
    verify(jobTaskMapper).updatePayload(eq("ta"), eq(500L), payloadCap.capture());
    // payload 应含 run_mode 标记
    assertThat(payloadCap.getValue()).contains("RECOVER");
    // 内存对象也被同步
    assertThat(t.getTaskPayload()).contains("RECOVER");
  }

  // ===== idempotencyKey (partition 缺失场景) =====

  @Test
  @DisplayName("无 partition + 无 eventKey → idempotencyKey 用 tenantId:task:taskId:instance:instId")
  void idempotency_key_no_partition_no_event_key() {
    JobTaskEntity t = task(500L, null);
    t.setJobInstanceId(100L);
    service.writeDispatchEvent(instance(100L, 5), t, null, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    // idempotencyKey 在 message payload 里,JSON 序列化后应含 "ta:task:500:instance:100"
    assertThat(cap.getValue().getPayloadJson()).contains("ta:task:500:instance:100");
  }

  @Test
  @DisplayName("有 partition → idempotencyKey 用 partition.idempotencyKey")
  void idempotency_key_from_partition() {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setId(99L);
    p.setIdempotencyKey("partition-idem-99");
    JobTaskEntity t = task(500L, 99L);

    service.writeDispatchEvent(instance(100L, 5), t, p, "trace", null);

    ArgumentCaptor<OutboxEventEntity> cap = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventMapper).insert(cap.capture());
    assertThat(cap.getValue().getPayloadJson()).contains("partition-idem-99");
  }

  // ===== fixtures =====

  private JobInstanceEntity instance(Long id, Integer priority) {
    JobInstanceEntity i = new JobInstanceEntity();
    i.setId(id);
    i.setTenantId("ta");
    i.setInstanceNo("inst-" + id);
    i.setJobCode("job-x");
    i.setPriority(priority);
    return i;
  }

  private JobTaskEntity task(Long taskId, Long jobPartitionId) {
    JobTaskEntity t = new JobTaskEntity();
    t.setId(taskId);
    t.setTenantId("ta");
    t.setTaskType("EXECUTION");
    t.setJobPartitionId(jobPartitionId);
    return t;
  }
}
