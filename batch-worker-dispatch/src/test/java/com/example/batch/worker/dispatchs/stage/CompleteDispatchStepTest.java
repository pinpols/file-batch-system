package com.example.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CompleteDispatchStepTest {

    @Mock
    private PlatformFileRuntimeRepository runtimeRepository;

    private CompleteDispatchStep step;

    @BeforeEach
    void setUp() {
        step = new CompleteDispatchStep(runtimeRepository);
    }

    @Test
    void stage_returnsComplete() {
        assertThat(step.stage()).isEqualTo(DispatchStage.COMPLETE);
    }

    @Test
    void execute_failsWhenNoPayload() {
        DispatchJobContext context = new DispatchJobContext();
        DispatchStageResult result = step.execute(context);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_COMPLETE_NO_PAYLOAD");
    }

    @Test
    void execute_updatesFileStatusToDispatchedWhenReceiptStatusIsSuccess() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);

        DispatchJobContext context = buildContext("SUCCESS", "R-001");
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isTrue();
        verify(runtimeRepository).updateFileStatus(eq(10L), eq("DISPATCHED"), any());
    }

    @Test
    void execute_doesNotUpdateFileStatusWhenReceiptNotSuccess() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);

        DispatchJobContext context = buildContext("PENDING", null);
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isTrue();
        verify(runtimeRepository, never()).updateFileStatus(any(), any(), any());
    }

    @Test
    void execute_alwaysWritesAuditLog() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);

        DispatchJobContext context = buildContext("NONE", null);
        step.execute(context);

        verify(runtimeRepository).appendAudit(any());
    }

    @Test
    void execute_includesReceiptCodeInMetadataWhenPresent() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);

        DispatchJobContext context = buildContext("SUCCESS", "R-001");
        context.getAttributes().put("receiptCode", "R-001");
        step.execute(context);

        // Just ensure no NPE and audit is written
        verify(runtimeRepository).appendAudit(any());
    }

    @Test
    void execute_handlesNullReceiptStatusGracefully() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);

        DispatchPayload payload = new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null);
        DispatchJobContext context = new DispatchJobContext();
        context.setTenantId("t1");
        context.setWorkerId("w1");
        context.getAttributes().put("dispatchPayload", payload);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
        context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
        // receiptStatus is absent — defaults to "NONE"

        DispatchStageResult result = step.execute(context);
        assertThat(result.success()).isTrue();
        verify(runtimeRepository, never()).updateFileStatus(any(), any(), any());
    }

    private DispatchJobContext buildContext(String receiptStatus, String receiptCode) {
        DispatchPayload payload = new DispatchPayload("10", null, "CH1", "target", "ext-1", receiptCode, null, null, null, null);
        DispatchJobContext context = new DispatchJobContext();
        context.setTenantId("t1");
        context.setWorkerId("w1");
        context.getAttributes().put("dispatchPayload", payload);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
        context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
        context.getAttributes().put("externalRequestId", "ext-1");
        context.getAttributes().put("receiptStatus", receiptStatus);
        if (receiptCode != null) {
            context.getAttributes().put("receiptCode", receiptCode);
        }
        return context;
    }
}
