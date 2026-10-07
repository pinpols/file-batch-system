package io.github.pinpols.batch.worker.dispatchs.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchJobContext;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchPayload;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStage;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchStageResult;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.DispatchRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.FileDispatchRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发准备阶段:入参校验、文件与渠道装载失败的错误码,以及上下文填充、强制重试与解析失败")
class PrepareDispatchStepTest {

  @Mock
  private FileDispatchRepository fileDispatchRepository;

  @Mock
  private PlatformPipelineRunRepository pipelineRuns;

  private PrepareDispatchStep step;

  @BeforeEach
  void setUp() {
    step = new PrepareDispatchStep(new ObjectMapper(), fileDispatchRepository, pipelineRuns);
  }

  @Test
  @DisplayName("阶段标识为分发准备阶段")
  void stage_returnsPrepare() {
    assertThat(step.stage()).isEqualTo(DispatchStage.PREPARE);
  }

  @Test
  @DisplayName("上下文为空时判定失败,并给出参数非法错误码")
  void execute_failsWhenContextIsNull() {
    DispatchStageResult result = step.execute(null);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_INVALID");
  }

  @Test
  @DisplayName("上下文缺少租户号时判定失败,并给出参数非法错误码")
  void execute_failsWhenTenantIdBlank() {
    DispatchJobContext context = new DispatchJobContext();
    context.setRawPayload("{\"fileId\":\"1\",\"channelCode\":\"CH1\"}");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_INVALID");
  }

  @Test
  @DisplayName("上下文缺少原始载荷时判定失败,并给出参数非法错误码")
  void execute_failsWhenPayloadBlank() {
    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_INVALID");
  }

  @Test
  @DisplayName("载荷缺少文件号时判定失败,并给出文件缺失错误码")
  void execute_failsWhenFileIdMissingInPayload() {
    DispatchJobContext context = buildContext("{\"channelCode\":\"CH1\"}");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_FILE_MISSING");
  }

  @Test
  @DisplayName("文件记录查不到时判定失败,并给出文件不存在错误码")
  void execute_failsWhenFileRecordNotFound() {
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(Map.of());
    DispatchJobContext context = buildContext("{\"fileId\":\"10\",\"channelCode\":\"CH1\"}");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_FILE_NOT_FOUND");
  }

  @Test
  @DisplayName("渠道配置查不到时判定失败,并给出渠道不存在错误码")
  void execute_failsWhenChannelNotFound() {
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(Map.of("id", 10L));
    when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(Map.of());
    DispatchJobContext context = buildContext("{\"fileId\":\"10\",\"channelCode\":\"CH1\"}");
    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isFalse();
    assertThat(result.code()).isEqualTo("DISPATCH_PREPARE_CHANNEL_NOT_FOUND");
  }

  @Test
  @DisplayName("文件与渠道都存在时准备成功:上下文补齐文件号、文件记录与渠道配置,并绑定流水线实例")
  void execute_succeedsAndPopulatesContext() {
    Map<String, Object> fileRecord = Map.of("id", 10L, "status", "PENDING");
    Map<String, Object> channelRow = Map.of("channel_type", "LOCAL", "channel_code", "CH1");
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(fileRecord);
    when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(channelRow);

    DispatchJobContext context = buildContext("{\"fileId\":\"10\",\"channelCode\":\"CH1\"}");
    context.getAttributes().put(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID, 100L);

    DispatchStageResult result = step.execute(context);

    assertThat(result.success()).isTrue();
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID)).isEqualTo(10L);
    assertThat(context.getAttributes()).containsEntry(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
    assertThat(context.getAttributes().get(PipelineRuntimeKeys.CHANNEL_CONFIG)).isNotNull();
    verify(pipelineRuns).bindFileToPipelineInstance(100L, 10L);
  }

  @Test
  @DisplayName("载荷带强制重试标记时,上下文的请求重试属性置为真")
  void execute_setsForceRetryFlagWhenPayloadHasForceRetry() {
    Map<String, Object> fileRecord = Map.of("id", 10L);
    Map<String, Object> channelRow = Map.of("channel_type", "LOCAL");
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(fileRecord);
    when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(channelRow);

    DispatchJobContext context =
        buildContext("{\"fileId\":\"10\",\"channelCode\":\"CH1\",\"forceRetry\":true}");
    step.execute(context);

    assertThat(context.getAttributes())
        .containsEntry(DispatchRuntimeKeys.RETRY_REQUESTED, Boolean.TRUE);
  }

  @Test
  @DisplayName("上下文已有解析好的载荷时直接复用,原始载荷不是合法 JSON 也照样准备成功")
  void execute_usesAlreadyParsedPayloadWhenPresentInAttributes() {
    DispatchPayload prebuilt =
        new DispatchPayload("10", null, "CH1", null, null, null, null, null, null, null);
    Map<String, Object> fileRecord = Map.of("id", 10L);
    Map<String, Object> channelRow = Map.of("channel_type", "LOCAL");
    when(fileDispatchRepository.loadFile("t1", 10L)).thenReturn(fileRecord);
    when(fileDispatchRepository.loadChannel("t1", "CH1")).thenReturn(channelRow);

    DispatchJobContext context = new DispatchJobContext();
    context.setTenantId("t1");
    context.setRawPayload("INVALID_JSON"); // would fail if re-parsed
    context.getAttributes().put(DispatchRuntimeKeys.DISPATCH_PAYLOAD, prebuilt);

    DispatchStageResult result = step.execute(context);
    assertThat(result.success()).isTrue();
  }

  @Test
  @DisplayName("原始载荷不是合法 JSON 时判定失败,并给出解析失败错误码")
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
