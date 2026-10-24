package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.param.WorkflowLaunchWaitInsertParam;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowLaunchWaitMapper;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

@DisplayName("Workflow 容量等待必须绑定原请求并原子推进状态")
class WorkflowLaunchWaitServiceTest {

  private WorkflowLaunchWaitMapper waitingMapper;
  private TriggerRequestMapper requestMapper;
  private WorkflowLaunchWaitService service;
  private SecretPayloadProtector protector;

  @BeforeEach
  void setUp() {
    waitingMapper = mock(WorkflowLaunchWaitMapper.class);
    requestMapper = mock(TriggerRequestMapper.class);
    protector = mock(SecretPayloadProtector.class);
    when(protector.protect(any())).thenReturn("sealed-wait-snapshot");
    service = new WorkflowLaunchWaitService(waitingMapper, requestMapper, protector);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "definition", "date", "dryRun", "REJECTED", "GIVE_UP"})
  @DisplayName("原请求缺失、目标或业务日错配、演练属性变化和终态均不能创建容量等待")
  void shouldRejectInvalidOriginalRequest(String scenario) {
    LaunchRequest request = request();
    TriggerRequestEntity original = pending(request);
    switch (scenario) {
      case "definition" -> original.setWorkflowDefinitionId(99L);
      case "date" -> original.setBizDate(request.bizDate().plusDays(1));
      case "dryRun" -> original.setDryRun(true);
      case "REJECTED", "GIVE_UP" -> original.setRequestStatus(scenario);
      default -> {}
    }
    when(requestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "root-request"))
        .thenReturn("missing".equals(scenario) ? null : original);
    when(waitingMapper.defer(any())).thenReturn(1);

    WorkflowLaunchWaitSnapshot snapshot = WorkflowLaunchWaitFixtures.snapshot(request);
    assertThatThrownBy(() -> service.defer(request, snapshot)).isInstanceOf(BizException.class);
    verify(waitingMapper, never()).defer(any());
  }

  @Test
  @DisplayName("等待已写入但原请求状态更新为零行时必须失败，由事务回滚等待")
  void shouldRejectZeroRowRequestTransition() {
    LaunchRequest request = request();
    when(requestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "root-request"))
        .thenReturn(pending(request));
    when(waitingMapper.defer(any())).thenReturn(1);
    when(requestMapper.updateAcceptance("tenant-a", "root-request", "PROCESSING", null))
        .thenReturn(0);

    WorkflowLaunchWaitSnapshot snapshot = WorkflowLaunchWaitFixtures.snapshot(request);
    assertThatThrownBy(() -> service.defer(request, snapshot)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("容量等待仅保存密文封套，原请求和有效参数不以明文落库")
  void shouldPersistOnlySealedSnapshot() {
    LaunchRequest request = request().toBuilder().params(Map.of("apiToken", "raw-secret")).build();
    WorkflowLaunchWaitSnapshot snapshot = WorkflowLaunchWaitFixtures.snapshot(request);
    when(requestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "root-request"))
        .thenReturn(pending(request));
    when(waitingMapper.defer(any())).thenReturn(1);
    when(requestMapper.updateAcceptance("tenant-a", "root-request", "PROCESSING", null))
        .thenReturn(1);

    service.defer(request, snapshot);

    ArgumentCaptor<WorkflowLaunchWaitInsertParam> inserted =
        ArgumentCaptor.forClass(WorkflowLaunchWaitInsertParam.class);
    verify(waitingMapper).defer(inserted.capture());
    String payload = inserted.getValue().launchPayload();
    assertThat(payload).contains("WORKFLOW_CAPACITY_WAIT_V1", "sealed-wait-snapshot")
        .doesNotContain("raw-secret", "executionSnapshotJson", "originalRequestJson");
    String snapshotJson = JsonUtils.toJson(snapshot);
    verify(protector).protect(snapshotJson);
  }

  @Test
  @DisplayName("同请求变参重投不能替换或拒绝首个已冻结等待")
  void shouldRejectChangedRedeliveryWithoutReplacingFirstSnapshot() {
    LaunchRequest original = request().toBuilder().params(Map.of("apiToken", "first-secret")).build();
    WorkflowLaunchWaitSnapshot snapshot = WorkflowLaunchWaitFixtures.snapshot(original);
    String sealedPayload = JsonUtils.toJson(Map.of(
        "tenantId", original.tenantId(), "requestId", original.requestId(),
        "workflowDefinitionId", original.workflowDefinitionId(), "bizDate", original.bizDate(),
        "dryRun", false, "format", "WORKFLOW_CAPACITY_WAIT_V1",
        "sealedSnapshotPayload", "sealed-wait-snapshot"));
    WorkflowLaunchWaitClaim waiting = new WorkflowLaunchWaitClaim(
        original.tenantId(), original.requestId(), original.workflowDefinitionId(), original.bizDate(),
        false, sealedPayload);
    when(requestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "root-request"))
        .thenReturn(pending(original));
    when(waitingMapper.selectWaitingForUpdate("tenant-a", "root-request")).thenReturn(waiting);
    String snapshotJson = JsonUtils.toJson(snapshot);
    when(protector.revealEncrypted("sealed-wait-snapshot")).thenReturn(snapshotJson);
    LaunchRequest changed = original.toBuilder().params(Map.of("apiToken", "second-secret")).build();

    assertThatThrownBy(() -> service.findSnapshot(changed)).isInstanceOf(BizException.class);

    verify(waitingMapper, never()).defer(any());
    verify(requestMapper, never()).updateAcceptance(any(), any(), any(), any());
  }

  private static LaunchRequest request() {
    return LaunchRequest.builder()
        .tenantId("tenant-a")
        .requestId("root-request")
        .workflowDefinitionId(17L)
        .bizDate(LocalDate.of(2026, 10, 18))
        .triggerType(TriggerType.API)
        .build();
  }

  @ParameterizedTest
  @ValueSource(strings = {"LAUNCHED", "REJECTED", "DUPLICATE", "GIVE_UP"})
  @DisplayName("等待拒绝先锁原请求，保留已有终态并按派发事实收敛等待")
  void shouldPreserveTerminalRequestAndLockParentBeforeWait(String status) {
    TriggerRequestEntity original = pending(request());
    original.setRequestStatus(status);
    when(requestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "root-request"))
        .thenReturn(original);

    service.reject("tenant-a", "root-request");

    InOrder order = inOrder(requestMapper, waitingMapper);
    order.verify(requestMapper).selectByTenantAndRequestIdForUpdate("tenant-a", "root-request");
    if ("LAUNCHED".equals(status)) {
      order.verify(waitingMapper).markResumed("tenant-a", "root-request");
    } else {
      order.verify(waitingMapper).markRejected("tenant-a", "root-request");
    }
    verify(requestMapper, never()).updateAcceptance(any(), any(), any(), any());
  }

  @Test
  @DisplayName("等待拒绝后的原请求 CAS 零行必须失败，不能提交半个拒绝事务")
  void shouldRollbackRejectedWaitWhenRequestTransitionFails() {
    when(requestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "root-request"))
        .thenReturn(pending(request()));
    when(waitingMapper.markRejected("tenant-a", "root-request")).thenReturn(1);

    assertThatThrownBy(() -> service.reject("tenant-a", "root-request"))
        .isInstanceOf(BizException.class);
  }

  private static TriggerRequestEntity pending(LaunchRequest request) {
    TriggerRequestEntity result = new TriggerRequestEntity();
    result.setTenantId(request.tenantId());
    result.setRequestId(request.requestId());
    result.setWorkflowDefinitionId(request.workflowDefinitionId());
    result.setBizDate(request.bizDate());
    result.setDryRun(false);
    result.setRequestStatus("ACCEPTED");
    return result;
  }
}
