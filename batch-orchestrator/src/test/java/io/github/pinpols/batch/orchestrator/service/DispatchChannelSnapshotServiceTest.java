package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.FrozenDispatchChannelSnapshot;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.mapper.DispatchChannelSnapshotMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发通道配置在实例和整 Workflow Replay 审批快照中固定")
class DispatchChannelSnapshotServiceTest {

  @Mock private DispatchChannelSnapshotMapper channelSnapshots;
  @Mock private SecretPayloadProtector protector;

  private DispatchChannelSnapshotService service;

  @BeforeEach
  void setUp() {
    service = new DispatchChannelSnapshotService(channelSnapshots, protector);
  }

  @Test
  @DisplayName("普通 Dispatch 实例捕获当前完整通道行并覆盖调用方提供的内部快照")
  void shouldCaptureCurrentChannel_whenPinningAndRejectCallerSnapshot() {
    String rowJson = JsonUtils.toJson(channelRow());
    when(channelSnapshots.selectCurrentChannelSnapshotJson("t1", "CH1"))
        .thenReturn(rowJson);
    when(protector.protect(anyString())).thenReturn("protected-channel");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("channelCode", "CH1");
    params.put(FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "caller-value"));

    Map<String, Object> pinned = service.pin("t1", JobType.DISPATCH.code(), params);

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY,
            Map.of("CH1", "protected-channel"));
    verify(channelSnapshots).selectCurrentChannelSnapshotJson("t1", "CH1");
  }

  @Test
  @DisplayName("整 Workflow Replay 从来源实例读取原始冻结配置,不回查当前通道")
  void shouldReuseSourceSnapshot_whenOriginalConfigSelected() {
    String sourceSnapshot = "source-protected-channel";
    when(protector.revealEncrypted(sourceSnapshot)).thenReturn(JsonUtils.toJson(snapshot()));
    Map<String, Object> originalParams = Map.of(
        "channelCode",
        "CH1",
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
        Map.of("CH1", sourceSnapshot));

    DispatchChannelSnapshotService.ReplayDispatchSelection selection =
        service.captureForWorkflowReplay(
            new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
                "t1",
                JobType.DISPATCH.code(),
                Map.of("channelCode", "CALLER_CHANNEL"),
                ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
                originalParams,
                Map.of()));

    assertThat(selection.protectedSnapshots()).containsEntry("CH1", sourceSnapshot);
    assertThat(selection.secretBindings()).isEmpty();
    assertThat(selection.pluginId()).isEqualTo(JobType.DISPATCH.code());
    verifyNoInteractions(channelSnapshots);
  }

  @Test
  @DisplayName("原始参数中的通道集合与审批快照不一致时拒绝预览")
  void shouldRejectChannels_whenOriginalSnapshotSetDiffers() {
    Map<String, Object> originalParams = Map.of(
        "channelCode",
        "CH1",
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
        Map.of("CH1", "source-channel", "CH2", "extra-source-channel"));

    DispatchChannelSnapshotService.ReplayDispatchCaptureRequest captureRequest =
        new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
            "t1",
            JobType.DISPATCH.code(),
            Map.of("channelCode", "CH1"),
            ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
            originalParams,
            Map.of());

    assertThatThrownBy(() -> service.captureForWorkflowReplay(captureRequest))
        .isInstanceOf(BizException.class);
    verifyNoInteractions(channelSnapshots, protector);
  }

  @Test
  @DisplayName("整 Workflow Replay 指定不存在的独立通道版本时失败关闭")
  void shouldFailClosed_whenSpecifiedChannelVersionMissing() {
    DispatchChannelSnapshotService.ReplayDispatchCaptureRequest captureRequest =
        new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
            "t1",
            JobType.DISPATCH.code(),
            Map.of("channelCode", "CH1"),
            ConfigVersionPolicy.USE_SPECIFIED_VERSION,
            Map.of(),
            Map.of());

    assertThatThrownBy(() -> service.captureForWorkflowReplay(captureRequest))
        .isInstanceOf(BizException.class);
    verifyNoInteractions(channelSnapshots);
  }

  @Test
  @DisplayName("整 Workflow Replay 子 Job 启动只接受审批绑定的精确通道集合")
  void shouldRequireApprovedChannelSet_whenPinningWorkflowReplay() {
    when(protector.revealEncrypted("approved-channel"))
        .thenReturn(JsonUtils.toJson(snapshot()));
    Map<String, Object> params = Map.of(
        "channelCode", "CH1",
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
            Map.of("CH1", "untrusted-channel"));

    Map<String, Object> pinned = service.pinWorkflowReplay(
        "t1",
        JobType.DISPATCH.code(),
        params,
        Map.of("CH1", "approved-channel"),
        Map.of());

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY,
            Map.of("CH1", "approved-channel"));
    assertThatThrownBy(() -> service.pinWorkflowReplay(
            "t1",
            JobType.DISPATCH.code(),
            params,
            Map.of("OTHER", "approved-channel"),
            Map.of()))
        .isInstanceOf(BizException.class);
  }

  private static Map<String, Object> channelRow() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", 3L);
    row.put("tenant_id", "t1");
    row.put("channel_code", "CH1");
    row.put("channel_name", "Channel one");
    row.put("channel_type", "LOCAL");
    row.put("target_endpoint", "/tmp/out");
    row.put("auth_type", "NONE");
    row.put("config_json", Map.of("target_endpoint", "/tmp/out"));
    row.put("receipt_policy", "NONE");
    row.put("timeout_seconds", 0);
    row.put("enabled", true);
    row.put("is_deleted", false);
    return row;
  }

  private static FrozenDispatchChannelSnapshot snapshot() {
    return FrozenDispatchChannelSnapshot.capture(channelRow());
  }
}
