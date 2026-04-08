package com.example.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import com.example.batch.worker.dispatchs.infrastructure.channel.DispatchResult;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RetryDispatchStepTest {

    @Mock
    private FileDispatchRepository fileDispatchRepository;
    @Mock
    private DispatchChannelGateway dispatchChannelGateway;
    @Mock
    private PlatformFileRuntimeRepository runtimeRepository;

    private RetryDispatchStep step;

    @BeforeEach
    void setUp() {
        step = new RetryDispatchStep(fileDispatchRepository, dispatchChannelGateway, runtimeRepository);
    }

    @Test
    void stage_returnsRetry() {
        assertThat(step.stage()).isEqualTo(DispatchStage.RETRY);
    }

    @Test
    void execute_failsAndRoutesToCompensateWhenNoPayload() {
        DispatchJobContext context = new DispatchJobContext();
        context.setTenantId("t1");
        DispatchStageResult result = step.execute(context);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_RETRY_NO_PAYLOAD");
        assertThat(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE))
                .isEqualTo(DispatchStage.COMPENSATE.name());
    }

    @Test
    void execute_succeedsWithNoOpWhenRetryNotRequested() {
        DispatchJobContext context = buildContext();
        context.getAttributes().put("retryRequested", Boolean.FALSE);
        DispatchStageResult result = step.execute(context);
        assertThat(result.success()).isTrue();
        assertThat(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE))
                .isEqualTo(DispatchStage.COMPENSATE.name());
    }

    @Test
    void execute_succeedsAndRoutesToAckWhenRetrySucceeds() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);
        when(dispatchChannelGateway.dispatch(any()))
                .thenReturn(new DispatchResult(true, "ext-retry", "R-retry", true, false, "ok", null));
        when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any())).thenReturn(1);

        DispatchJobContext context = buildContextWithRetryRequested();
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isTrue();
        assertThat(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE))
                .isEqualTo(DispatchStage.ACK.name());
        assertThat(context.getAttributes().get("retryRecovered")).isEqualTo(Boolean.TRUE);
        verify(fileDispatchRepository).incrementAttempt("t1", 10L, "CH1");
    }

    @Test
    void execute_failsAndRoutesToCompensateWhenRetryFails() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);
        when(dispatchChannelGateway.dispatch(any()))
                .thenReturn(new DispatchResult(false, null, null, false, false, "network error", null));
        when(fileDispatchRepository.markFailed(any(), any(), any(), any(), any())).thenReturn(1);

        DispatchJobContext context = buildContextWithRetryRequested();
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_RETRY_FAILED");
        assertThat(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE))
                .isEqualTo(DispatchStage.COMPENSATE.name());
    }

    @Test
    void execute_failsWhenMarkSentAfterRetryReturnsZero() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);
        when(dispatchChannelGateway.dispatch(any()))
                .thenReturn(new DispatchResult(true, "ext-retry", "R-retry", true, false, "ok", null));
        when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any())).thenReturn(0);

        DispatchJobContext context = buildContextWithRetryRequested();
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_RETRY_FAILED");
        assertThat(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE))
                .isEqualTo(DispatchStage.COMPENSATE.name());
    }

    private DispatchJobContext buildContext() {
        DispatchPayload payload = new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null);
        DispatchJobContext context = new DispatchJobContext();
        context.setTenantId("t1");
        context.getAttributes().put("dispatchPayload", payload);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, Map.of("id", 10L));
        context.getAttributes().put(PipelineRuntimeKeys.CHANNEL_CONFIG, Map.of("channel_type", "LOCAL"));
        context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
        return context;
    }

    private DispatchJobContext buildContextWithRetryRequested() {
        DispatchJobContext context = buildContext();
        context.getAttributes().put("retryRequested", Boolean.TRUE);
        return context;
    }
}
