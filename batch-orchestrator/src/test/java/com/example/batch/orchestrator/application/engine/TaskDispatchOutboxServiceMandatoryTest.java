package com.example.batch.orchestrator.application.engine;

import com.example.batch.common.enums.RunMode;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TaskDispatchOutboxServiceMandatoryTest {

    @Mock
    private OutboxEventMapper outboxEventMapper;

    private TaskDispatchOutboxService service;

    @BeforeEach
    void setUp() {
        service = new TaskDispatchOutboxService(outboxEventMapper);
    }

    @Test
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

        verify(outboxEventMapper).insert(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void writeDispatchEvent_withRunModeOverride_setsRunModeInPayload() {
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

        var captor = ArgumentCaptor.forClass(com.example.batch.orchestrator.domain.entity.OutboxEventEntity.class);
        verify(outboxEventMapper).insert(captor.capture());
        assertThat(captor.getValue().getPayloadJson()).contains("run_mode");
    }

    @Test
    void writeDispatchEvent_methodHasMandatoryTransactional() throws Exception {
        Method method = TaskDispatchOutboxService.class.getDeclaredMethod(
                "writeDispatchEvent",
                JobInstanceEntity.class, JobTaskEntity.class, JobPartitionEntity.class,
                String.class, String.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void writeDispatchEvent_withRunMode_methodHasMandatoryTransactional() throws Exception {
        Method method = TaskDispatchOutboxService.class.getDeclaredMethod(
                "writeDispatchEvent",
                JobInstanceEntity.class, JobTaskEntity.class, JobPartitionEntity.class,
                String.class, String.class, RunMode.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
