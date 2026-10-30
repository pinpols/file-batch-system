package io.github.pinpols.batch.worker.dispatchs.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import io.github.pinpols.batch.worker.core.domain.StepExecutionRequest;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.DispatchChannelGateway;
import io.github.pinpols.batch.worker.dispatchs.infrastructure.channel.FrozenDispatchChannelResolver;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Dispatch 重放能力解析:仅根据任务携带的冻结渠道配置确认")
class DispatchReplayCapabilityResolverTest {

  private static final String REPLAY_PAYLOAD = """
      {"operationType":"WORKFLOW_RUN","fileId":"10","channelCode":"CH1"}
      """;

  @Mock
  private FrozenDispatchChannelResolver frozenChannels;

  @Mock
  private DispatchChannelGateway channelGateway;

  private DispatchReplayCapabilityResolver resolver;

  @BeforeEach
  void setUp() {
    resolver =
        new DispatchReplayCapabilityResolver(new ObjectMapper(), frozenChannels, channelGateway);
  }

  @Test
  @DisplayName("请求含精确冻结渠道且 Worker 有对应适配器时返回注册的 Dispatch 合同")
  void shouldResolveDeclaredContract_whenFrozenChannelHasInstalledAdapter() {
    when(frozenChannels.resolve("t1", "CH1", REPLAY_PAYLOAD))
        .thenReturn(Optional.of(Map.of("channel_code", "CH1", "channel_type", "API")));
    when(channelGateway.supports("API")).thenReturn(true);

    ReplayCapabilityDeclaration declaration = resolver.resolve(request("t1", REPLAY_PAYLOAD));

    assertThat(declaration).isEqualTo(DispatchReplayCapability.declaration());
    verify(frozenChannels).resolve("t1", "CH1", REPLAY_PAYLOAD);
    verify(channelGateway).supports("API");
  }

  @Test
  @DisplayName("重放载荷使用调度支持的渠道字段时仍要求匹配冻结渠道")
  void shouldResolveAliasOnlyWithMatchingFrozenChannel() {
    String payload = """
        {"operationType":"WORKFLOW_RUN","fileId":"10","targetChannelCode":"CH1"}
        """;
    when(frozenChannels.resolve("t1", "CH1", payload))
        .thenReturn(Optional.of(Map.of("channel_code", "CH1", "channel_type", "API")))
        .thenReturn(Optional.empty());
    when(channelGateway.supports("API")).thenReturn(true);

    assertThat(resolver.resolve(request("t1", payload)))
        .isEqualTo(DispatchReplayCapability.declaration());
    assertThat(resolver.resolve(request("t1", payload)))
        .isEqualTo(ReplayCapabilityDeclaration.unknown("DISPATCH"));
  }

  @Test
  @DisplayName("缺少受保护渠道快照时保持 UNKNOWN")
  void shouldFailClosed_whenFrozenChannelIsMissing() {
    when(frozenChannels.resolve("t1", "CH1", REPLAY_PAYLOAD)).thenReturn(Optional.empty());

    assertThat(resolver.resolve(request("t1", REPLAY_PAYLOAD)))
        .isEqualTo(ReplayCapabilityDeclaration.unknown("DISPATCH"));
    verifyNoInteractions(channelGateway);
  }

  @Test
  @DisplayName("Worker 未安装精确渠道适配器时保持 UNKNOWN")
  void shouldFailClosed_whenChannelAdapterIsNotInstalled() {
    when(frozenChannels.resolve("t1", "CH1", REPLAY_PAYLOAD))
        .thenReturn(Optional.of(Map.of("channel_code", "CH1", "channel_type", "SFTP")));
    when(channelGateway.supports("SFTP")).thenReturn(false);

    assertThat(resolver.resolve(request("t1", REPLAY_PAYLOAD)))
        .isEqualTo(ReplayCapabilityDeclaration.unknown("DISPATCH"));
  }

  @Test
  @DisplayName("非 Workflow Replay 请求不会读取运行时或租户当前渠道配置")
  void shouldFailClosedWithoutResolvingChannel_whenOperationIsNotWorkflowReplay() {
    String normalPayload = """
        {"operationType":"JOB_RERUN","fileId":"10","channelCode":"CH1"}
        """;

    assertThat(resolver.resolve(request("t1", normalPayload)))
        .isEqualTo(ReplayCapabilityDeclaration.unknown("DISPATCH"));
    verifyNoInteractions(frozenChannels, channelGateway);
  }

  @Test
  @DisplayName("文件或渠道身份不完整时保持 UNKNOWN")
  void shouldFailClosed_whenDispatchIdentityIsIncomplete() {
    String missingFileIdPayload = """
        {"operationType":"WORKFLOW_RUN","channelCode":"CH1"}
        """;

    assertThat(resolver.resolve(request("t1", missingFileIdPayload)))
        .isEqualTo(ReplayCapabilityDeclaration.unknown("DISPATCH"));
    verifyNoInteractions(frozenChannels, channelGateway);
  }

  private StepExecutionRequest request(String tenantId, String rawPayload) {
    return new StepExecutionRequest(
        tenantId,
        "job-dispatch",
        "DISPATCH",
        "worker-1",
        Map.of(PipelineRuntimeKeys.PAYLOAD, rawPayload));
  }
}
