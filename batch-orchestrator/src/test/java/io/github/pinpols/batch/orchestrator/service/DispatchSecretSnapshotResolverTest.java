package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.DispatchTaskKeys;
import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.domain.view.DispatchSecretBindingView;
import io.github.pinpols.batch.orchestrator.mapper.DispatchSecretBindingMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("启动时固定 Dispatch SecretVersion")
class DispatchSecretSnapshotResolverTest {

  @Mock
  private DispatchSecretBindingMapper bindingMapper;

  @Test
  @DisplayName("Dispatch 实例快照保存版本 ID 和版本号,不保存密钥材料")
  void pinsCurrentVersion_whenDispatchInstanceCreated() {
    DispatchSecretSnapshotResolver resolver = resolver();
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1")))
        .thenReturn(List.of(new DispatchSecretBindingView("CH1", "dispatch/key", 42L, 3)));

    Map<String, Object> result =
        resolver.pin("t1", JobType.DISPATCH.code(), Map.of("channelCode", "CH1"));

    assertThat(result)
        .containsEntry(
            DispatchTaskKeys.SECRET_BINDINGS,
            Map.of(
                "CH1", Map.of("secretRef", "dispatch/key", "secretVersionId", 42L, "versionNo", 3)))
        .doesNotContainValue("secret material");
  }

  @Test
  @DisplayName("文件束分发为各分区渠道固定凭据版本")
  void pinsCurrentVersion_whenBundleDispatchInstanceCreated() {
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1")))
        .thenReturn(List.of(new DispatchSecretBindingView("CH1", "dispatch/key", 42L, 3)));
    Map<String, Object> params =
        Map.of("bundleFiles", List.of(Map.of("sourceFileId", 1, "targetRef", "CH1")));

    Map<String, Object> result = resolver().pin("t1", JobType.BUNDLE_DISPATCH.code(), params);

    assertThat(result)
        .containsEntry(
            DispatchTaskKeys.SECRET_BINDINGS,
            Map.of(
                "CH1",
                Map.of("secretRef", "dispatch/key", "secretVersionId", 42L, "versionNo", 3)));
  }

