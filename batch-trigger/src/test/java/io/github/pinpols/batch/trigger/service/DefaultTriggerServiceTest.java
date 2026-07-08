package io.github.pinpols.batch.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.event.TriggerOutboxDomainEventPublisher;
import io.github.pinpols.batch.trigger.infrastructure.readiness.UpstreamReadinessChecker;
import io.github.pinpols.batch.trigger.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.trigger.mapper.TenantStatusMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
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
  @Mock private TriggerMisfirePendingMapper triggerMisfirePendingMapper;
  @Mock private TriggerOutboxDomainEventPublisher triggerOutboxPublisher;
  @Mock private BusinessCalendarMapper businessCalendarMapper;
  @Mock private TenantStatusMapper tenantStatusMapper;
  @Mock private PlatformTransactionManager transactionManager;
  @Mock private TransactionStatus transactionStatus;
  @Mock private UpstreamReadinessChecker upstreamReadinessChecker;

  private DefaultTriggerService service;

  @BeforeEach
  void setUp() {
    lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    lenient().when(tenantStatusMapper.selectStatus(any())).thenReturn("ACTIVE");
    service =
        new DefaultTriggerService(
            launchAdapterService,
            triggerRequestMapper,
            triggerMisfirePendingMapper,
            triggerOutboxPublisher,
            businessCalendarMapper,
            tenantStatusMapper,
            transactionManager,
            upstreamReadinessChecker);
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
    verify(triggerRequestMapper)
        .updateRequestStatusConditional("t1", "req-pending", "LAUNCHED", "PROCESSING");
  }

  @Test
  void shouldApproveMisfirePendingByPendingIdAndLaunchLinkedRequest() {
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setPendingId(10L);
    command.setReason("manual approve");

    TriggerMisfirePendingEntity pendingRow = new TriggerMisfirePendingEntity();
    pendingRow.setId(10L);
    pendingRow.setTenantId("t1");
    pendingRow.setStatus("PENDING");
    pendingRow.setCatchUpRequestId(77L);

    TriggerRequestEntity pendingRequest = new TriggerRequestEntity();
    pendingRequest.setId(77L);
    pendingRequest.setTenantId("t1");
    pendingRequest.setRequestId("req-linked");
    pendingRequest.setJobCode("EXPORT_JOB");
    pendingRequest.setBizDate(LocalDate.of(2026, 3, 27));
    pendingRequest.setTriggerType(TriggerType.CATCH_UP.code());
    pendingRequest.setRequestStatus("ACCEPTED");
    pendingRequest.setTraceId("trace-linked");
    pendingRequest.setDedupKey("dedup-linked");

    when(triggerMisfirePendingMapper.selectById(10L)).thenReturn(pendingRow);
    when(triggerRequestMapper.selectById(77L)).thenReturn(pendingRequest);
    when(triggerRequestMapper.updateRequestStatusConditional(
            "t1", "req-linked", "PROCESSING", "ACCEPTED"))
        .thenReturn(1);

    LaunchResponse approved = service.approvePendingCatchUp(command);

    assertThat(approved.instanceNo()).isEqualTo("req-linked");
    assertThat(approved.traceId()).isEqualTo("trace-linked");
    verify(triggerMisfirePendingMapper).approve(10L, "trigger-api");
    verify(triggerOutboxPublisher)
        .publishRaw(eq("t1"), eq("req-linked"), eq("trace-linked"), anyString());
    verify(triggerRequestMapper)
        .updateRequestStatusConditional("t1", "req-linked", "LAUNCHED", "PROCESSING");
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
  void launchScheduled_upstreamNotReady_throwsUpstreamNotReadyException() {
    // ADR-043:声明了 dependsOn 且上游未就绪 → 不再返回 skipped 丢批,改抛 UpstreamNotReadyException,
    // 由 wheel 走 readiness defer。
    TriggerDescriptor descriptor = scheduledDescriptor();
    descriptor.setDependsOnJobCode("UPSTREAM_SETTLE");
    ScheduledTriggerCommand command =
        new ScheduledTriggerCommand(
            descriptor,
            Instant.parse("2026-03-28T18:00:00Z"),
            TriggerType.SCHEDULED,
            "req-nr",
            "trace-nr");
    LaunchRequest launchRequest =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 28),
            TriggerType.SCHEDULED,
            "req-nr",
            "trace-nr",
            Map.of("calendarCode", "BIZ_CAL"));

    when(launchAdapterService.fromScheduledTrigger(eq(command), any())).thenReturn(launchRequest);
    when(upstreamReadinessChecker.isReady("t1", "UPSTREAM_SETTLE", LocalDate.of(2026, 3, 28)))
        .thenReturn(false);

    assertThatThrownBy(() -> service.launchScheduled(command))
        .isInstanceOf(UpstreamNotReadyException.class);

    // 既不落 trigger_request 也不发 outbox(未 fire)
    verify(triggerRequestMapper, never()).insert(any());
    verify(triggerOutboxPublisher, never())
        .publishRaw(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void launchScheduled_upstreamReady_proceedsToForward() {
    TriggerDescriptor descriptor = scheduledDescriptor();
    descriptor.setDependsOnJobCode("UPSTREAM_SETTLE");
    ScheduledTriggerCommand command =
        new ScheduledTriggerCommand(
            descriptor,
            Instant.parse("2026-03-28T18:00:00Z"),
            TriggerType.SCHEDULED,
            "req-ok",
            "trace-ok");
    LaunchRequest launchRequest =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 28),
            TriggerType.SCHEDULED,
            "req-ok",
            "trace-ok",
            Map.of("calendarCode", "BIZ_CAL"));

    when(launchAdapterService.fromScheduledTrigger(eq(command), any())).thenReturn(launchRequest);
    when(upstreamReadinessChecker.isReady("t1", "UPSTREAM_SETTLE", LocalDate.of(2026, 3, 28)))
        .thenReturn(true);

    LaunchResponse response = service.launchScheduled(command);

    // 就绪 → 正常 persistAndForward(落 trigger_request)
    assertThat(response).isNotNull();
    verify(triggerRequestMapper).insert(any());
  }

  @Test
  void createPendingCatchUpShouldLinkMisfirePendingToCatchUpRequest() {
    ScheduledTriggerCommand command =
        new ScheduledTriggerCommand(
            scheduledDescriptor(),
            Instant.parse("2026-03-28T18:00:00Z"),
            TriggerType.CATCH_UP,
            "req-cu",
            "trace-cu",
            500L);
    LaunchRequest launchRequest =
        new LaunchRequest(
            "t1",
            "IMPORT_JOB",
            LocalDate.of(2026, 3, 28),
            TriggerType.CATCH_UP,
            "req-cu",
            "trace-cu",
            Map.of("catchUp", true));
    doAnswer(
            invocation -> {
              TriggerRequestEntity entity = invocation.getArgument(0);
              entity.setId(900L);
              return 1;
            })
        .when(triggerRequestMapper)
        .insert(any());
    doAnswer(
            invocation -> {
              TriggerMisfirePendingEntity entity = invocation.getArgument(0);
              entity.setId(901L);
              return 1;
            })
        .when(triggerMisfirePendingMapper)
        .insertPending(any());
    when(launchAdapterService.fromScheduledTrigger(eq(command), any())).thenReturn(launchRequest);

    LaunchResponse response = service.createPendingCatchUp(command);

    assertThat(response.instanceNo()).isEqualTo("req-cu");
    verify(triggerRequestMapper).insert(any());
    verify(triggerMisfirePendingMapper).insertPending(any());
    verify(triggerMisfirePendingMapper).linkCatchUpRequest(901L, 900L);
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
