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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("分发通道配置在实例和整 Workflow Replay 审批快照中固定")
class DispatchChannelSnapshotServiceTest {

  @Mock
  private DispatchChannelSnapshotMapper channelSnapshots;

  @Mock
  private SecretPayloadProtector protector;

  private DispatchChannelSnapshotService service;

  @BeforeEach
  void setUp() {
    service = new DispatchChannelSnapshotService(channelSnapshots, protector);
  }

  @Test
  @DisplayName("普通 Dispatch 实例捕获当前完整通道行并覆盖调用方提供的内部快照")
  void shouldCaptureCurrentChannel_whenPinningAndRejectCallerSnapshot() {
    String rowJson = JsonUtils.toJson(channelRow());
    when(channelSnapshots.selectCurrentChannelSnapshotJson("t1", "CH1")).thenReturn(rowJson);
    when(protector.protect(anyString())).thenReturn("protected-channel");
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("channelCode", "CH1");
    params.put(FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "caller-value"));

    Map<String, Object> pinned = service.pin("t1", JobType.DISPATCH.code(), params);

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "protected-channel"));
    verify(channelSnapshots).selectCurrentChannelSnapshotJson("t1", "CH1");
  }

  @Test
  @DisplayName("普通分发有未捕获渠道时保留已捕获的渠道快照")
  void shouldKeepCapturedChannel_whenOtherChannelIsUnavailable() {
    when(channelSnapshots.selectCurrentChannelSnapshotJson(ArgumentMatchers.eq("t1"), anyString()))
        .thenAnswer(invocation ->
            "CH1".equals(invocation.getArgument(1)) ? JsonUtils.toJson(channelRow()) : null);
    when(protector.protect(anyString())).thenReturn("protected-one");
    Map<String, Object> params =
        Map.of("channelCode", "CH1", "metadata", Map.of("targetRef", "not-configured"));

    Map<String, Object> pinned = service.pin("t1", JobType.DISPATCH.code(), params);

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "protected-one"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"channelCode", "dispatchChannelCode", "targetChannelCode"})
  @DisplayName("普通分发的明确目标不存在时拒绝创建无快照实例")
  void shouldRejectMissingExplicitChannel_whenPinningDispatch(String field) {
    Map<String, Object> params = Map.of(field, "MISSING");

    assertThatThrownBy(() -> service.pin("t1", JobType.DISPATCH.code(), params))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.file_channel.not_found");
  }

  @Test
  @DisplayName("其他候选可捕获时仍须检查 Worker 实际使用的明确目标")
  void shouldRejectMissingSelectedChannel_whenOtherCandidateExists() {
    when(channelSnapshots.selectCurrentChannelSnapshotJson(ArgumentMatchers.eq("t1"), anyString()))
        .thenAnswer(invocation ->
            "CH1".equals(invocation.getArgument(1)) ? JsonUtils.toJson(channelRow()) : null);
    when(protector.protect(anyString())).thenReturn("protected-one");
    Map<String, Object> params =
        Map.of("channelCode", "MISSING", "metadata", Map.of("targetRef", "CH1"));

    assertThatThrownBy(() -> service.pin("t1", JobType.DISPATCH.code(), params))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.file_channel.not_found");
  }

  @Test
  @DisplayName("明确目标按调度与 Worker 的首个非空字段选择")
  void shouldChooseFirstNonBlankExplicitChannel_whenPinningDispatch() {
    when(channelSnapshots.selectCurrentChannelSnapshotJson(ArgumentMatchers.eq("t1"), anyString()))
        .thenAnswer(invocation ->
            "CH1".equals(invocation.getArgument(1)) ? JsonUtils.toJson(channelRow()) : null);
    when(protector.protect(anyString())).thenReturn("protected-one");
    Map<String, Object> params =
        Map.of("channelCode", " ", "dispatchChannelCode", " CH1 ", "targetChannelCode", "MISSING");

    Map<String, Object> pinned = service.pin("t1", JobType.DISPATCH.code(), params);

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "protected-one"));
  }

  @Test
  @DisplayName("没有顶层明确目标时不把普通业务元数据当必需渠道")
  void shouldNotRejectMetadataOnly_whenNoExplicitChannelExists() {
    Map<String, Object> params = Map.of("metadata", Map.of("targetRef", "business-value"));

    Map<String, Object> pinned = service.pin("t1", JobType.DISPATCH.code(), params);

    assertThat(pinned)
        .containsEntry("metadata", Map.of("targetRef", "business-value"))
        .doesNotContainKey(FrozenDispatchChannelSnapshot.COLLECTION_KEY);
  }

  @Test
  @DisplayName("文件束分发固定分区渠道配置")
  void shouldCaptureChannel_whenPinningBundleDispatch() {
    when(channelSnapshots.selectCurrentChannelSnapshotJson("t1", "CH1"))
        .thenReturn(JsonUtils.toJson(channelRow()));
    when(protector.protect(anyString())).thenReturn("protected-channel");
    Map<String, Object> params =
        Map.of("bundleFiles", List.of(Map.of("sourceFileId", 1, "targetRef", "CH1")));

    Map<String, Object> pinned = service.pin("t1", JobType.BUNDLE_DISPATCH.code(), params);

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "protected-channel"));
  }

  @Test
  @DisplayName("文件束不同分区分别冻结各自渠道")
  void shouldCaptureEachChannel_whenBundleHasMultipleTargets() {
    Map<String, Object> secondRow = channelRow();
    secondRow.put("id", 4L);
    secondRow.put("channel_code", "CH2");
    when(channelSnapshots.selectCurrentChannelSnapshotJson("t1", "CH1"))
        .thenReturn(JsonUtils.toJson(channelRow()));
    when(channelSnapshots.selectCurrentChannelSnapshotJson("t1", "CH2"))
        .thenReturn(JsonUtils.toJson(secondRow));
    when(protector.protect(anyString())).thenReturn("protected-one", "protected-two");
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 1, "targetRef", "CH1"),
            Map.of("sourceFileId", 2, "targetRef", "CH2")));

    Map<String, Object> pinned = service.pin("t1", JobType.BUNDLE_DISPATCH.code(), params);

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY,
            Map.of("CH1", "protected-one", "CH2", "protected-two"));
  }

  @Test
  @DisplayName("文件束任一实际目标渠道不存在时拒绝创建不完整快照")
  void shouldRejectBundle_whenTargetChannelMissing() {
    when(channelSnapshots.selectCurrentChannelSnapshotJson("t1", "CH1"))
        .thenReturn(JsonUtils.toJson(channelRow()));
    when(protector.protect(anyString())).thenReturn("protected-one");
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 1, "targetRef", "CH1"),
            Map.of("sourceFileId", 2, "targetRef", "MISSING")));

    assertThatThrownBy(() -> service.pin("t1", JobType.BUNDLE_DISPATCH.code(), params))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.file_channel.not_found");
  }

  @Test
  @DisplayName("文件束只按有效分区的渠道固定快照，不把业务元数据当渠道")
  void shouldIgnoreUnrelatedTargetRef_whenPinningBundle() {
    when(channelSnapshots.selectCurrentChannelSnapshotJson(ArgumentMatchers.eq("t1"), anyString()))
        .thenAnswer(invocation ->
            "CH1".equals(invocation.getArgument(1)) ? JsonUtils.toJson(channelRow()) : null);
    when(protector.protect(anyString())).thenReturn("protected-one");
    Map<String, Object> params = Map.of(
        "bundleFiles", List.of(Map.of("sourceFileId", 1, "targetRef", "CH1")),
        "metadata", Map.of("targetRef", "business-value"));

    Map<String, Object> pinned = service.pin("t1", JobType.BUNDLE_DISPATCH.code(), params);

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "protected-one"));
    verify(channelSnapshots, Mockito.never())
        .selectCurrentChannelSnapshotJson("t1", "business-value");
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
  @DisplayName("整图重放不将业务元数据中的同名字段当成必需渠道")
  void shouldReplayFrozenTarget_whenMetadataContainsUnrelatedChannel() {
    String sourceSnapshot = "source-protected-channel";
    when(protector.revealEncrypted(sourceSnapshot)).thenReturn(JsonUtils.toJson(snapshot()));
    Map<String, Object> originalParams = Map.of(
        "channelCode",
        "CH1",
        "metadata",
        Map.of("targetRef", "not-configured"),
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
        Map.of("CH1", sourceSnapshot));

    DispatchChannelSnapshotService.ReplayDispatchSelection selection =
        service.captureForWorkflowReplay(
            new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
                "t1",
                JobType.DISPATCH.code(),
                originalParams,
                ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
                originalParams,
                Map.of()));

    assertThat(selection.protectedSnapshots())
        .containsExactlyEntriesOf(Map.of("CH1", sourceSnapshot));
    Map<String, Object> pinned = service.pinWorkflowReplay(
        "t1", JobType.DISPATCH.code(), originalParams, selection.protectedSnapshots(), Map.of());
    assertThat(pinned)
        .containsEntry(FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", sourceSnapshot));
    verifyNoInteractions(channelSnapshots);
  }

  @Test
  @DisplayName("整图重放使用最新配置时只要求实际目标渠道存在")
  void shouldCaptureLatestTarget_whenMetadataContainsUnrelatedChannel() {
    when(channelSnapshots.selectCurrentChannelSnapshotJson(ArgumentMatchers.eq("t1"), anyString()))
        .thenAnswer(invocation ->
            "CH1".equals(invocation.getArgument(1)) ? JsonUtils.toJson(channelRow()) : null);
    when(protector.protect(anyString())).thenReturn("latest-channel");
    when(protector.revealEncrypted("latest-channel")).thenReturn(JsonUtils.toJson(snapshot()));
    Map<String, Object> params =
        Map.of("channelCode", "CH1", "metadata", Map.of("targetRef", "not-configured"));
    var request = new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
        "t1",
        JobType.DISPATCH.code(),
        params,
        ConfigVersionPolicy.USE_LATEST_CONFIG,
        Map.of(),
        Map.of());

    var selection = service.captureForWorkflowReplay(request);
    assertThat(selection.protectedSnapshots())
        .containsExactlyEntriesOf(Map.of("CH1", "latest-channel"));
    assertThat(service.pinWorkflowReplay(
            "t1", JobType.DISPATCH.code(), params, selection.protectedSnapshots(), Map.of()))
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "latest-channel"));
  }

  @Test
  @DisplayName("有其他候选快照时仍须拒绝缺少实际目标快照的重放")
  void shouldRejectReplay_whenSelectedTargetIsNotFrozen() {
    Map<String, Object> params = Map.of(
        "channelCode",
        "CH1",
        "metadata",
        Map.of("targetRef", "CH2"),
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
        Map.of("CH2", "other-channel"));
    var request = new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
        "t1",
        JobType.DISPATCH.code(),
        params,
        ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
        params,
        Map.of());

    assertThatThrownBy(() -> service.captureForWorkflowReplay(request))
        .isInstanceOf(BizException.class);
    assertThatThrownBy(() -> service.pinWorkflowReplay(
            "t1", JobType.DISPATCH.code(), params, Map.of("CH2", "other-channel"), Map.of()))
        .isInstanceOf(BizException.class);
    verifyNoInteractions(channelSnapshots, protector);
  }

  @ParameterizedTest
  @EnumSource(
      value = ConfigVersionPolicy.class,
      names = {"USE_ORIGINAL_CONFIG", "USE_LATEST_CONFIG"})
  @DisplayName("无关候选渠道缺少密钥绑定时不阻断明确目标重放")
  void shouldIgnoreUnboundOptionalChannel_whenReplaying(ConfigVersionPolicy policy) {
    Map<String, Object> optionalRow = channelRow();
    optionalRow.put("channel_code", "CH2");
    optionalRow.put("config_json", Map.of("secret_ref", "missing/key"));
    String mainJson = JsonUtils.toJson(snapshot());
    String optionalJson = JsonUtils.toJson(FrozenDispatchChannelSnapshot.capture(optionalRow));
    when(protector.revealEncrypted("main")).thenReturn(mainJson);
    when(protector.revealEncrypted("optional")).thenReturn(optionalJson);
    if (policy == ConfigVersionPolicy.USE_LATEST_CONFIG) {
      when(channelSnapshots.selectCurrentChannelSnapshotJson(
              ArgumentMatchers.eq("t1"), anyString()))
          .thenAnswer(call -> "CH1".equals(call.getArgument(1))
              ? JsonUtils.toJson(channelRow())
              : JsonUtils.toJson(optionalRow));
      when(protector.protect(anyString()))
          .thenAnswer(call -> ((String) call.getArgument(0)).contains("\"channelCode\":\"CH1\"")
              ? "main"
              : "optional");
    }
    Map<String, Object> params = Map.of(
        "channelCode",
        "CH1",
        "metadata",
        Map.of("targetRef", "CH2"),
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
        Map.of("CH1", "main", "CH2", "optional"));
    var request = new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
        "t1", JobType.DISPATCH.code(), params, policy, params, Map.of());

    var selection = service.captureForWorkflowReplay(request);
    assertThat(selection.protectedSnapshots()).containsExactlyEntriesOf(Map.of("CH1", "main"));
    assertThat(service.pinWorkflowReplay(
            "t1",
            JobType.DISPATCH.code(),
            params,
            selection.protectedSnapshots(),
            selection.secretBindings()))
        .containsEntry(FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "main"));
  }

  @Test
  @DisplayName("实际目标缺少密钥绑定时重放仍失败关闭")
  void shouldRejectUnboundSelectedChannel_whenReplaying() {
    Map<String, Object> row = channelRow();
    row.put("config_json", Map.of("secret_ref", "missing/key"));
    when(protector.revealEncrypted("main"))
        .thenReturn(JsonUtils.toJson(FrozenDispatchChannelSnapshot.capture(row)));
    Map<String, Object> params = Map.of(
        "channelCode", "CH1", FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "main"));
    var request = new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
        "t1",
        JobType.DISPATCH.code(),
        params,
        ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
        params,
        Map.of());
    assertThatThrownBy(() -> service.captureForWorkflowReplay(request))
        .isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("非目标候选已有错误密钥绑定时仍拒绝重放")
  void shouldRejectInvalidOptionalSecretBinding_whenReplaying() {
    Map<String, Object> optionalRow = channelRow();
    optionalRow.put("channel_code", "CH2");
    optionalRow.put("config_json", Map.of("secret_ref", "expected/key"));
    when(protector.revealEncrypted("main")).thenReturn(JsonUtils.toJson(snapshot()));
    when(protector.revealEncrypted("optional"))
        .thenReturn(JsonUtils.toJson(FrozenDispatchChannelSnapshot.capture(optionalRow)));
    Map<String, Object> params = Map.of(
        "channelCode",
        "CH1",
        "metadata",
        Map.of("targetRef", "CH2"),
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
        Map.of("CH1", "main", "CH2", "optional"));
    Map<String, Object> invalidBindings =
        Map.of("CH2", Map.of("secretRef", "wrong/key", "secretVersionId", 7, "versionNo", 1));
    var request = new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
        "t1",
        JobType.DISPATCH.code(),
        params,
        ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
        params,
        invalidBindings);

    assertThatThrownBy(() -> service.captureForWorkflowReplay(request))
        .isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = ConfigVersionPolicy.class,
      names = {"USE_ORIGINAL_CONFIG", "USE_LATEST_CONFIG"})
  @DisplayName("无关停用渠道不阻断实际目标的重放")
  void shouldIgnoreDisabledOptionalChannel_whenReplaying(ConfigVersionPolicy policy) {
    Map<String, Object> optionalRow = channelRow();
    optionalRow.put("channel_code", "CH2");
    optionalRow.put("enabled", false);
    when(protector.revealEncrypted("main")).thenReturn(JsonUtils.toJson(snapshot()));
    when(protector.revealEncrypted("optional"))
        .thenReturn(JsonUtils.toJson(FrozenDispatchChannelSnapshot.capture(optionalRow)));
    if (policy == ConfigVersionPolicy.USE_LATEST_CONFIG) {
      when(channelSnapshots.selectCurrentChannelSnapshotJson(
              ArgumentMatchers.eq("t1"), anyString()))
          .thenAnswer(call -> "CH1".equals(call.getArgument(1))
              ? JsonUtils.toJson(channelRow())
              : JsonUtils.toJson(optionalRow));
      when(protector.protect(anyString()))
          .thenAnswer(call -> ((String) call.getArgument(0)).contains("\"channelCode\":\"CH1\"")
              ? "main"
              : "optional");
    }
    Map<String, Object> params = Map.of(
        "channelCode",
        "CH1",
        "metadata",
        Map.of("targetRef", "CH2"),
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
        Map.of("CH1", "main", "CH2", "optional"));
    var request = new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
        "t1", JobType.DISPATCH.code(), params, policy, params, Map.of());

    var selection = service.captureForWorkflowReplay(request);
    assertThat(selection.protectedSnapshots()).containsExactlyEntriesOf(Map.of("CH1", "main"));
    assertThat(service.pinWorkflowReplay(
            "t1",
            JobType.DISPATCH.code(),
            params,
            selection.protectedSnapshots(),
            selection.secretBindings()))
        .containsEntry(FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "main"));
  }

  @Test
  @DisplayName("实际目标已停用时重放仍失败关闭")
  void shouldRejectDisabledSelectedChannel_whenReplaying() {
    Map<String, Object> row = channelRow();
    row.put("enabled", false);
    when(protector.revealEncrypted("main"))
        .thenReturn(JsonUtils.toJson(FrozenDispatchChannelSnapshot.capture(row)));
    Map<String, Object> params = Map.of(
        "channelCode", "CH1", FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "main"));
    var request = new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
        "t1",
        JobType.DISPATCH.code(),
        params,
        ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
        params,
        Map.of());
    assertThatThrownBy(() -> service.captureForWorkflowReplay(request))
        .isInstanceOf(BizException.class);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("停用候选若带密钥绑定，先验证身份再丢弃该候选")
  void shouldValidateDisabledOptionalBinding_whenReplaying(boolean bindingValid) {
    Map<String, Object> optionalRow = channelRow();
    optionalRow.put("channel_code", "CH2");
    optionalRow.put("enabled", false);
    optionalRow.put("config_json", Map.of("secret_ref", "expected/key"));
    when(protector.revealEncrypted("main")).thenReturn(JsonUtils.toJson(snapshot()));
    when(protector.revealEncrypted("optional"))
        .thenReturn(JsonUtils.toJson(FrozenDispatchChannelSnapshot.capture(optionalRow)));
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("channelCode", "CH1");
    params.put("metadata", Map.of("targetRef", "CH2"));
    params.put(
        FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "main", "CH2", "optional"));
    Map<String, Object> bindings = Map.of(
        "CH2",
        Map.of(
            "secretRef",
            bindingValid ? "expected/key" : "wrong/key",
            "secretVersionId",
            7,
            "versionNo",
            1));
    var request = new DispatchChannelSnapshotService.ReplayDispatchCaptureRequest(
        "t1",
        JobType.DISPATCH.code(),
        params,
        ConfigVersionPolicy.USE_ORIGINAL_CONFIG,
        params,
        bindings);

    if (!bindingValid) {
      assertThatThrownBy(() -> service.captureForWorkflowReplay(request))
          .isInstanceOf(BizException.class);
      return;
    }
    var selection = service.captureForWorkflowReplay(request);
    assertThat(selection.protectedSnapshots()).containsOnlyKeys("CH1");
    assertThat(selection.secretBindings()).isEmpty();
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
    when(protector.revealEncrypted("approved-channel")).thenReturn(JsonUtils.toJson(snapshot()));
    Map<String, Object> params = Map.of(
        "channelCode",
        "CH1",
        FrozenDispatchChannelSnapshot.COLLECTION_KEY,
        Map.of("CH1", "untrusted-channel"));

    Map<String, Object> pinned = service.pinWorkflowReplay(
        "t1", JobType.DISPATCH.code(), params, Map.of("CH1", "approved-channel"), Map.of());

    assertThat(pinned)
        .containsEntry(
            FrozenDispatchChannelSnapshot.COLLECTION_KEY, Map.of("CH1", "approved-channel"));
    assertThatThrownBy(() -> service.pinWorkflowReplay(
            "t1", JobType.DISPATCH.code(), params, Map.of("OTHER", "approved-channel"), Map.of()))
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