  @Test
  @DisplayName("文件束不同分区分别固定各自渠道的凭据版本")
  void pinsEachChannelVersion_whenBundleHasMultipleTargets() {
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1", "CH2")))
        .thenReturn(List.of(
            new DispatchSecretBindingView("CH1", "key/one", 42L, 3),
            new DispatchSecretBindingView("CH2", "key/two", 51L, 4)));
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 1, "targetRef", "CH1"),
            Map.of("sourceFileId", 2, "targetRef", "CH2")));

    Map<String, Object> result = resolver().pin("t1", JobType.BUNDLE_DISPATCH.code(), params);

    assertThat(result)
        .containsEntry(
            DispatchTaskKeys.SECRET_BINDINGS,
            Map.of(
                "CH1", Map.of("secretRef", "key/one", "secretVersionId", 42L, "versionNo", 3),
                "CH2", Map.of("secretRef", "key/two", "secretVersionId", 51L, "versionNo", 4)));
  }

  @Test
  @DisplayName("文件束只查询有效分区的渠道密钥，不把业务元数据当渠道")
  void ignoresUnrelatedTargetRef_whenBundleDispatch() {
    when(bindingMapper.selectCurrentByChannels(eq("t1"), anyList()))
        .thenReturn(List.of(new DispatchSecretBindingView("CH1", "key/one", 42L, 3)));
    Map<String, Object> params = Map.of(
        "bundleFiles", List.of(Map.of("sourceFileId", 1, "targetRef", "CH1")),
        "metadata", Map.of("targetRef", "business-value"));

    Map<String, Object> result = resolver().pin("t1", JobType.BUNDLE_DISPATCH.code(), params);

    assertThat(result)
        .containsEntry(
            DispatchTaskKeys.SECRET_BINDINGS,
            Map.of("CH1", Map.of("secretRef", "key/one", "secretVersionId", 42L, "versionNo", 3)));
    verify(bindingMapper).selectCurrentByChannels("t1", List.of("CH1"));
  }

  @Test
  @DisplayName("普通分发明确渠道可用时，不因业务元数据引用的其他渠道密钥缺失而阻断")
  void shouldIgnoreUnavailableUnselectedChannel_whenExplicitTargetIsValid() {
    when(bindingMapper.selectCurrentByChannels(eq("t1"), anyList()))
        .thenReturn(List.of(
            new DispatchSecretBindingView("CH1", "key/one", 42L, 3),
            new DispatchSecretBindingView("OTHER", "key/other", null, null)));
    Map<String, Object> params =
        Map.of("channelCode", "CH1", "metadata", Map.of("targetRef", "OTHER"));

    Map<String, Object> result = resolver().pin("t1", JobType.DISPATCH.code(), params);

    assertThat(result)
        .containsEntry(
            DispatchTaskKeys.SECRET_BINDINGS,
            Map.of("CH1", Map.of("secretRef", "key/one", "secretVersionId", 42L, "versionNo", 3)));
  }

  @Test
  @DisplayName("没有明确渠道时未解析候选仍失败关闭")
  void shouldRejectUnavailableCandidate_whenNoExplicitTargetExists() {
    when(bindingMapper.selectCurrentByChannels(eq("t1"), anyList()))
        .thenReturn(List.of(new DispatchSecretBindingView("CH2", "key/two", null, null)));
    Map<String, Object> params = Map.of("metadata", Map.of("targetRef", "CH2"));

    assertThatThrownBy(() -> resolver().pin("t1", JobType.DISPATCH.code(), params))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.CREDENTIAL_REF_UNRESOLVED);
  }

  @Test
  @DisplayName("文件束任一目标渠道的密钥缺失仍失败关闭")
  void shouldRejectUnavailableBundleTarget_whenAnotherChannelIsValid() {
    when(bindingMapper.selectCurrentByChannels(eq("t1"), anyList()))
        .thenReturn(List.of(
            new DispatchSecretBindingView("CH1", "key/one", 42L, 3),
            new DispatchSecretBindingView("CH2", "key/two", null, null)));
    Map<String, Object> params = Map.of(
        "bundleFiles",
        List.of(
            Map.of("sourceFileId", 1, "targetRef", "CH1"),
            Map.of("sourceFileId", 2, "targetRef", "CH2")));

    assertThatThrownBy(() -> resolver().pin("t1", JobType.BUNDLE_DISPATCH.code(), params))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.CREDENTIAL_REF_UNRESOLVED);
  }

  @Test
  @DisplayName("渠道声明 SecretVersion 但没有当前有效版本时拒绝创建实例")
  void rejectsChannel_whenPublishedVersionUnavailable() {
    DispatchSecretSnapshotResolver resolver = resolver();
    String jobType = JobType.DISPATCH.code();
    Map<String, Object> params = Map.of("channelCode", "CH1");
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1")))
        .thenReturn(List.of(new DispatchSecretBindingView("CH1", "dispatch/key", null, null)));

    assertThatThrownBy(() -> resolver.pin("t1", jobType, params))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.CREDENTIAL_REF_UNRESOLVED);
  }

  @Test
  @DisplayName("非 Dispatch 作业不查询密钥渠道")
  void skipsLookup_whenJobTypeIsNotDispatch() {
    Map<String, Object> params = Map.of("channelCode", "CH1");

    assertThat(resolver().pin("t1", JobType.IMPORT.code(), params)).isSameAs(params);

    verifyNoInteractions(bindingMapper);
  }

  @Test
  @DisplayName("拒绝请求参数伪造内部固定版本绑定")
  void removesCallerBindings_whenNoServerBindingExists() {
    Map<String, Object> params = Map.of(
        "channelCode",
        "CH1",
        DispatchTaskKeys.SECRET_BINDINGS,
        Map.of("CH1", Map.of("secretRef", "dispatch/old", "secretVersionId", 1L)));
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1"))).thenReturn(List.of());

    Map<String, Object> result = resolver().pin("t1", JobType.DISPATCH.code(), params);

    assertThat(result)
        .containsEntry("channelCode", "CH1")
        .doesNotContainKey(DispatchTaskKeys.SECRET_BINDINGS);
  }

  @Test
  @DisplayName("非 Dispatch 作业也不传播调用方提供的内部密钥绑定")
  void stripsCallerBindings_whenJobTypeIsNotDispatch() {
    Map<String, Object> params = Map.of(
        DispatchTaskKeys.SECRET_BINDINGS,
        Map.of("CH1", Map.of("secretRef", "dispatch/old", "secretVersionId", 1L)));

    assertThat(resolver().pin("t1", JobType.IMPORT.code(), params))
        .doesNotContainKey(DispatchTaskKeys.SECRET_BINDINGS);
    verifyNoInteractions(bindingMapper);
  }

  private DispatchSecretSnapshotResolver resolver() {
    return new DispatchSecretSnapshotResolver(bindingMapper);
  }
}
