package com.example.batch.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.example.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import com.example.batch.trigger.domain.command.ScheduledTriggerCommand;
import com.example.batch.trigger.domain.command.TriggerLaunchCommand;
import com.example.batch.trigger.event.TriggerOutboxDomainEventPublisher;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class DefaultTriggerServiceTest {

  @Mock private LaunchAdapterService launchAdapterService;
  @Mock private TriggerRequestMapper triggerRequestMapper;
  @Mock private TriggerOutboxDomainEventPublisher triggerOutboxPublisher;
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
            triggerRequestMapper,
            triggerOutboxPublisher,
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
    verify(triggerOutboxPublisher, never())
        .publishRaw(anyString(), anyString(), anyString(), anyString());
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
    pending.setDedupKey("dedup-pending");

    when(triggerRequestMapper.selectByTenantAndRequestId("t1", "req-pending")).thenReturn(pending);
    when(triggerRequestMapper.updateRequestStatusConditional(
            "t1", "req-pending", "PROCESSING", "ACCEPTED"))
        .thenReturn(1);

    LaunchResponse approved = service.approvePendingCatchUp(command);

    // ADR-010：审批走 outbox，不再调 orchestrator HTTP；返回 trigger 侧的 requestId/traceId
    assertThat(approved.instanceNo()).isEqualTo("req-pending");
    assertThat(approved.traceId()).isEqualTo("trace-pending");
    // 同事务内：CAS PROCESSING → INSERT outbox → 更新 LAUNCHED
    verify(triggerOutboxPublisher)
        .publishRaw(eq("t1"), eq("req-pending"), eq("trace-pending"), anyString());
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
        .hasMessageContaining("not_catch_up");

    verify(triggerOutboxPublisher, never())
        .publishRaw(anyString(), anyString(), anyString(), anyString());
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
    verify(triggerOutboxPublisher, never())
        .publishRaw(anyString(), anyString(), anyString(), anyString());
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
        .extracting(e -> ((BizException) e).getMessageArgs())
        .satisfies(
            args -> assertThat(java.util.Arrays.toString((Object[]) args)).contains("suspended"));

    verify(triggerRequestMapper, never()).insert(any());
    verify(triggerOutboxPublisher, never())
        .publishRaw(anyString(), anyString(), anyString(), anyString());
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
        .extracting(e -> ((BizException) e).getMessageArgs())
        .satisfies(
            args -> assertThat(java.util.Arrays.toString((Object[]) args)).contains("suspended"));
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
