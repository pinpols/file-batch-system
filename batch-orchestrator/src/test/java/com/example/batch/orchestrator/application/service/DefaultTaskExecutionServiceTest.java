package com.example.batch.orchestrator.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.domain.command.TaskOutcomeCommand;
import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies that DefaultTaskExecutionService correctly delegates every method
 * to its sub-service collaborators without adding logic of its own.
 */
@ExtendWith(MockitoExtension.class)
class DefaultTaskExecutionServiceTest {

    @Mock
    private TaskCreationService taskCreationService;
    @Mock
    private TaskAssignmentService taskAssignmentService;
    @Mock
    private TaskOutcomeService taskOutcomeService;

    private DefaultTaskExecutionService service;

    @BeforeEach
    void setUp() {
        service = new DefaultTaskExecutionService(taskCreationService, taskAssignmentService, taskOutcomeService);
    }

    @Test
    void createTask_delegatesToCreationService() {
        JobTaskEntity task = new JobTaskEntity();
        when(taskCreationService.createTask(task)).thenReturn(task);

        JobTaskEntity result = service.createTask(task);

        assertThat(result).isSameAs(task);
        verify(taskCreationService).createTask(task);
    }

    @Test
    void assignWorker_delegatesToAssignmentService() {
        JobTaskEntity task = new JobTaskEntity();
        when(taskAssignmentService.assignWorker("t1", 1L, "w1")).thenReturn(task);

        JobTaskEntity result = service.assignWorker("t1", 1L, "w1");

        assertThat(result).isSameAs(task);
        verify(taskAssignmentService).assignWorker("t1", 1L, "w1");
    }

    @Test
    void renewTaskLease_delegatesToAssignmentService() {
        when(taskAssignmentService.renewTaskLease("t1", 1L, "w1")).thenReturn(true);

        boolean result = service.renewTaskLease("t1", 1L, "w1");

        assertThat(result).isTrue();
        verify(taskAssignmentService).renewTaskLease("t1", 1L, "w1");
    }

    @Test
    void updateTaskStatus_delegatesToAssignmentService() {
        JobTaskEntity task = new JobTaskEntity();
        when(taskAssignmentService.updateTaskStatus("t1", 1L, "RUNNING", null, null)).thenReturn(task);

        JobTaskEntity result = service.updateTaskStatus("t1", 1L, "RUNNING", null, null);

        assertThat(result).isSameAs(task);
        verify(taskAssignmentService).updateTaskStatus("t1", 1L, "RUNNING", null, null);
    }

    @Test
    void appendLog_delegatesToAssignmentService() {
        JobExecutionLogEntity log = new JobExecutionLogEntity();
        when(taskAssignmentService.appendLog(log)).thenReturn(log);

        JobExecutionLogEntity result = service.appendLog(log);

        assertThat(result).isSameAs(log);
        verify(taskAssignmentService).appendLog(log);
    }

    @Test
    void listLogs_delegatesToAssignmentService() {
        List<JobExecutionLogEntity> logs = List.of(new JobExecutionLogEntity());
        when(taskAssignmentService.listLogs("t1", 1L, 1L)).thenReturn(logs);

        List<JobExecutionLogEntity> result = service.listLogs("t1", 1L, 1L);

        assertThat(result).isEqualTo(logs);
        verify(taskAssignmentService).listLogs("t1", 1L, 1L);
    }

    @Test
    void markRunning_delegatesToAssignmentService() {
        Instant now = Instant.now();
        JobTaskEntity task = new JobTaskEntity();
        when(taskAssignmentService.markRunning("t1", 1L, now)).thenReturn(task);

        JobTaskEntity result = service.markRunning("t1", 1L, now);

        assertThat(result).isSameAs(task);
        verify(taskAssignmentService).markRunning("t1", 1L, now);
    }

    @Test
    void applyTaskOutcome_delegatesToOutcomeService() {
        TaskOutcomeCommand command = new TaskOutcomeCommand("t1", 1L, true, null, null, null);
        JobTaskEntity task = new JobTaskEntity();
        when(taskOutcomeService.applyTaskOutcome(command)).thenReturn(task);

        JobTaskEntity result = service.applyTaskOutcome(command);

        assertThat(result).isSameAs(task);
        verify(taskOutcomeService).applyTaskOutcome(command);
    }

    @Test
    void recordNodeRunReady_delegatesToOutcomeService() {
        WorkflowNodeRunEntity nodeRun = new WorkflowNodeRunEntity();
        when(taskOutcomeService.recordNodeRunReady(1L, "N1", "EXPORT")).thenReturn(nodeRun);

        WorkflowNodeRunEntity result = service.recordNodeRunReady(1L, "N1", "EXPORT");

        assertThat(result).isSameAs(nodeRun);
        verify(taskOutcomeService).recordNodeRunReady(1L, "N1", "EXPORT");
    }
}
