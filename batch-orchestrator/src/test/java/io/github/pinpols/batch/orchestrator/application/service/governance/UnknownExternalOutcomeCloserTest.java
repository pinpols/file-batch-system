package io.github.pinpols.batch.orchestrator.application.service.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskOutcomeService;
import io.github.pinpols.batch.orchestrator.domain.command.TaskOutcomeCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;

@DisplayName("未知外部结果收口: 使用当前 invocation 进入既有核对流程")
class UnknownExternalOutcomeCloserTest {

    private JobPartitionMapper jobPartitionMapper;
    private TaskOutcomeService taskOutcomeService;
    private ObjectProvider<TaskOutcomeService> taskOutcomeServiceProvider;
    private UnknownExternalOutcomeCloser closer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        jobPartitionMapper = mock(JobPartitionMapper.class);
        taskOutcomeService = mock(TaskOutcomeService.class);
        taskOutcomeServiceProvider = mock(ObjectProvider.class);
        when(taskOutcomeServiceProvider.getObject()).thenReturn(taskOutcomeService);
        closer = new UnknownExternalOutcomeCloser(jobPartitionMapper, taskOutcomeServiceProvider);
    }

    @Test
    @DisplayName("存在当前 invocation 时以结果不明收口并带上任务归属")
    void shouldCloseWithCurrentInvocation_whenPartitionInvocationExists() {
        JobPartitionEntity partition = new JobPartitionEntity();
        partition.setId(7L);
        partition.setTenantId("t1");
        partition.setCurrentInvocationId("inv-9");
        JobTaskEntity task = new JobTaskEntity();
        task.setId(11L);
        task.setTenantId("t1");
        task.setJobPartitionId(7L);
        task.setAssignedWorkerCode("w1");
        when(jobPartitionMapper.selectById("t1", 7L)).thenReturn(partition);
        when(taskOutcomeService.applyTaskOutcome(any())).thenReturn(task);

        boolean closed = closer.close(task);

        assertThat(closed).isTrue();
        ArgumentCaptor<TaskOutcomeCommand> captor = ArgumentCaptor.forClass(TaskOutcomeCommand.class);
        verify(taskOutcomeService).applyTaskOutcome(captor.capture());
        assertThat(captor.getValue().tenantId()).isEqualTo("t1");
        assertThat(captor.getValue().taskId()).isEqualTo(11L);
        assertThat(captor.getValue().workerId()).isEqualTo("w1");
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().errorCode()).isEqualTo(TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN);
        assertThat(captor.getValue().partitionInvocationId()).isEqualTo("inv-9");
        assertThat(captor.getValue().errorMessage()).isEqualTo(UnknownExternalOutcomeCloser.OUTCOME_UNKNOWN_MESSAGE);
    }

    @Test
    @DisplayName("缺少当前 invocation 时不写结果, 交由调用方重试")
    void shouldRefuseClose_whenCurrentInvocationIsMissing() {
        JobPartitionEntity partition = new JobPartitionEntity();
        partition.setId(7L);
        partition.setTenantId("t1");
        JobTaskEntity task = new JobTaskEntity();
        task.setId(11L);
        task.setTenantId("t1");
        task.setJobPartitionId(7L);
        when(jobPartitionMapper.selectById("t1", 7L)).thenReturn(partition);

        assertThat(closer.close(task)).isFalse();
        verify(taskOutcomeService, never()).applyTaskOutcome(any());
    }

    @Test
    @DisplayName("结果未落库时返回失败, 不伪装成已收口")
    void shouldReturnFalse_whenOutcomeWasNotPersisted() {
        JobPartitionEntity partition = new JobPartitionEntity();
        partition.setId(7L);
        partition.setTenantId("t1");
        partition.setCurrentInvocationId("inv-9");
        JobTaskEntity task = new JobTaskEntity();
        task.setId(11L);
        task.setTenantId("t1");
        task.setJobPartitionId(7L);
        when(jobPartitionMapper.selectById("t1", 7L)).thenReturn(partition);
        when(taskOutcomeService.applyTaskOutcome(any())).thenReturn(null);

        assertThat(closer.close(task)).isFalse();
    }
}
