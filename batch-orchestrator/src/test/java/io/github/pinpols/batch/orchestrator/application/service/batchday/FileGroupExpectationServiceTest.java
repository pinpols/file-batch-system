package io.github.pinpols.batch.orchestrator.application.service.batchday;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.param.FileGroupExpectationDefinitionUpsertParam;
import io.github.pinpols.batch.orchestrator.domain.query.FileGroupExpectationDefinition;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.FileGroupExpectationDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;

@DisplayName("文件组预期按 Workflow 身份配置与物化")
@ExtendWith(MockitoExtension.class)
class FileGroupExpectationServiceTest {

  @Mock
  private FileGroupExpectationDefinitionMapper definitionMapper;

  @Mock
  private BatchDayInstanceMapper batchDayInstanceMapper;

  @Mock
  private ExpectedWorkOccurrenceMapper occurrenceMapper;

  @Mock
  private WorkflowDefinitionMapper workflowDefinitionMapper;

  @Mock
  private PlatformTransactionManager transactionManager;

  private FileGroupExpectationService service;

  @BeforeEach
  void setUp() {
    when(transactionManager.getTransaction(ArgumentMatchers.any()))
        .thenReturn(mock(TransactionStatus.class));
    service = new FileGroupExpectationService(
        definitionMapper,
        batchDayInstanceMapper,
        occurrenceMapper,
        workflowDefinitionMapper,
        transactionManager);
  }

  @DisplayName("按 Workflow 身份配置并物化预期")
  @Test
  void shouldConfiguresAndMaterializesByWorkflowIdentity() {
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 91L, true))
        .thenReturn(new WorkflowDefinitionView(
            91L, "tenant-a", "WF_IMPORT", "Import", "DAG", 1, true, 11L, "CAL_A"));
    var request = new FileGroupExpectationService.DefinitionRequest(
        "tenant-a", "CAL_A", 91L, "ORDER_DAILY", LocalTime.of(9, 0), LocalTime.of(10, 0), true);
    when(definitionMapper.upsert(FileGroupExpectationDefinitionUpsertParam.builder()
            .tenantId("tenant-a")
            .calendarCode("CAL_A")
            .workflowDefinitionId(91L)
            .fileGroupCode("ORDER_DAILY")
            .expectedLocalTime(LocalTime.of(9, 0))
            .dueLocalTime(LocalTime.of(10, 0))
            .enabled(true)
            .build()))
        .thenReturn(1);
    assertThat(service.configure(request)).isOne();

    when(definitionMapper.selectEnabledByCalendar("tenant-a", "CAL_A"))
        .thenReturn(List.of(new FileGroupExpectationDefinition(
            "tenant-a", "CAL_A", 91L, "ORDER_DAILY", LocalTime.of(9, 0), LocalTime.of(10, 0))));
    when(occurrenceMapper.insertIfAbsent(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.tenantId(), "tenant-a")
            && Objects.equals(parameters.calendarCode(), "CAL_A")
            && Objects.equals(parameters.bizDate(), LocalDate.of(2026, 10, 14))
            && Objects.equals(parameters.workflowDefinitionId(), 91L)
            && Objects.equals(parameters.sourceType(), "FILE_GROUP")
            && Objects.equals(parameters.sourceKey(), "ORDER_DAILY"))))
        .thenReturn(1);

    assertThat(service.materializeForOpenedBusinessDay(
            "tenant-a", "CAL_A", LocalDate.of(2026, 10, 14), "Asia/Shanghai"))
        .isOne();
    verify(occurrenceMapper)
        .insertIfAbsent(ArgumentMatchers.argThat(parameters -> parameters != null
            && Objects.equals(parameters.tenantId(), "tenant-a")
            && Objects.equals(parameters.calendarCode(), "CAL_A")
            && Objects.equals(parameters.bizDate(), LocalDate.of(2026, 10, 14))
            && Objects.equals(parameters.workflowDefinitionId(), 91L)
            && Objects.equals(parameters.sourceType(), "FILE_GROUP")
            && Objects.equals(parameters.sourceKey(), "ORDER_DAILY")));
  }

  @DisplayName("拒绝不属于请求日历的 Workflow")
  @Test
  void shouldRejectsWorkflowOutsideRequestedCalendar() {
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 91L, true))
        .thenReturn(new WorkflowDefinitionView(
            91L, "tenant-a", "WF_IMPORT", "Import", "DAG", 1, true, 11L, "CAL_A"));
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 91L, true))
        .thenReturn(new WorkflowDefinitionView(
            91L, "tenant-a", "WF_IMPORT", "Import", "DAG", 1, true, 11L, "OTHER_CAL"));

    assertThatThrownBy(() -> service.configure(new FileGroupExpectationService.DefinitionRequest(
            "tenant-a", "CAL_A", 91L, "ORDER_DAILY", LocalTime.of(9, 0), null, true)))
        .isInstanceOf(OperationFailure.class)
        .satisfies(error -> assertThat(((OperationFailure) error).kind())
            .isEqualTo(OperationFailure.Kind.INVALID_CONFIGURATION));
  }

  @DisplayName("删除预期定义只移除当前周期配置")
  @Test
  void shouldDeletesRecurringDefinitionByFullTenantIdentity() {
    when(definitionMapper.delete("tenant-a", "CAL_A", 91L, "ORDER_DAILY")).thenReturn(1);

    assertThat(service.deleteDefinition("tenant-a", "CAL_A", 91L, "ORDER_DAILY"))
        .isTrue();

    verify(occurrenceMapper).setTenantContext("tenant-a");
    verify(definitionMapper).delete("tenant-a", "CAL_A", 91L, "ORDER_DAILY");
  }
}
