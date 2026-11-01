package io.github.pinpols.batch.orchestrator.application.service.batchday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceTransitionStatusByGenerationParam;
import io.github.pinpols.batch.orchestrator.domain.param.ExternalEventExpectationDefinitionUpsertParam;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExternalEventExpectationDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure.Kind;

@DisplayName("外部事件预期满足：请求状态和事件身份必须精确关联")
@ExtendWith(MockitoExtension.class)
class ExternalEventExpectationServiceTest {

  @Mock
  private ExternalEventExpectationDefinitionMapper definitionMapper;

  @Mock
  private ExpectedWorkOccurrenceMapper occurrenceMapper;

  @Mock
  private BatchDayInstanceMapper batchDayInstanceMapper;

  @Mock
  private TriggerRequestMapper triggerRequestMapper;

  @Mock
  private WorkflowDefinitionMapper workflowDefinitionMapper;

  @Mock
  private PlatformTransactionManager transactionManager;

  @Mock
  private TransactionStatus transactionStatus;

  private ExternalEventExpectationService service;

  @BeforeEach
  void setUp() {
    when(transactionManager.getTransaction(ArgumentMatchers.any())).thenReturn(transactionStatus);
    service = new ExternalEventExpectationService(
        definitionMapper,
        batchDayInstanceMapper,
        occurrenceMapper,
        triggerRequestMapper,
        workflowDefinitionMapper,
        transactionManager);
  }

