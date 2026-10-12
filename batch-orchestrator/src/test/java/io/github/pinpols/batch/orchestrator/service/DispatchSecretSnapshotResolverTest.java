package io.github.pinpols.batch.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.JobType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.orchestrator.domain.entity.DispatchSecretBindingEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.orchestrator.mapper.DispatchSecretBindingMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowNodeMapper;
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

  @Mock
  private WorkflowNodeMapper workflowNodeMapper;

  @Test
  @DisplayName("Dispatch 实例快照保存版本 ID 和版本号,不保存密钥材料")
  void pinsCurrentVersion_whenDispatchInstanceCreated() {
    DispatchSecretSnapshotResolver resolver = resolver();
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1")))
        .thenReturn(List.of(new DispatchSecretBindingEntity("CH1", "dispatch/key", 42L, 3)));

    Map<String, Object> result =
        resolver.pin("t1", JobType.DISPATCH.code(), null, Map.of("channelCode", "CH1"));

    assertThat(result)
        .containsEntry(
            DispatchSecretSnapshotResolver.SNAPSHOT_KEY,
            Map.of(
                "CH1", Map.of("secretRef", "dispatch/key", "secretVersionId", 42L, "versionNo", 3)))
        .doesNotContainValue("secret material");
    verifyNoInteractions(workflowNodeMapper);
  }

  @Test
  @DisplayName("Workflow 节点渠道引用固定到父实例快照")
  void pinsWorkflowNodeVersions_whenWorkflowInstanceCreated() {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeParams("{\"channelCode\":\"CH1\"}");
    when(workflowNodeMapper.selectByWorkflowDefinitionId(9L)).thenReturn(List.of(node));
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1")))
        .thenReturn(List.of(new DispatchSecretBindingEntity("CH1", "dispatch/key", 42L, 3)));

    Map<String, Object> result = resolver().pin("t1", JobType.WORKFLOW.code(), 9L, Map.of());

    assertThat(result)
        .containsEntry(
            DispatchSecretSnapshotResolver.SNAPSHOT_KEY,
            Map.of(
                "CH1",
                Map.of("secretRef", "dispatch/key", "secretVersionId", 42L, "versionNo", 3)));
  }

  @Test
  @DisplayName("渠道声明 SecretVersion 但没有当前有效版本时拒绝创建实例")
  void rejectsChannel_whenPublishedVersionUnavailable() {
    DispatchSecretSnapshotResolver resolver = resolver();
    String jobType = JobType.DISPATCH.code();
    Map<String, Object> params = Map.of("channelCode", "CH1");
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1")))
        .thenReturn(List.of(new DispatchSecretBindingEntity("CH1", "dispatch/key", null, null)));

    assertThatThrownBy(() -> resolver.pin("t1", jobType, null, params))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.CREDENTIAL_REF_UNRESOLVED);
  }

  @Test
  @DisplayName("非 Dispatch 作业不查询密钥渠道")
  void skipsLookup_whenJobTypeIsNotDispatch() {
    Map<String, Object> params = Map.of("channelCode", "CH1");

    assertThat(resolver().pin("t1", JobType.IMPORT.code(), null, params)).isSameAs(params);

    verifyNoInteractions(bindingMapper, workflowNodeMapper);
  }

  @Test
  @DisplayName("Workflow 节点参数为 JSON null 时按空对象处理")
  void treatsNullNodeParams_asEmptyObject() {
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeParams("null");
    when(workflowNodeMapper.selectByWorkflowDefinitionId(9L)).thenReturn(List.of(node));

    Map<String, Object> result = resolver().pin("t1", JobType.WORKFLOW.code(), 9L, Map.of());

    assertThat(result).isEmpty();
    verifyNoInteractions(bindingMapper);
  }

  @Test
  @DisplayName("拒绝请求参数伪造内部固定版本绑定")
  void removesCallerBindings_whenNoServerBindingExists() {
    Map<String, Object> params = Map.of(
        "channelCode",
        "CH1",
        DispatchSecretSnapshotResolver.SNAPSHOT_KEY,
        Map.of("CH1", Map.of("secretRef", "dispatch/old", "secretVersionId", 1L)));
    when(bindingMapper.selectCurrentByChannels("t1", List.of("CH1"))).thenReturn(List.of());

    Map<String, Object> result = resolver().pin("t1", JobType.DISPATCH.code(), null, params);

    assertThat(result)
        .containsEntry("channelCode", "CH1")
        .doesNotContainKey(DispatchSecretSnapshotResolver.SNAPSHOT_KEY);
  }

  @Test
  @DisplayName("非 Dispatch 作业也不传播调用方提供的内部密钥绑定")
  void stripsCallerBindings_whenJobTypeIsNotDispatch() {
    Map<String, Object> params = Map.of(
        DispatchSecretSnapshotResolver.SNAPSHOT_KEY,
        Map.of("CH1", Map.of("secretRef", "dispatch/old", "secretVersionId", 1L)));

    assertThat(resolver().pin("t1", JobType.IMPORT.code(), null, params))
        .doesNotContainKey(DispatchSecretSnapshotResolver.SNAPSHOT_KEY);
    verifyNoInteractions(bindingMapper, workflowNodeMapper);
  }

  private DispatchSecretSnapshotResolver resolver() {
    return new DispatchSecretSnapshotResolver(bindingMapper, workflowNodeMapper);
  }
}
