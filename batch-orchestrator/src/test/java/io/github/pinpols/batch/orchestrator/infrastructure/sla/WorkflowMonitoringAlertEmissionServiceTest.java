package io.github.pinpols.batch.orchestrator.infrastructure.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.service.governance.AlertEventService;
import io.github.pinpols.batch.orchestrator.controller.request.AlertEmitRequest;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowMonitoringQuery;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate.Violation;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowMonitoringMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Workflow 时限告警发送前复核、独立身份与失败传播")
class WorkflowMonitoringAlertEmissionServiceTest {
  @Mock
  private WorkflowMonitoringMapper mapper;

  @Mock
  private AlertEventService alertEventService;

  private WorkflowMonitoringAlertEmissionService service;

  @BeforeEach
  void setUp() {
    service = new WorkflowMonitoringAlertEmissionService(mapper, alertEventService);
  }

  @ParameterizedTest
  @CsvSource({
    "NOT_STARTED,WORKFLOW_NOT_STARTED_BY_DEADLINE",
    "NOT_COMPLETED_BY_DEADLINE,WORKFLOW_NOT_COMPLETED_BY_DEADLINE",
    "RUNNING_TOO_LONG,WORKFLOW_RUNNING_TOO_LONG"
  })
  @DisplayName("每种根级违约使用独立事件编码，保留真实资源与上下文")
  void shouldEmitRootAlertAfterRechecking(Violation violation, String alertType) {
    WorkflowMonitoringAlertCandidate candidate = candidate(violation);
    when(mapper.selectCandidates(any())).thenReturn(List.of(candidate));

    assertThat(service.emitOnce(candidate)).isTrue();

    ArgumentCaptor<AlertEmitRequest> requestCaptor =
        ArgumentCaptor.forClass(AlertEmitRequest.class);
    verify(alertEventService).emit(requestCaptor.capture());
    AlertEmitRequest request = requestCaptor.getValue();
    assertThat(request.alertType()).isEqualTo(alertType);
    assertThat(request.tenantId()).isEqualTo("tenant-a");
    assertThat(request.resourceKey()).isEqualTo("workflow_run:21");
    assertThat(request.detailJson())
        .contains("\"workflowRunId\":\"21\"")
        .contains("\"monitoringResourceKey\":\"workflow_run:21\"");
    verify(mapper).acquireAlertLock("tenant-a", "workflow_run:21", violation.name());
    ArgumentCaptor<WorkflowMonitoringQuery> queryCaptor =
        ArgumentCaptor.forClass(WorkflowMonitoringQuery.class);
    verify(mapper).selectCandidates(queryCaptor.capture());
    assertThat(queryCaptor.getValue().resourceKey()).isEqualTo("workflow_run:21");
    assertThat(queryCaptor.getValue().violation()).isEqualTo(violation);
    assertThat(queryCaptor.getValue().limit()).isOne();
  }

  @Test
  @DisplayName("扫描后策略关闭或已有告警时复核为空，不再次发送")
  void shouldSkipCandidateNoLongerEligible() {
    WorkflowMonitoringAlertCandidate candidate = candidate(Violation.NOT_STARTED);
    when(mapper.selectCandidates(any())).thenReturn(List.of());

    assertThat(service.emitOnce(candidate)).isFalse();

    verify(alertEventService, never()).emit(any());
  }

  @Test
  @DisplayName("告警持久化错误传播给独立事务，允许回滚并由下轮重试")
  void shouldPropagatePersistenceFailureForRollback() {
    WorkflowMonitoringAlertCandidate candidate = candidate(Violation.RUNNING_TOO_LONG);
    when(mapper.selectCandidates(any())).thenReturn(List.of(candidate));
    doThrow(new IllegalStateException("injected persistence failure"))
        .when(alertEventService)
        .emit(any());

    assertThatThrownBy(() -> service.emitOnce(candidate)).isInstanceOf(IllegalStateException.class);
  }

  private WorkflowMonitoringAlertCandidate candidate(Violation violation) {
    return WorkflowMonitoringAlertCandidate.builder()
        .tenantId("tenant-a")
        .workflowDefinitionId(11L)
        .workflowCode("WORKFLOW")
        .workflowRunId(21L)
        .resourceKey("workflow_run:21")
        .severity("ERROR")
        .startedAt(Instant.parse("2026-01-03T00:00:00Z"))
        .violation(violation)
        .build();
  }
}
