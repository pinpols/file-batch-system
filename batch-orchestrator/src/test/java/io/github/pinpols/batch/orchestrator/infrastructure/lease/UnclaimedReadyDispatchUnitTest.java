package io.github.pinpols.batch.orchestrator.infrastructure.lease;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.command.TaskDispatchEventCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("未认领 READY 重发: 晚到认领胜出时不写 outbox，CAS 成功才按新版本重发")
class UnclaimedReadyDispatchUnitTest {

    @Mock
    private JobTaskMapper jobTaskMapper;

    @Mock
    private JobPartitionMapper jobPartitionMapper;

    @Mock
    private JobInstanceMapper jobInstanceMapper;

    @Mock
    private TaskDispatchOutboxService taskDispatchOutboxService;

    private UnclaimedReadyDispatchUnit unit;

    @BeforeEach
    void setUp() {
        unit = new UnclaimedReadyDispatchUnit(
                jobTaskMapper,
                jobPartitionMapper,
                jobInstanceMapper,
                taskDispatchOutboxService,
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("认领先提交时版本 CAS 为 0，不再写 outbox")
    void shouldNotPublishWhenLateClaimWins() {
        JobTaskEntity scanned = readyTask();
        when(jobTaskMapper.selectForWorkerClaim("ta", 9L)).thenReturn(readyTask());
        when(jobPartitionMapper.selectById("ta", 3L)).thenReturn(readyPartition());
        when(jobInstanceMapper.selectById("ta", 2L)).thenReturn(instance());
        when(jobTaskMapper.bumpVersionIfUnclaimedReady("ta", 9L, 4L)).thenReturn(0);

        unit.redispatch(scanned);

        verify(taskDispatchOutboxService, never()).writeDispatchEvent(any());
    }

    @Test
    @DisplayName("宽限后仍未认领时只推进版本，并用新版本键重发，不改运行模式")
    void shouldRepublishWithVersionedKeyWhenStillUnclaimed() {
        JobTaskEntity scanned = readyTask();
        when(jobTaskMapper.selectForWorkerClaim("ta", 9L)).thenReturn(readyTask());
        when(jobPartitionMapper.selectById("ta", 3L)).thenReturn(readyPartition());
        when(jobInstanceMapper.selectById("ta", 2L)).thenReturn(instance());
        when(jobTaskMapper.bumpVersionIfUnclaimedReady("ta", 9L, 4L)).thenReturn(1);

        unit.redispatch(scanned);

        ArgumentCaptor<TaskDispatchEventCommand> command = ArgumentCaptor.forClass(TaskDispatchEventCommand.class);
        verify(taskDispatchOutboxService).writeDispatchEvent(command.capture());
        assertThat(command.getValue().eventKey())
                .isEqualTo(OutboxEventKeyGenerator.forUnclaimedRedispatch("ta", 9L, 5L));
        assertThat(command.getValue().runModeOverride()).isNull();
        verify(jobTaskMapper, never()).resetForLeaseReclaim(eq("ta"), eq(9L), any(), any());
    }

    private static JobTaskEntity readyTask() {
        JobTaskEntity task = new JobTaskEntity();
        task.setId(9L);
        task.setTenantId("ta");
        task.setJobInstanceId(2L);
        task.setJobPartitionId(3L);
        task.setTaskStatus(TaskStatus.READY.code());
        task.setVersion(4L);
        return task;
    }

    private static JobPartitionEntity readyPartition() {
        JobPartitionEntity partition = new JobPartitionEntity();
        partition.setId(3L);
        partition.setTenantId("ta");
        partition.setPartitionStatus(PartitionStatus.READY.code());
        return partition;
    }

    private static JobInstanceEntity instance() {
        JobInstanceEntity instance = new JobInstanceEntity();
        instance.setId(2L);
        instance.setTenantId("ta");
        instance.setTraceId("trace-1");
        return instance;
    }
}
