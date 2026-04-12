package com.example.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.dispatchs.domain.DispatchJobContext;
import com.example.batch.worker.dispatchs.domain.DispatchPayload;
import com.example.batch.worker.dispatchs.domain.DispatchStage;
import com.example.batch.worker.dispatchs.domain.DispatchStageResult;
import com.example.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PrepareDispatchStepTest {

  @Mock private FileDispatchRepository fileDispatchRepository;
  @Mock private PlatformFileRuntimeRepository runtimeRepository;

  private PrepareDispatchStep step;

  @BeforeEach
  void setUp() {
    step = new PrepareDispatchStep(new ObjectMapper(), fileDispatchRepository, runtimeRepository);
  }

  @Test
  void stage_returnsPrepare() {
    assertThat(step.stage()).isEqualTo(DispatchStage.PREPARE);
  }

  @Test
  void execute_failsWhenContextIsNull() {
    DispatchStageResult result = step.execute(null);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_INVALID");
  }

  @Test
  void execute_failsWhenTenantIdBlank() {
    DispatchJobContext context = new DispatchJobContext();
    context.setRawPayload("{\"fileId\":\"1\",\"channelCode\":\"CH1\"}");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_INVALID");
  }

  @Test
  void execute_failsWhenPayloadBlank() {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_INVALID");
  }

  @Test
  void execute_failsWhenFileIdMissingInPayload() {
    DispatchJobContext context = buildContext("{\"channelCode\":\"CH1\"}");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_FILE_MISSING");
  }

  @Test
  void execute_failsWhenFileRecordNotFound() {
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(Map.of());
    DispatchJobContext context = buildContext("{\"fileId\":\"10\",\"channelCode\":\"CH1\"}");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_FILE_NOT_FOUND");
  }

  @Test
  void execute_failsWhenChannelNotFound() {
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(Map.of("id", 10L));
    when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(Map.of());
    DispatchJobContext context = buildContext("{\"fileId\":\"10\",\"channelCode\":\"CH1\"}");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_CHANNEL_NOT_FOUND");
  }

  @Test
  void execute_succeedsAndPopulatesContext() {
    Map<String, Object> fileRecord = Map.of("id", 10L, "status", "PENDING");
    Map<String, Object> channelRow = Map.of("channel_type", "LOCAL", "channel_code", "CH1");
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(fileRecord);
    when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(channelRow);
    when(runtimeRepository.toLong(any())).thenReturn(100L);

    DispatchJobContext context = buildContext("{\"fileId\":\"10\",\"channelCode\":\"CH1\"}");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 100L);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)).isEqualTo(10L);
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.FILE_RECORD)).isEqualTo(fileRecord);
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.CHANNEL_CONFIG)).isNotNull();
    verify(runtimeRepository).bindFileToPipelineInstance(eq(100L), eq(10L));
  }

  @Test
  void execute_setsForceRetryFlagWhenPayloadHasForceRetry() {
    Map<String, Object> fileRecord = Map.of("id", 10L);
    Map<String, Object> channelRow = Map.of("channel_type", "LOCAL");
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(fileRecord);
    when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(channelRow);
    when(runtimeRepository.toLong(any())).thenReturn(100L);

    DispatchJobContext context =
        buildContext("{\"fileId\":\"10\",\"channelCode\":\"CH1\",\"forceRetry\":true}");
    step.execute(context);

    assertThat(context.getAttributes().get("retryRequested")).isEqualTo(Boolean.TRUE);
  }

  @Test
  void execute_usesAlreadyParsedPayloadWhenPresentInAttributes() {
    DispatchPayload prebuilt =
        new DispatchPayload("10", null, "CH1", null, null, null, null, null, null, null);
    Map<String, Object> fileRecord = Map.of("id", 10L);
    Map<String, Object> channelRow = Map.of("channel_type", "LOCAL");
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(fileRecord);
    when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(channelRow);
    when(runtimeRepository.toLong(any())).thenReturn(100L);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setRawPayload("INVALID_JSON"); // would fail if re-parsed
    context.getAttributes().put("dispatchPayload", prebuilt);

    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isTrue();
  }

  @Test
  void execute_failsOnJsonParseError() {
    DispatchJobContext context = buildContext("NOT_VALID_JSON");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_PARSE_FAILED");
  }

  private DispatchJobContext buildContext(String rawPayload) {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setRawPayload(rawPayload);
    return context;
  }
}
