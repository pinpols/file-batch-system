package io.github.pinpols.batch.orchestrator.application.service.batchday;

import io.github.pinpols.batch.orchestrator.domain.param.ExpectedWorkOccurrenceInsertIfAbsentParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ExpectedWorkOccurrenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowDefinitionMapper;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("逐日文件预期登记校验租户作业日历，并以稳定文件组身份幂等写入")
class ExpectedWorkRegistrationServiceTest {

  private ExpectedWorkOccurrenceMapper occurrenceMapper;
  private WorkflowDefinitionMapper workflowDefinitionMapper;
  private BatchDayInstanceMapper batchDayInstanceMapper;
  private ExpectedWorkRegistrationService service;

  @BeforeEach
  void setUp() {
    occurrenceMapper = mock(ExpectedWorkOccurrenceMapper.class);
    workflowDefinitionMapper = mock(WorkflowDefinitionMapper.class);
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 91L, true))
        .thenReturn(new WorkflowDefinitionEntity(
            91L, "tenant-a", "WF_IMPORT", "Import", "DAG", 1, true, 11L, "CAL_A"));
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
    service = new ExpectedWorkRegistrationService(
        occurrenceMapper, batchDayInstanceMapper, workflowDefinitionMapper, transactionManager);
  }

  @DisplayName("日历匹配作业时幂等登记文件组预期")
  @Test
  void shouldRegisterExpectedGroupIdempotentlyWhenCalendarMatchesJob() {
    when(occurrenceMapper.insertIfAbsent(org.mockito.ArgumentMatchers.any(ExpectedWorkOccurrenceInsertIfAbsentParam.class)))
        .thenReturn(1);

    var result = service.register(request());

    assertThat(result.inserted()).isTrue();
    assertThat(result.fileGroupCode()).isEqualTo("ORDER_DAILY");
    verify(occurrenceMapper)
        .insertIfAbsent(
            ExpectedWorkOccurrenceInsertIfAbsentParam.builder()
                .tenantId("tenant-a")
                .calendarCode("CAL_A")
                .bizDate(LocalDate.of(2026, 10, 14))
                .workflowDefinitionId(91L)
                .sourceType("FILE_GROUP")
                .sourceKey("ORDER_DAILY")
                .expectedAt(Instant.parse("2026-10-14T01:00:00Z"))
                .dueAt(Instant.parse("2026-10-14T03:00:00Z"))
                .build());
    verify(batchDayInstanceMapper)
        .acquireSettlementFence("tenant-a", "CAL_A", LocalDate.of(2026, 10, 14));
  }

  @DisplayName("拒绝与作业日历不匹配的预期")
  @Test
  void shouldRejectExpectationForDifferentJobCalendar() {
    when(workflowDefinitionMapper.selectByTenantAndIdAndEnabled("tenant-a", 91L, true))
        .thenReturn(new WorkflowDefinitionEntity(
            91L, "tenant-a", "WF_IMPORT", "Import", "DAG", 1, true, 11L, "OTHER_CALENDAR"));

    assertThatThrownBy(() -> service.register(request()))
        .isInstanceOf(ResponseStatusException.class);
  }

  @DisplayName("拒绝早于预期时间的截止时间")
  @Test
  void shouldRejectDueTimeBeforeExpectedTime() {
    var invalid = new ExpectedWorkRegistrationService.FileGroupExpectation(
        "tenant-a",
        "CAL_A",
        91L,
        "ORDER_DAILY",
        LocalDate.of(2026, 10, 14),
        Instant.parse("2026-10-14T03:00:00Z"),
        Instant.parse("2026-10-14T01:00:00Z"));

    assertThatThrownBy(() -> service.register(invalid))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ExpectedWorkRegistrationService.FileGroupExpectation request() {
    return new ExpectedWorkRegistrationService.FileGroupExpectation(
        "tenant-a",
        "CAL_A",
        91L,
        "ORDER_DAILY",
        LocalDate.of(2026, 10, 14),
        Instant.parse("2026-10-14T01:00:00Z"),
        Instant.parse("2026-10-14T03:00:00Z"));
  }
}
