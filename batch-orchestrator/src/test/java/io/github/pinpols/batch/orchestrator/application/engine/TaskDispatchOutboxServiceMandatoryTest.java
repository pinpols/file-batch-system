package io.github.pinpols.batch.orchestrator.application.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.event.DomainEventPublisher;
import io.github.pinpols.batch.orchestrator.application.service.workflow.BizDateArithmetic;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.lang.reflect.Method;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
@DisplayName("派发出箱服务事务强制: 事件落库, 运行模式落载荷与事务传播口径")
class TaskDispatchOutboxServiceMandatoryTest {

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

  @Test
  @DisplayName("写入派发事件时落库出箱事件并发布领域事件")
  void writeDispatchEvent_insertsOutboxEvent() {
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setId(1L);
    jobInstance.setInstanceNo("inst-001");
    jobInstance.setJobCode("IMPORT_JOB");
    jobInstance.setPriority(1);

    JobTaskEntity task = new JobTaskEntity();
    task.setId(10L);
    task.setTenantId("t1");
    task.setTaskType("IMPORT");
    task.setTaskSeq(1);
    task.setAssignedWorkerCode("worker-1");

    JobPartitionEntity partition = new JobPartitionEntity();
    partition.setId(100L);
    partition.setBusinessKey("biz-key-1");
    partition.setIdempotencyKey("idem-key-1");

    service.writeDispatchEvent(jobInstance, task, partition, "trace-1", "evt-key-1");

    verify(domainEventPublisher).publish(any());
  }

  @Test
  @DisplayName("带运行模式覆盖时把运行模式写入任务载荷, 并发布领域事件")
  void writeDispatchEvent_withRunModeOverride_persistsToTaskPayload() {
    // P1-2.2:RunMode 不再写入 Kafka message,而是 UPDATE job_task.task_payload,
    // worker CLAIM 时由 EffectiveTaskConfig 实时读到。
    JobInstanceEntity jobInstance = new JobInstanceEntity();
    jobInstance.setId(1L);
    jobInstance.setInstanceNo("inst-002");
    jobInstance.setJobCode("EXPORT_JOB");
    jobInstance.setPriority(5);

    JobTaskEntity task = new JobTaskEntity();
    task.setId(20L);
    task.setTenantId("t1");
    task.setTaskType("EXPORT");
    task.setTaskSeq(1);
    task.setTaskPayload("{\"key\":\"val\"}");

    service.writeDispatchEvent(jobInstance, task, null, "trace-2", "evt-key-2", RunMode.RETRY);

    var payloadCaptor = ArgumentCaptor.forClass(String.class);
    verify(jobTaskMapper).updatePayload(eq("t1"), eq(20L), payloadCaptor.capture());
    assertThat(payloadCaptor.getValue()).contains("run_mode").contains("RETRY").contains("\"key\"");
    verify(domainEventPublisher).publish(any());
  }

  @Test
  @DisplayName("写入派发事件的方法声明为强制事务传播")
  void writeDispatchEvent_methodHasMandatoryTransactional() throws Exception {
    Method method = TaskDispatchOutboxService.class.getDeclaredMethod(
        "writeDispatchEvent",
        JobInstanceEntity.class,
        JobTaskEntity.class,
        JobPartitionEntity.class,
        String.class,
        String.class);
    Transactional tx = method.getAnnotation(Transactional.class);
    assertThat(tx).isNotNull();
    assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
  }

  @Test
  @DisplayName("带运行模式的写入方法同样声明为强制事务传播")
  void writeDispatchEvent_withRunMode_methodHasMandatoryTransactional() throws Exception {
    Method method = TaskDispatchOutboxService.class.getDeclaredMethod(
        "writeDispatchEvent",
        JobInstanceEntity.class,
        JobTaskEntity.class,
        JobPartitionEntity.class,
        String.class,
        String.class,
        RunMode.class);
    Transactional tx = method.getAnnotation(Transactional.class);
    assertThat(tx).isNotNull();
    assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
  }
}
