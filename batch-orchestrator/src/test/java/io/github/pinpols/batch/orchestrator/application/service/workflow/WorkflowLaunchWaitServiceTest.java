package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowLaunchWaitMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Workflow 容量等待必须绑定原请求并原子推进状态")
class WorkflowLaunchWaitServiceTest {

  private WorkflowLaunchWaitMapper waitingMapper;
  private TriggerRequestMapper requestMapper;
  private WorkflowLaunchWaitService service;

  @BeforeEach
  void setUp() {
    waitingMapper = mock(WorkflowLaunchWaitMapper.class);
    requestMapper = mock(TriggerRequestMapper.class);
    service = new WorkflowLaunchWaitService(waitingMapper, requestMapper);
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
      default -> { }
    }
    when(requestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "root-request"))
        .thenReturn("missing".equals(scenario) ? null : original);
    when(waitingMapper.defer(any())).thenReturn(1);

    assertThatThrownBy(() -> service.defer(request)).isInstanceOf(BizException.class);
    verify(waitingMapper, never()).defer(any());
  }

  @Test
  @DisplayName("等待已写入但原请求状态更新为零行时必须失败，由事务回滚等待")
  void shouldRejectZeroRowRequestTransition() {
    LaunchRequest request = request();
    when(requestMapper.selectByTenantAndRequestIdForUpdate("tenant-a", "root-request"))
        .thenReturn(pending(request));
    when(waitingMapper.defer(any())).thenReturn(1);
    when(requestMapper.updateAcceptance("tenant-a", "root-request", "PROCESSING", null)).thenReturn(0);

    assertThatThrownBy(() -> service.defer(request)).isInstanceOf(BizException.class);
  }

  private static LaunchRequest request() {
    return LaunchRequest.builder().tenantId("tenant-a").requestId("root-request")
        .workflowDefinitionId(17L).bizDate(LocalDate.of(2026, 10, 18)).triggerType(TriggerType.API).build();
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

    org.mockito.InOrder order = inOrder(requestMapper, waitingMapper);
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
