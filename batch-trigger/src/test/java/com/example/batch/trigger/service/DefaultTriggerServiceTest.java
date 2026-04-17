package com.example.batch.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.TriggerRequestEntity;
import com.example.batch.trigger.domain.OrchestratorTriggerAdapter;
import com.example.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.mapper.BusinessCalendarMapper;
import com.example.batch.trigger.mapper.TenantStatusMapper;
import com.example.batch.trigger.mapper.TriggerRequestMapper;
import com.example.batch.trigger.support.TriggerDescriptor;
import com.example.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class DefaultTriggerServiceTest {

  @Mock private LaunchAdapterService launchAdapterService;
  @Mock private OrchestratorTriggerAdapter orchestratorTriggerAdapter;
  @Mock private TriggerRequestMapper triggerRequestMapper;
  @Mock private BusinessCalendarMapper businessCalendarMapper;
  @Mock private TenantStatusMapper tenantStatusMapper;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private TransactionStatus transactionStatus;

  private DefaultTriggerService service;

  @BeforeEach
  void setUp() {
    lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    lenient().when(tenantStatusMapper.selectStatus(any())).thenReturn("ACTIVE");
    service =
        new DefaultTriggerService(
            launchAdapterService,
            orchestratorTriggerAdapter,
            triggerRequestMapper,
            businessCalendarMapper,
            tenantStatusMapper,
            transactionManager);
  }

  @Test
  void shouldRejectBlankIdempotencyKey() {
    assertThatThrownBy(
            () ->
                service.launch(
                    new TriggerLaunchCommand(validRequest(), " ", "req-001", "trace-001")))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.MISSING_IDEMPOTENCY_KEY);
  }

  @Test
  void shouldShortCircuitWhenDedupRequestAlreadyExists() {
    TriggerLaunchCommand command =
        new TriggerLaunchCommand(validRequest(), "idem-001", "req-001", "trace-001");
    LaunchRequest launchRequest =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 27),
            TriggerType.API,
            "req-001",
            "trace-001",
            Map.of());
    TriggerRequestEntity existing = new TriggerRequestEntity();
    existing.setTenantId("t1");
    existing.setRequestId("existing-request");
    existing.setTraceId("existing-trace");

    when(launchAdapterService.fromApiRequest(command)).thenReturn(launchRequest);
    when(triggerRequestMapper.selectByTenantAndDedupKey("t1", "idem-001")).thenReturn(existing);

    LaunchResponse response = service.launch(command);

    assertThat(response).isNotNull();
    assertThat(response.traceId()).isEqualTo("existing-trace");
    verify(triggerRequestMapper, never()).insert(any());
    verify(orchestratorTriggerAdapter, never()).sendTrigger(any());
  }

  @Test
  void shouldMarkRequestForwardFailedWhenForwardingFails() {
    TriggerLaunchCommand command =
        new TriggerLaunchCommand(validRequest(), "idem-002", "req-002", "trace-002");
    LaunchRequest launchRequest =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 27),
            TriggerType.API,
            "req-002",
            "trace-002",
            Map.of("channel", "api"));

    when(launchAdapterService.fromApiRequest(command)).thenReturn(launchRequest);
    when(triggerRequestMapper.selectByTenantAndDedupKey("t1", "idem-002")).thenReturn(null);
    when(orchestratorTriggerAdapter.sendTrigger(launchRequest))
        .thenThrow(new IllegalStateException("orchestrator down"));

    // 5.7: transient failures now return a response instead of throwing
    LaunchResponse response = service.launch(command);
    assertThat(response.instanceNo()).isEqualTo("req-002");
    assertThat(response.traceId()).isEqualTo("trace-002");

    ArgumentCaptor<TriggerRequestEntity> captor =
        ArgumentCaptor.forClass(TriggerRequestEntity.class);
    verify(triggerRequestMapper).insert(captor.capture());
    assertThat(captor.getValue().getRequestStatus()).isEqualTo("PENDING");
    assertThat(captor.getValue().getDedupKey()).isEqualTo("idem-002");
    // 5.7: status is FORWARD_FAILED (retryable) instead of REJECTED
    verify(triggerRequestMapper).updateRequestStatus("t1", "req-002", "FORWARD_FAILED");
  }

  @Test
  void shouldApprovePendingCatchUpAndMarkRequestLaunched() {
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setRequestId("req-pending");
    command.setReason("manual approve");

    TriggerRequestEntity pending = new TriggerRequestEntity();
    pending.setTenantId("t1");
    pending.setRequestId("req-pending");
    pending.setJobCode("EXPORT_JOB");
    pending.setBizDate(LocalDate.of(2026, 3, 27));
    pending.setTriggerType(TriggerType.CATCH_UP.code());
    pending.setRequestStatus("ACCEPTED");
    pending.setTraceId("trace-pending");

    LaunchResponse response = new LaunchResponse("inst-001", "trace-pending");
    when(triggerRequestMapper.selectByTenantAndRequestId("t1", "req-pending")).thenReturn(pending);
    when(triggerRequestMapper.updateRequestStatusConditional(
            "t1", "req-pending", "PROCESSING", "ACCEPTED"))
        .thenReturn(1);
    when(orchestratorTriggerAdapter.sendTrigger(any())).thenReturn(response);

    LaunchResponse approved = service.approvePendingCatchUp(command);

    assertThat(approved.instanceNo()).isEqualTo("inst-001");
    ArgumentCaptor<LaunchRequest> captor = ArgumentCaptor.forClass(LaunchRequest.class);
    verify(orchestratorTriggerAdapter).sendTrigger(captor.capture());
    assertThat(captor.getValue().triggerType()).isEqualTo(TriggerType.CATCH_UP);
    assertThat(captor.getValue().params())
        .containsEntry("operationType", "CATCH_UP_APPROVAL")
        .containsEntry("approvalMode", "MANUAL_APPROVAL")
        .containsEntry("catchUpApproved", true)
        .containsEntry("reason", "manual approve");
    verify(triggerRequestMapper).updateRequestStatus("t1", "req-pending", "LAUNCHED");
  }

  @Test
  void shouldRejectApprovalForNonCatchUpRequest() {
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setRequestId("req-invalid");

    TriggerRequestEntity request = new TriggerRequestEntity();
    request.setTriggerType(TriggerType.API.code());

    when(triggerRequestMapper.selectByTenantAndRequestId("t1", "req-invalid")).thenReturn(request);

    assertThatThrownBy(() -> service.approvePendingCatchUp(command))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("not a catch-up request");

    verify(orchestratorTriggerAdapter, never()).sendTrigger(any());
  }

  @Test
  void shouldSkipScheduledTriggerWhenBizDateResolutionReturnsNull() {
    ScheduledTriggerCommand command =
        new ScheduledTriggerCommand(
            scheduledDescriptor(),
            Instant.parse("2026-03-28T18:00:00Z"),
            TriggerType.SCHEDULED,
            "req-skip",
            "trace-skip");
    LaunchRequest launchRequest =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            null,
            TriggerType.SCHEDULED,
            "req-skip",
            "trace-skip",
            Map.of("calendarCode", "BIZ_CAL"));

    when(launchAdapterService.fromScheduledTrigger(eq(command), any())).thenReturn(launchRequest);

    LaunchResponse response = service.launchScheduled(command);

    assertThat(response.instanceNo()).isNull();
    assertThat(response.traceId()).isEqualTo("trace-skip");
    verify(triggerRequestMapper, never()).insert(any());
    verify(orchestratorTriggerAdapter, never()).sendTrigger(any());
  }

  @Test
  void launch_suspendedTenant_throwsBizException() {
    when(tenantStatusMapper.selectStatus("t1")).thenReturn("SUSPENDED");

    assertThatThrownBy(
            () ->
                service.launch(
                    new TriggerLaunchCommand(
                        validRequest(), "idem-susp", "req-susp", "trace-susp")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("suspended");

    verify(triggerRequestMapper, never()).insert(any());
    verify(orchestratorTriggerAdapter, never()).sendTrigger(any());
  }

  @Test
  void launchScheduled_suspendedTenant_throwsBizException() {
    when(tenantStatusMapper.selectStatus("t1")).thenReturn("SUSPENDED");

    ScheduledTriggerCommand command =
        new ScheduledTriggerCommand(
            scheduledDescriptor(),
            Instant.parse("2026-03-28T18:00:00Z"),
            TriggerType.SCHEDULED,
            "req-susp",
            "trace-susp");

    assertThatThrownBy(() -> service.launchScheduled(command))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("suspended");
  }

  private TriggerLaunchRequest validRequest() {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setJobCode("IMPORT_JOB");
    request.setBizDate(LocalDate.of(2026, 3, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of());
    return request;
  }

  private TriggerDescriptor scheduledDescriptor() {
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId("t1");
    descriptor.setJobCode("IMPORT_JOB");
    descriptor.setTimezone("Asia/Shanghai");
    descriptor.setCalendarCode("BIZ_CAL");
    return descriptor;
  }
}
