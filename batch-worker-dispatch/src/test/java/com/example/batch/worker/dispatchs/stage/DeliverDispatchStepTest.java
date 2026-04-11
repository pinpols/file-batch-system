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
class DeliverDispatchStepTest {

    @Mock
    private FileDispatchRepository fileDispatchRepository;
    @Mock
    private DispatchChannelGateway dispatchChannelGateway;
    @Mock
    private PlatformFileRuntimeRepository runtimeRepository;

    private DeliverDispatchStep step;

    @BeforeEach
    void setUp() {
        step = new DeliverDispatchStep(fileDispatchRepository, dispatchChannelGateway, runtimeRepository);
    }

    @Test
    void stage_returnsDispatch() {
        assertThat(step.stage()).isEqualTo(DispatchStage.DISPATCH);
    }

    @Test
    void execute_failsWhenNoPayloadInContext() {
        DispatchJobContext context = new DispatchJobContext();
        context.setTenantId("t1");
        DispatchStageResult result = step.execute(context);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_LOAD_NO_PAYLOAD");
    }

    @Test
    void execute_failsWhenContextIsNull() {
        DispatchStageResult result = step.execute(null);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_LOAD_NO_PAYLOAD");
    }

    @Test
    void execute_failsWhenFilePrepareContextMissing() {
        DispatchJobContext context = buildContext();
        context.getAttributes().remove(PipelineRuntimeKeys.FILE_ID);
        when(runtimeRepository.toLong(any())).thenReturn(null);

        DispatchStageResult result = step.execute(context);
        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_MISSING");
    }

    @Test
    void execute_insertsNewDispatchRecordWhenNoneExists() {
        setupMocksForNewRecord();
        when(dispatchChannelGateway.dispatch(any())).thenReturn(successResult());
        when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any())).thenReturn(1);

        DispatchJobContext context = buildContext();
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isTrue();
        verify(fileDispatchRepository).insertDispatchRecord(any());
    }

    @Test
    void execute_incrementsAttemptWhenRecordAlreadyExists() {
        Map<String, Object> fileRecord = Map.of("id", 10L);
        Map<String, Object> channelConfig = Map.of("channel_type", "LOCAL");
        when(runtimeRepository.toLong(any())).thenReturn(10L);
        when(fileDispatchRepository.loadLatestDispatchRecord("t1", 10L, "CH1"))
                .thenReturn(Map.of("id", 5L));
        when(dispatchChannelGateway.dispatch(any())).thenReturn(successResult());
        when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any())).thenReturn(1);

        DispatchJobContext context = buildContext(fileRecord, channelConfig);
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isTrue();
        verify(fileDispatchRepository).incrementAttempt("t1", 10L, "CH1");
        verify(fileDispatchRepository, never()).insertDispatchRecord(any());
    }

    @Test
    void execute_failsAndSetsRetryWhenDispatchFails() {
        setupMocksForNewRecord();
        DispatchResult failed = new DispatchResult(false, "ext-1", null, false, false, "connection refused", null);
        when(dispatchChannelGateway.dispatch(any())).thenReturn(failed);

        DispatchJobContext context = buildContext();
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_SEND_FAILED");
        assertThat(context.getAttributes().get("retryRequested")).isEqualTo(Boolean.TRUE);
        assertThat(context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE))
                .isEqualTo(DispatchStage.RETRY.name());
        verify(fileDispatchRepository).markFailed(any(), any(), any(), any(), any());
    }

    @Test
    void execute_failsWhenMarkSentReturnsZero() {
        setupMocksForNewRecord();
        when(dispatchChannelGateway.dispatch(any())).thenReturn(successResult());
        when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any())).thenReturn(0);

        DispatchJobContext context = buildContext();
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_SEND_FAILED");
    }

    @Test
    void execute_failsWhenInsertReturnsZero() {
        Map<String, Object> fileRecord = Map.of("id", 10L);
        Map<String, Object> channelConfig = Map.of("channel_type", "LOCAL");
        when(runtimeRepository.toLong(any())).thenReturn(10L);
        when(fileDispatchRepository.loadLatestDispatchRecord(any(), any(), any())).thenReturn(Map.of());
        when(fileDispatchRepository.insertDispatchRecord(any())).thenReturn(0);

        DispatchJobContext context = buildContext(fileRecord, channelConfig);
        DispatchStageResult result = step.execute(context);

        assertThat(result.success()).isFalse();
        assertThat(result.code()).isEqualTo("DISPATCH_INSERT_FAILED");
    }

    @Test
    void execute_updatesFileStatusToDispatching() {
        setupMocksForNewRecord();
        when(dispatchChannelGateway.dispatch(any())).thenReturn(successResult());
        when(fileDispatchRepository.markSent(any(), any(), any(), any(), any(), any())).thenReturn(1);

        DispatchJobContext context = buildContext();
        step.execute(context);

        verify(runtimeRepository).updateFileStatus(eq(10L), eq("DISPATCHING"), any());
    }

    private void setupMocksForNewRecord() {
        when(runtimeRepository.toLong(any())).thenReturn(10L);
        when(fileDispatchRepository.loadLatestDispatchRecord(any(), any(), any())).thenReturn(Map.of());
        when(fileDispatchRepository.insertDispatchRecord(any())).thenReturn(1);
    }

    private DispatchResult successResult() {
        return new DispatchResult(true, "ext-1", "R-001", true, false, "ok", null);
    }

    private DispatchJobContext buildContext() {
        return buildContext(Map.of("id", 10L), Map.of("channel_type", "LOCAL"));
    }

    private DispatchJobContext buildContext(Map<String, Object> fileRecord, Map<String, Object> channelConfig) {
        DispatchPayload payload = new DispatchPayload("10", null, "CH1", "target", null, null, null, null, null, null);
        DispatchJobContext context = new DispatchJobContext();
        context.setTenantId("t1");
        context.getAttributes().put("dispatchPayload", payload);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, 10L);
        context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
        context.getAttributes().put(PipelineRuntimeKeys.CHANNEL_CONFIG, channelConfig);
        context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 100L);
        context.getAttributes().put(PipelineRuntimeKeys.TRACE_ID, "tr-1");
        return context;
    }
}
