package com.example.batch.worker.core.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.domain.PulledTask;
import com.example.batch.worker.core.domain.StepExecutionRequest;
import com.example.batch.worker.core.domain.StepExecutionResponse;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.domain.WorkerExecutionResult;
import com.example.batch.worker.core.infrastructure.ActiveTaskLeaseRegistry;
import com.example.batch.worker.core.support.StepExecutionAdapter;
import com.example.batch.worker.core.support.TaskExecutionClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultTaskExecutionWrapperTest {

    private StepExecutionAdapter stepExecutionAdapter;
    private TaskExecutionClient taskExecutionClient;
    private ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
    private DefaultTaskExecutionWrapper wrapper;

    @BeforeEach
    void setUp() {
        stepExecutionAdapter = mock(StepExecutionAdapter.class);
        taskExecutionClient = mock(TaskExecutionClient.class);
        activeTaskLeaseRegistry = mock(ActiveTaskLeaseRegistry.class);
        wrapper = new DefaultTaskExecutionWrapper(stepExecutionAdapter, taskExecutionClient, activeTaskLeaseRegistry);
    }

    @Test
    void shouldDelegateClaimToTaskExecutionClient() {
        when(taskExecutionClient.claim("t1", 42L, "w1")).thenReturn(true);

        boolean result = wrapper.claim("t1", 42L, "w1");

        assertThat(result).isTrue();
        verify(taskExecutionClient).claim("t1", 42L, "w1");
    }

    @Test
    void shouldReturnFalseWhenClaimDenied() {
        when(taskExecutionClient.claim("t1", 42L, "w1")).thenReturn(false);

        assertThat(wrapper.claim("t1", 42L, "w1")).isFalse();
    }

    @Test
    void shouldRegisterLeaseExecuteAndRemoveOnSuccess() {
        PulledTask task = sampleTask("1001", "t1", "w1");
        when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
                .thenReturn(StepExecutionResponse.successResponse());

        WorkerExecutionResult result = wrapper.execute(task);

        assertThat(result.success()).isTrue();
        assertThat(result.taskId()).isEqualTo("1001");

        verify(activeTaskLeaseRegistry).register("1001", "t1", "w1");
        verify(activeTaskLeaseRegistry).remove("1001");
        verify(taskExecutionClient).report(any(TaskExecutionReport.class));
    }

    @Test
    void shouldReportFailureWhenStepExecutionFails() {
        PulledTask task = sampleTask("1002", "t1", "w1");
        when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
                .thenReturn(new StepExecutionResponse(false, "ERR_PARSE", "parse failed"));

        WorkerExecutionResult result = wrapper.execute(task);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("parse failed");
        verify(taskExecutionClient).report(any(TaskExecutionReport.class));
        verify(activeTaskLeaseRegistry).remove("1002");
    }

    @Test
    void shouldRemoveLeaseEvenWhenStepExecutionThrows() {
        PulledTask task = sampleTask("1003", "t1", "w1");
        when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
                .thenThrow(new RuntimeException("unexpected"));

        assertThatThrownBy(() -> wrapper.execute(task))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("unexpected");

        verify(activeTaskLeaseRegistry).register("1003", "t1", "w1");
        verify(activeTaskLeaseRegistry).remove("1003");
    }

    @Test
    void shouldBuildExecutionContextWithNullSafeDefaults() {
        PulledTask task = new PulledTask();
        task.setTaskId("9001");
        task.setTenantId("t1");
        task.setWorkerId("w1");
        // payload, jobCode, businessKey, traceId, idempotencyKey are all null
        when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
                .thenReturn(StepExecutionResponse.successResponse());

        wrapper.execute(task);

        // verifies execute was called without NPE from null fields
        verify(stepExecutionAdapter).execute(any(StepExecutionRequest.class));
    }

    @Test
    void shouldIncludeJobCodeInExecutionRequest() {
        PulledTask task = sampleTask("1004", "t1", "w1");
        task.setJobCode("MY_JOB");

        when(stepExecutionAdapter.execute(any(StepExecutionRequest.class)))
                .thenAnswer(invocation -> {
                    StepExecutionRequest req = invocation.getArgument(0);
                    assertThat(req.jobCode()).isEqualTo("MY_JOB");
                    return StepExecutionResponse.successResponse();
                });

        wrapper.execute(task);
    }

    // --- helpers ---

    private static PulledTask sampleTask(String taskId, String tenantId, String workerId) {
        PulledTask task = new PulledTask();
        task.setTaskId(taskId);
        task.setTenantId(tenantId);
        task.setWorkerId(workerId);
        task.setTaskType("IMPORT");
        task.setTraceId("trace-" + taskId);
        task.setPayload("{\"k\":1}");
        task.setJobCode("TEST_JOB");
        task.setBusinessKey("biz-key");
        task.setJobInstanceId(100L);
        task.setJobPartitionId(200L);
        task.setTaskSeq(1);
        task.setIdempotencyKey("idem-" + taskId);
        return task;
    }
}