  @Test
  @DisplayName("只允许 LAUNCHED 且 eventCode 完全匹配的 EVENT 请求结清预期项")
  void shouldMarksOnlyMatchingLaunchedEventSatisfied() {
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 99L, true))
        .thenReturn(new WorkflowDefinitionView(
            99L, "tenant-a", "IMPORT", "Import", "DAG", 1, true, 11L, "CALENDAR_A"));
    TriggerRequestEntity request = request("LAUNCHED", "PAYMENT_CLOSED");
    when(triggerRequestMapper.selectByTenantAndRequestId("tenant-a", "request-a"))
        .thenReturn(request);
    when(occurrenceMapper.transitionStatus(
            ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
                .tenantId("tenant-a")
                .calendarCode("CALENDAR_A")
                .workflowDefinitionId(99L)
                .sourceType("EXTERNAL_EVENT")
                .sourceKey("PAYMENT_CLOSED")
                .bizDate(LocalDate.of(2026, 10, 14))
                .generation(null)
                .expectedStatus("EXPECTED")
                .newStatus("SATISFIED")
                .triggerRequestId("request-a")
                .build()))
        .thenReturn(1);

    assertThat(service.markSatisfied(satisfaction("PAYMENT_CLOSED"))).isTrue();

    verify(occurrenceMapper)
        .transitionStatus(ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
            .tenantId("tenant-a")
            .calendarCode("CALENDAR_A")
            .workflowDefinitionId(99L)
            .sourceType("EXTERNAL_EVENT")
            .sourceKey("PAYMENT_CLOSED")
            .bizDate(LocalDate.of(2026, 10, 14))
            .generation(null)
            .expectedStatus("EXPECTED")
            .newStatus("SATISFIED")
            .triggerRequestId("request-a")
            .build());
    verify(batchDayInstanceMapper)
        .acquireSettlementFence("tenant-a", "CALENDAR_A", LocalDate.of(2026, 10, 14));
  }

  @Test
  @DisplayName("定义停用后仍允许对应的已启动 EVENT 请求结清既有预期")
  void shouldMarkExistingExpectationSatisfiedAfterWorkflowIsDisabled() {
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 99L, true))
        .thenReturn(new WorkflowDefinitionView(
            99L, "tenant-a", "IMPORT", "Import", "DAG", 1, true, 11L, "CALENDAR_A"));
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 99L, true))
        .thenReturn(null);
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 99L, false))
        .thenReturn(new WorkflowDefinitionView(
            99L, "tenant-a", "IMPORT", "Import", "DAG", 1, false, 11L, "CALENDAR_A"));
    when(triggerRequestMapper.selectByTenantAndRequestId("tenant-a", "request-a"))
        .thenReturn(request("LAUNCHED", "PAYMENT_CLOSED"));
    when(occurrenceMapper.transitionStatus(
            ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
                .tenantId("tenant-a")
                .calendarCode("CALENDAR_A")
                .workflowDefinitionId(99L)
                .sourceType("EXTERNAL_EVENT")
                .sourceKey("PAYMENT_CLOSED")
                .bizDate(LocalDate.of(2026, 10, 14))
                .generation(null)
                .expectedStatus("EXPECTED")
                .newStatus("SATISFIED")
                .triggerRequestId("request-a")
                .build()))
        .thenReturn(1);

    assertThat(service.markSatisfied(satisfaction("PAYMENT_CLOSED"))).isTrue();

    verify(workflowDefinitionMapper).selectByTenantAndIdAndEnabled("tenant-a", 99L, false);
    verify(occurrenceMapper)
        .transitionStatus(ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
            .tenantId("tenant-a")
            .calendarCode("CALENDAR_A")
            .workflowDefinitionId(99L)
            .sourceType("EXTERNAL_EVENT")
            .sourceKey("PAYMENT_CLOSED")
            .bizDate(LocalDate.of(2026, 10, 14))
            .generation(null)
            .expectedStatus("EXPECTED")
            .newStatus("SATISFIED")
            .triggerRequestId("request-a")
            .build());
  }

  @Test
  @DisplayName("停用 Workflow 的不匹配 EVENT 请求不能结清既有预期")
  void shouldRejectMismatchedRequestBeforeAcceptingDisabledWorkflow() {
    when(triggerRequestMapper.selectByTenantAndRequestId("tenant-a", "request-a"))
        .thenReturn(request("LAUNCHED", "ACCOUNT_CLOSED"));

    assertThatThrownBy(() -> service.markSatisfied(satisfaction("PAYMENT_CLOSED")))
        .isInstanceOf(OperationFailure.class)
        .extracting(error -> ((OperationFailure) error).kind())
        .isEqualTo(Kind.CONFLICT);

    verify(workflowDefinitionMapper, Mockito.never())
        .selectByTenantAndIdAndEnabled("tenant-a", 99L, false);
    verify(occurrenceMapper, Mockito.never())
        .transitionStatus(ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
            .tenantId("tenant-a")
            .calendarCode("CALENDAR_A")
            .workflowDefinitionId(99L)
            .sourceType("EXTERNAL_EVENT")
            .sourceKey("PAYMENT_CLOSED")
            .bizDate(LocalDate.of(2026, 10, 14))
            .generation(null)
            .expectedStatus("EXPECTED")
            .newStatus("SATISFIED")
            .triggerRequestId("request-a")
            .build());
  }

  @Test
  @DisplayName("新建外部事件预期仍要求 Workflow 处于启用状态")
  void shouldRejectExpectationConfigurationForDisabledWorkflow() {
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 99L, true))
        .thenReturn(new WorkflowDefinitionView(
            99L, "tenant-a", "IMPORT", "Import", "DAG", 1, true, 11L, "CALENDAR_A"));
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 99L, true))
        .thenReturn(null);

    assertThatThrownBy(
            () -> service.configure(new ExternalEventExpectationService.DefinitionRequest(
                "tenant-a",
                "CALENDAR_A",
                99L,
                "PAYMENT_CLOSED",
                LocalTime.of(9, 0),
                LocalTime.of(10, 0),
                true)))
        .isInstanceOf(OperationFailure.class)
        .extracting(error -> ((OperationFailure) error).kind())
        .isEqualTo(Kind.INVALID_CONFIGURATION);

    Mockito.verify(definitionMapper, Mockito.never())
        .upsert(ExternalEventExpectationDefinitionUpsertParam.builder()
            .tenantId("tenant-a")
            .calendarCode("CALENDAR_A")
            .workflowDefinitionId(99L)
            .eventCode("PAYMENT_CLOSED")
            .expectedLocalTime(LocalTime.of(9, 0))
            .dueLocalTime(LocalTime.of(10, 0))
            .enabled(true)
            .build());
  }

  @Test
  @DisplayName("不同 eventCode 即使作业和业务日相同也不能复用已启动请求")
  void shouldRejectsMismatchedEventIdentity() {
    when(triggerRequestMapper.selectByTenantAndRequestId("tenant-a", "request-a"))
        .thenReturn(request("LAUNCHED", "PAYMENT_CLOSED"));

    assertThatThrownBy(() -> service.markSatisfied(satisfaction("ACCOUNT_CLOSED")))
        .isInstanceOf(OperationFailure.class)
        .extracting(error -> ((OperationFailure) error).kind())
        .isEqualTo(Kind.CONFLICT);

    verify(occurrenceMapper, Mockito.never())
        .transitionStatus(ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
            .tenantId("tenant-a")
            .calendarCode("CALENDAR_A")
            .workflowDefinitionId(99L)
            .sourceType("EXTERNAL_EVENT")
            .sourceKey("ACCOUNT_CLOSED")
            .bizDate(LocalDate.of(2026, 10, 14))
            .generation(null)
            .expectedStatus("EXPECTED")
            .newStatus("SATISFIED")
            .triggerRequestId("request-a")
            .build());
  }

  @Test
  @DisplayName("已启动请求不能结清另一个 Workflow 的同名事件预期")
  void shouldRejectsRequestForDifferentWorkflow() {
    when(triggerRequestMapper.selectByTenantAndRequestId("tenant-a", "request-a"))
        .thenReturn(request("LAUNCHED", "PAYMENT_CLOSED"));

    assertThatThrownBy(
            () -> service.markSatisfied(new ExternalEventExpectationService.SatisfactionRequest(
                "tenant-a",
                "CALENDAR_A",
                100L,
                "PAYMENT_CLOSED",
                LocalDate.of(2026, 10, 14),
                "request-a")))
        .isInstanceOf(OperationFailure.class)
        .extracting(error -> ((OperationFailure) error).kind())
        .isEqualTo(Kind.CONFLICT);

    verify(occurrenceMapper, Mockito.never())
        .transitionStatus(ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
            .tenantId("tenant-a")
            .calendarCode("CALENDAR_A")
            .workflowDefinitionId(99L)
            .sourceType("EXTERNAL_EVENT")
            .sourceKey("PAYMENT_CLOSED")
            .bizDate(LocalDate.of(2026, 10, 14))
            .generation(null)
            .expectedStatus("EXPECTED")
            .newStatus("SATISFIED")
            .triggerRequestId("request-a")
            .build());
  }

  @Test
  @DisplayName("ACCEPTED 请求不能提前结清事件预期")
  void shouldRejectsRequestBeforeLaunch() {
    when(triggerRequestMapper.selectByTenantAndRequestId("tenant-a", "request-a"))
        .thenReturn(request("ACCEPTED", "PAYMENT_CLOSED"));

    assertThatThrownBy(() -> service.markSatisfied(satisfaction("PAYMENT_CLOSED")))
        .isInstanceOf(OperationFailure.class)
        .extracting(error -> ((OperationFailure) error).kind())
        .isEqualTo(Kind.CONFLICT);

    verify(occurrenceMapper, Mockito.never())
        .transitionStatus(ExpectedWorkOccurrenceTransitionStatusByGenerationParam.builder()
            .tenantId("tenant-a")
            .calendarCode("CALENDAR_A")
            .workflowDefinitionId(99L)
            .sourceType("EXTERNAL_EVENT")
            .sourceKey("PAYMENT_CLOSED")
            .bizDate(LocalDate.of(2026, 10, 14))
            .generation(null)
            .expectedStatus("EXPECTED")
            .newStatus("SATISFIED")
            .triggerRequestId("request-a")
            .build());
  }

  @Test
  @DisplayName("删除预期定义不删除已经物化的业务日记录")
  void shouldDeletesRecurringDefinitionByFullTenantIdentity() {
    when(definitionMapper.delete("tenant-a", "CALENDAR_A", 99L, "PAYMENT_CLOSED"))
        .thenReturn(1);

    assertThat(service.deleteDefinition("tenant-a", "CALENDAR_A", 99L, "PAYMENT_CLOSED"))
        .isTrue();

    Mockito.verify(occurrenceMapper).setTenantContext("tenant-a");
    Mockito.verify(definitionMapper).delete("tenant-a", "CALENDAR_A", 99L, "PAYMENT_CLOSED");
  }

  private TriggerRequestEntity request(String status, String eventCode) {
    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTenantId("tenant-a");
    request.setWorkflowDefinitionId(99L);
    request.setBizDate(LocalDate.of(2026, 10, 14));
    request.setTriggerType("EVENT");
    request.setRequestStatus(status);
    request.setExternalEventCode(eventCode);
    return request;
  }

  private ExternalEventExpectationService.SatisfactionRequest satisfaction(String eventCode) {
    return new ExternalEventExpectationService.SatisfactionRequest(
        "tenant-a", "CALENDAR_A", 99L, eventCode, LocalDate.of(2026, 10, 14), "request-a");
  }
}
