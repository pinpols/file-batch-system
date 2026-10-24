package io.github.pinpols.batch.trigger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.trigger.application.UpstreamReadinessPort;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.TriggerLaunchStatus;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.domain.entity.ExternalEventDeliveryEntity;
import io.github.pinpols.batch.trigger.event.TriggerOutboxDomainEventPublisher;
import io.github.pinpols.batch.trigger.mapper.ExternalEventDeliveryMapper;
import io.github.pinpols.batch.trigger.mapper.TenantStatusMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.github.pinpols.batch.trigger.web.request.ExternalEventLaunchRequest;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
@DisplayName("Trigger 服务:幂等校验、人工补跑审批、定时触发的业务日历与上游就绪闸、租户停用拦截")
class DefaultTriggerServiceTest {

  @Mock
  private LaunchAdapterService launchAdapterService;

  @Mock
  private TriggerRequestMapper triggerRequestMapper;

  @Mock
  private ExternalEventDeliveryMapper externalEventDeliveryMapper;

  @Mock
  private TriggerMisfirePendingMapper triggerMisfirePendingMapper;

  @Mock
  private TriggerOutboxDomainEventPublisher triggerOutboxPublisher;

  @Mock
  private TriggerCalendarDefinitionProvider calendarDefinitionProvider;

  @Mock
  private ScheduledOccurrenceRecorder occurrenceRecorder;

  @Mock
  private TenantStatusMapper tenantStatusMapper;

  @Mock
  private PlatformTransactionManager transactionManager;

  @Mock
  private TransactionStatus transactionStatus;

  @Mock
  private UpstreamReadinessPort upstreamReadiness;

  @Mock
  private TriggerDefinitionLoader triggerDefinitionLoader;

  private DefaultTriggerService service;

  @BeforeEach
  void setUp() {
    lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    lenient().when(tenantStatusMapper.selectStatus(any())).thenReturn("ACTIVE");
    lenient()
        .when(triggerRequestMapper.existsEnabledWorkflowDefinition(any(), any()))
        .thenReturn(true);
    lenient()
        .when(triggerRequestMapper.lockScheduledWorkflowAdmission(any(), any(), any(), any()))
        .thenReturn(true);
    TriggerRuntimeProperties runtimeProperties = new TriggerRuntimeProperties();
    service = new DefaultTriggerService(
        launchAdapterService,
        triggerRequestMapper,
        externalEventDeliveryMapper,
        triggerMisfirePendingMapper,
        triggerOutboxPublisher,
        calendarDefinitionProvider,
        occurrenceRecorder,
        tenantStatusMapper,
        transactionManager,
        upstreamReadiness,
        runtimeProperties,
        triggerDefinitionLoader);
  }

  @Test
  @DisplayName("手工触发缺少幂等键时以 MISSING_IDEMPOTENCY_KEY 直接拒绝")
  void shouldRejectBlankIdempotencyKey() {
    assertThatThrownBy(() ->
            service.launch(new TriggerLaunchCommand(validRequest(), " ", "req-001", "trace-001")))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.MISSING_IDEMPOTENCY_KEY);
  }

  @Test
  @DisplayName("顶层触发必须指向 Workflow 并拒绝 Job 根目标")
  void shouldRejectMissingOrJobLaunchTarget() {
    TriggerLaunchRequest missing = validRequest();
    missing.setWorkflowDefinitionId(null);
    assertThatThrownBy(() -> service.launch(
            new TriggerLaunchCommand(missing, "idem-missing-target", "req-1", "trace-1")))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getMessageKey())
                .isEqualTo("error.trigger.workflow_target_required"));

    TriggerLaunchRequest jobRoot = validRequest();
    jobRoot.setWorkflowDefinitionId(null);
    assertThatThrownBy(() -> service.launch(
            new TriggerLaunchCommand(jobRoot, "idem-job-target", "req-job", "trace-job")))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getMessageKey())
                .isEqualTo("error.trigger.workflow_target_required"));

    TriggerLaunchRequest forgedParent = validRequest();
    forgedParent.setParams(Map.of("_parentWorkflowRunId", 900L, "_parentNodeCode", "FORGED"));
    assertThatThrownBy(() -> service.launch(
            new TriggerLaunchCommand(forgedParent, "idem-parent", "req-parent", "trace-parent")))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getMessageKey())
                .isEqualTo("error.trigger.parent_context_forbidden"));

    verify(triggerRequestMapper, never()).insert(any());
    verify(triggerOutboxPublisher, never())
        .publishLaunch(anyString(), anyString(), anyString(), any(LaunchEnvelope.class));
  }

  @Test
  @DisplayName("Workflow ID 不存在、停用或属于其他租户时在写入请求前拒绝")
  void shouldRejectWorkflowOutsideTenantBeforePersisting() {
    TriggerLaunchRequest request = validRequest();
    when(triggerRequestMapper.existsEnabledWorkflowDefinition("t1", 71L)).thenReturn(false);

    assertThatThrownBy(() -> service.launch(
            new TriggerLaunchCommand(request, "idem-foreign-workflow", "req-foreign", "trace")))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));

    verify(triggerRequestMapper, never()).insert(any());
    verify(triggerOutboxPublisher, never())
        .publishLaunch(anyString(), anyString(), anyString(), any(LaunchEnvelope.class));
  }

  @Test
  @DisplayName("非 Quartz 补跑的数据区间起点不冒充触发事件时间")
  void shouldKeepManualCatchUpEventTimeSeparateFromDataInterval() {
    TriggerLaunchRequest input = validRequest();
    input.setTriggerType(TriggerType.CATCH_UP);
    TriggerLaunchCommand command =
        new TriggerLaunchCommand(input, "manual-cu", "manual-cu", "manual-trace");
    LaunchRequest request = LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(71L)
        .bizDate(input.getBizDate())
        .triggerType(TriggerType.CATCH_UP)
        .requestId("manual-cu")
        .traceId("manual-trace")
        .dataIntervalStart(Instant.parse("2026-03-27T00:00:00Z"))
        .dataIntervalEnd(Instant.parse("2026-03-28T00:00:00Z"))
        .params(Map.of("source", "manual"))
        .build();
    when(launchAdapterService.fromApiRequest(command)).thenReturn(request);
    Instant before = Instant.now();
    service.launch(command);
    ArgumentCaptor<LaunchEnvelope> envelope = forClass(LaunchEnvelope.class);
    verify(triggerOutboxPublisher)
        .publishLaunch(eq("t1"), eq("manual-cu"), eq("manual-trace"), envelope.capture());
    assertThat(envelope.getValue().sourceFireTime()).isBetween(before, Instant.now());
    assertThat(envelope.getValue().launchRequest().dataIntervalStart())
        .isEqualTo(request.dataIntervalStart());
  }

  @Test
  @DisplayName("按租户与幂等键查询触发状态,原样返回库中记录")
  void shouldFindLaunchStatusByTenantAndIdempotencyKey() {
    TriggerLaunchStatus expected = new TriggerLaunchStatus(
        "req-001", "trace-001", "ACCEPTED", null, null, Instant.parse("2026-08-26T15:00:00Z"));
    when(triggerRequestMapper.selectLaunchStatus("t1", "idem-001", null)).thenReturn(expected);

    assertThat(service.findLaunchStatus("t1", "idem-001")).isSameAs(expected);
    verify(triggerRequestMapper).selectLaunchStatus("t1", "idem-001", null);
  }

  @Test
  @DisplayName("幂等键命中已有请求时复用其 traceId 返回,不再落库也不发 outbox")
  void shouldShortCircuitWhenDedupRequestAlreadyExists() {
    TriggerLaunchCommand command =
        new TriggerLaunchCommand(validRequest(), "idem-001", "req-001", "trace-001");
    LaunchRequest launchRequest = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 27),
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
        .publishLaunch(anyString(), anyString(), anyString(), any(LaunchEnvelope.class));
  }

  @Test
  @DisplayName("外部 EVENT 编码随 trigger_request 持久化用于精确满足预期事件")
  void shouldPersistExternalEventCodeOnAcceptedRequest() {
    TriggerLaunchRequest request = validRequest();
    request.setTriggerType(TriggerType.EVENT);
    request.setExternalEventCode("PAYMENT_CLOSED");
    request.setExternalEventSource("billing");
    request.setExternalEventId("close-2026-01");
    TriggerLaunchCommand command =
        new TriggerLaunchCommand(request, "event-idem", "event-request", "event-trace");
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId("t1")
        .jobCode("IMPORT_JOB")
        .bizDate(request.getBizDate())
        .triggerType(TriggerType.EVENT)
        .requestId("event-request")
        .traceId("event-trace")
        .externalEventCode("PAYMENT_CLOSED")
        .externalEventSource("billing")
        .externalEventId("close-2026-01")
        .build();
    when(launchAdapterService.fromApiRequest(command)).thenReturn(launchRequest);
    when(triggerRequestMapper.insert(any())).thenReturn(1);

    service.launch(command);

    ArgumentCaptor<TriggerRequestEntity> captor = forClass(TriggerRequestEntity.class);
    verify(triggerRequestMapper).insert(captor.capture());
    assertThat(captor.getValue().getExternalEventCode()).isEqualTo("PAYMENT_CLOSED");
    assertThat(captor.getValue().getExternalEventSource()).isEqualTo("billing");
    assertThat(captor.getValue().getExternalEventId()).isEqualTo("close-2026-01");
    verify(externalEventDeliveryMapper).setTenantContext("t1");
  }

  @Test
  @DisplayName("外部事件接纳事务先设置强制 RLS 所需的租户数据库上下文")
  void shouldSetTenantContextBeforeExternalEventAdmissionQueries() {
    ExternalEventLaunchRequest request = new ExternalEventLaunchRequest();
    request.setTenantId("t1");
    request.setExternalEventCode("PAYMENT_SETTLED");
    request.setExternalEventId("event-rls");
    request.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    ExternalEventLaunchCommand command = new ExternalEventLaunchCommand(
        request, "billing", "idem-event-rls", "request-event-rls", "trace-event-rls");
    when(triggerRequestMapper.selectEnabledExternalEventWorkflowTargets("t1", "PAYMENT_SETTLED"))
        .thenReturn(List.of());

    assertThatThrownBy(() -> service.launchExternalEvent(command))
        .isInstanceOfSatisfying(
            BizException.class,
            exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.NOT_FOUND));

    verify(externalEventDeliveryMapper).setTenantContext("t1");
  }

  @Test
  @DisplayName("外部事件状态查询在同一租户事务上下文中读取回执和目标状态")
  void shouldSetTenantContextBeforeExternalEventStatusQueries() {
    ExternalEventDeliveryEntity delivery = new ExternalEventDeliveryEntity();
    delivery.setTenantId("t1");
    delivery.setEventSource("billing");
    delivery.setEventId("event-status-rls");
    delivery.setEventCode("PAYMENT_SETTLED");
    delivery.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    delivery.setRequestId("request-status-rls");
    delivery.setTraceId("trace-status-rls");
    delivery.setCreatedAt(Instant.parse("2026-03-27T00:00:00Z"));
    when(externalEventDeliveryMapper.selectByAlias("t1", "billing", "idem-status-rls"))
        .thenReturn(delivery);
    when(externalEventDeliveryMapper.selectTargetStatuses("t1", "billing", "event-status-rls"))
        .thenReturn(List.of());

    ExternalEventLaunchStatus status =
        service.findExternalEventStatus("t1", "billing", "idem-status-rls");

    assertThat(status.requestId()).isEqualTo("request-status-rls");
    assertThat(status.targets()).isEmpty();
    assertThat(status.updatedAt()).isEqualTo(delivery.getCreatedAt());
    verify(externalEventDeliveryMapper).setTenantContext("t1");
  }

  @Test
  @DisplayName("外部事件编码必须非空且仅允许用于 EVENT 触发")
  void shouldRejectInvalidExternalEventCode() {
    TriggerLaunchRequest request = validRequest();
    request.setExternalEventCode(" ");

    assertThatThrownBy(() -> service.launch(
            new TriggerLaunchCommand(request, "event-idem", "event-request", "event-trace")))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.INVALID_ARGUMENT);
  }

  @Test
  @DisplayName("审批补跑:CAS 从 ACCEPTED 抢占后经 outbox 发起,实际建 Run 前保留 PROCESSING")
  void shouldApprovePendingCatchUpAndMarkRequestLaunched() {
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setRequestId("req-pending");
    command.setReason("manual approve");

    TriggerRequestEntity pending = new TriggerRequestEntity();
    pending.setTenantId("t1");
    pending.setRequestId("req-pending");
    pending.setJobCode("EXPORT_JOB");
    pending.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    pending.setTriggerType(TriggerType.CATCH_UP.code());
    pending.setRequestStatus("ACCEPTED");
    pending.setTraceId("trace-pending");
    pending.setDedupKey("dedup-pending");
    LaunchRequest originalLaunchRequest = LaunchRequest.builder()
        .tenantId(pending.getTenantId())
        .jobCode(pending.getJobCode())
        .workflowDefinitionId(pending.getWorkflowDefinitionId())
        .bizDate(pending.getBizDate())
        .triggerType(TriggerType.CATCH_UP)
        .requestId(pending.getRequestId())
        .traceId(pending.getTraceId())
        .params(Map.of("source", "original"))
        .build();
    pending.setLaunchPayload(JsonUtils.toJson(originalLaunchRequest));

    when(triggerRequestMapper.selectByTenantAndRequestId("t1", "req-pending")).thenReturn(pending);
    when(triggerRequestMapper.updateRequestStatusConditional(
            "t1", "req-pending", "PROCESSING", "ACCEPTED"))
        .thenReturn(1);

    LaunchResponse approved = service.approvePendingCatchUp(command);

    // ADR-010：审批走 outbox，不再调 orchestrator HTTP；返回 trigger 侧的 requestId/traceId
    assertThat(approved.instanceNo()).isEqualTo("req-pending");
    assertThat(approved.traceId()).isEqualTo("trace-pending");
    // 同事务内：CAS PROCESSING → INSERT outbox；实际启动前不谎报 LAUNCHED
    verify(triggerOutboxPublisher)
        .publishLaunch(eq("t1"), eq("req-pending"), eq("trace-pending"), any(LaunchEnvelope.class));
    verify(triggerRequestMapper, Mockito.never())
        .updateRequestStatusConditional("t1", "req-pending", "LAUNCHED", "PROCESSING");
  }

  @ParameterizedTest
  @ValueSource(strings = {"CLAIMED", "APPROVED", "REJECTED"})
  @DisplayName("按 misfire 待办 id 审批时先标记待办通过,再驱动关联补跑请求走同一发布链路")
  void shouldApproveMisfirePendingByPendingIdAndLaunchLinkedRequest(String concurrentStatus) {
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
    pendingRequest.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    pendingRequest.setTriggerType(TriggerType.CATCH_UP.code());
    pendingRequest.setRequestStatus("ACCEPTED");
    pendingRequest.setTraceId("trace-linked");
    pendingRequest.setDedupKey("dedup-linked");
    LaunchRequest originalLaunchRequest = LaunchRequest.builder()
        .tenantId(pendingRequest.getTenantId())
        .jobCode(pendingRequest.getJobCode())
        .workflowDefinitionId(pendingRequest.getWorkflowDefinitionId())
        .bizDate(pendingRequest.getBizDate())
        .triggerType(TriggerType.CATCH_UP)
        .requestId(pendingRequest.getRequestId())
        .traceId(pendingRequest.getTraceId())
        .params(Map.of("source", "original"))
        .build();
    pendingRequest.setLaunchPayload(JsonUtils.toJson(originalLaunchRequest));

    when(triggerMisfirePendingMapper.selectById(10L)).thenReturn(pendingRow);
    when(triggerRequestMapper.selectById(77L)).thenReturn(pendingRequest);
    when(triggerRequestMapper.updateRequestStatusConditional(
            "t1", "req-linked", "PROCESSING", "ACCEPTED"))
        .thenReturn(1);

    when(triggerMisfirePendingMapper.approve(10L, "trigger-api")).thenAnswer(ignored -> {
      if ("CLAIMED".equals(concurrentStatus)) {
        return 1;
      }
      pendingRow.setStatus(concurrentStatus);
      return 0;
    });
    if ("REJECTED".equals(concurrentStatus)) {
      assertThatThrownBy(() -> service.approvePendingCatchUp(command))
          .isInstanceOf(BizException.class)
          .extracting("code")
          .isEqualTo(ResultCode.STATE_CONFLICT);
      verify(triggerOutboxPublisher, never()).publishLaunch(any(), any(), any(), any());
      return;
    }

    LaunchResponse approved = service.approvePendingCatchUp(command);

    assertThat(approved.instanceNo()).isEqualTo("req-linked");
    assertThat(approved.traceId()).isEqualTo("trace-linked");
    verify(triggerMisfirePendingMapper).approve(10L, "trigger-api");
    verify(triggerOutboxPublisher)
        .publishLaunch(eq("t1"), eq("req-linked"), eq("trace-linked"), any(LaunchEnvelope.class));
    verify(triggerRequestMapper, Mockito.never())
        .updateRequestStatusConditional("t1", "req-linked", "LAUNCHED", "PROCESSING");
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "malformed", "tenant", "date", "workflow", "type", "dryRun"})
  @DisplayName("待审批原始快照缺失、损坏或身份不符时失败关闭,不修改状态且不发消息")
  void shouldRejectInvalidPendingSnapshot(String invalidField) {
    TriggerRequestEntity pending = new TriggerRequestEntity();
    pending.setTenantId("t1");
    pending.setRequestId("snapshot-invalid");
    pending.setWorkflowDefinitionId(101L);
    pending.setBizDate(LocalDate.of(2026, 3, 27));
    pending.setTriggerType(TriggerType.CATCH_UP.code());
    pending.setRequestStatus("ACCEPTED");
    pending.setTraceId("snapshot-trace");
    LaunchRequest.LaunchRequestBuilder original = LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(101L)
        .bizDate(pending.getBizDate())
        .triggerType(TriggerType.CATCH_UP)
        .requestId(pending.getRequestId())
        .traceId(pending.getTraceId());
    switch (invalidField) {
      case "tenant" -> original.tenantId("other-tenant");
      case "date" -> original.bizDate(pending.getBizDate().plusDays(1));
      case "workflow" -> original.workflowDefinitionId(102L);
      case "type" -> original.triggerType(TriggerType.API);
      case "dryRun" -> original.dryRun(true);
      default -> {}
    }
    pending.setLaunchPayload(
        switch (invalidField) {
          case "missing" -> null;
          case "malformed" -> "{invalid";
          default -> JsonUtils.toJson(original.build());
        });
    when(triggerRequestMapper.selectByTenantAndRequestId("t1", pending.getRequestId()))
        .thenReturn(pending);
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setRequestId(pending.getRequestId());

    assertThatThrownBy(() -> service.approvePendingCatchUp(command))
        .isInstanceOf(BizException.class)
        .extracting("code")
        .isEqualTo(ResultCode.STATE_CONFLICT);
    verify(triggerRequestMapper, never())
        .updateRequestStatusConditional(any(), any(), any(), any());
    verify(triggerOutboxPublisher, never()).publishLaunch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("非补跑类型的请求不允许走审批补跑,以 not_catch_up 直接拒绝")
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
        .publishLaunch(anyString(), anyString(), anyString(), any(LaunchEnvelope.class));
  }

  @Test
  @DisplayName("业务日历解析不出业务日期时定时触发返回 skipped,不落库也不发消息")
  void shouldSkipScheduledTriggerWhenBizDateResolutionReturnsNull() {
    ScheduledTriggerCommand command = new ScheduledTriggerCommand(
        scheduledDescriptor(),
        Instant.parse("2026-03-28T18:00:00Z"),
        TriggerType.SCHEDULED,
        "req-skip",
        "trace-skip");
    LaunchRequest launchRequest = new LaunchRequest(
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
    verify(occurrenceRecorder)
        .record(
            eq(command.descriptor()),
            eq(command.fireTime()),
            eq(null),
            eq(ScheduledOccurrenceStatus.SKIPPED_CALENDAR),
            eq(null));
    verify(triggerOutboxPublisher, never())
        .publishLaunch(anyString(), anyString(), anyString(), any(LaunchEnvelope.class));
  }

  @Test
  @DisplayName("声明了依赖且上游未就绪时抛 UpstreamNotReadyException,交由 Quartz 延迟重检而不丢批")
  void launchScheduled_upstreamNotReady_throwsUpstreamNotReadyException() {
    // ADR-043:声明了 dependsOn 且上游未就绪 → 不再返回 skipped 丢批,改抛 UpstreamNotReadyException,
    // 由 QuartzLaunchJob 创建 one-shot retry trigger。
    TriggerDescriptor descriptor = scheduledDescriptor();
    descriptor.setDependsOnJobCode("UPSTREAM_SETTLE");
    ScheduledTriggerCommand command = new ScheduledTriggerCommand(
        descriptor,
        Instant.parse("2026-03-28T18:00:00Z"),
        TriggerType.SCHEDULED,
        "req-nr",
        "trace-nr");
    LaunchRequest launchRequest = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 28),
        TriggerType.SCHEDULED,
        "req-nr",
        "trace-nr",
        Map.of("calendarCode", "BIZ_CAL"));

    when(launchAdapterService.fromScheduledTrigger(eq(command), any())).thenReturn(launchRequest);
    when(upstreamReadiness.readyAt("t1", "UPSTREAM_SETTLE", LocalDate.of(2026, Month.MARCH, 28)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.launchScheduled(command))
        .isInstanceOf(UpstreamNotReadyException.class);

    // 既不落 trigger_request 也不发 outbox(未 fire)
    verify(triggerRequestMapper, never()).insert(any());
    verify(triggerOutboxPublisher, never())
        .publishLaunch(anyString(), anyString(), anyString(), any(LaunchEnvelope.class));
  }

  @Test
  @DisplayName("上游就绪后定时触发正常落 trigger_request 并进入转发")
  void launchScheduled_upstreamReady_proceedsToForward() {
    TriggerDescriptor descriptor = scheduledDescriptor();
    descriptor.setDependsOnJobCode("UPSTREAM_SETTLE");
    ScheduledTriggerCommand command = new ScheduledTriggerCommand(
        descriptor,
        Instant.parse("2026-03-28T18:00:00Z"),
        TriggerType.SCHEDULED,
        "req-ok",
        "trace-ok");
    LaunchRequest launchRequest = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 28),
        TriggerType.SCHEDULED,
        "req-ok",
        "trace-ok",
        Map.of("calendarCode", "BIZ_CAL"));

    when(launchAdapterService.fromScheduledTrigger(eq(command), any())).thenReturn(launchRequest);
    when(upstreamReadiness.readyAt("t1", "UPSTREAM_SETTLE", LocalDate.of(2026, Month.MARCH, 28)))
        .thenReturn(Optional.of(Instant.parse("2026-03-28T20:00:00Z")));

    LaunchResponse response = service.launchScheduled(command);

    // 就绪 → 正常 persistAndForward(落 trigger_request)
    assertThat(response).isNotNull();
    verify(triggerRequestMapper).insert(any());
    ArgumentCaptor<LaunchEnvelope> envelope = ArgumentCaptor.forClass(LaunchEnvelope.class);
    verify(triggerOutboxPublisher)
        .publishLaunch(anyString(), anyString(), anyString(), envelope.capture());
    assertThat(envelope.getValue().launchRequest().params())
        .containsEntry("dependencyReadyAt", "2026-03-28T20:00:00Z");
    verify(occurrenceRecorder)
        .recordAccepted(
            eq(command),
            eq(LocalDate.of(2026, Month.MARCH, 28)),
            eq(ScheduledOccurrenceStatus.REQUESTED),
            eq("req-ok"));
  }

  @Test
  @DisplayName("多个上游采用 AND 闸门，任一上游未就绪都延期同一次 fire")
  void launchScheduled_multipleUpstreamsAnyNotReady_defersFire() {
    TriggerDescriptor descriptor = scheduledDescriptor();
    descriptor.setDependsOnJobCodes(List.of("UPSTREAM_A", "UPSTREAM_B"));
    ScheduledTriggerCommand command = new ScheduledTriggerCommand(
        descriptor,
        Instant.parse("2026-03-28T18:00:00Z"),
        TriggerType.SCHEDULED,
        "req-multi-dependency",
        "trace-multi-dependency");
    LaunchRequest launchRequest = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 28),
        TriggerType.SCHEDULED,
        "req-multi-dependency",
        "trace-multi-dependency",
        Map.of("calendarCode", "BIZ_CAL"));

    when(launchAdapterService.fromScheduledTrigger(eq(command), any())).thenReturn(launchRequest);
    when(upstreamReadiness.readyAt("t1", "UPSTREAM_A", LocalDate.of(2026, Month.MARCH, 28)))
        .thenReturn(Optional.of(Instant.parse("2026-03-28T19:00:00Z")));
    when(upstreamReadiness.readyAt("t1", "UPSTREAM_B", LocalDate.of(2026, Month.MARCH, 28)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.launchScheduled(command))
        .isInstanceOf(UpstreamNotReadyException.class)
        .extracting("dependsOnJobCode")
        .isEqualTo("UPSTREAM_B");
    verify(upstreamReadiness).readyAt("t1", "UPSTREAM_A", LocalDate.of(2026, Month.MARCH, 28));
    verify(upstreamReadiness).readyAt("t1", "UPSTREAM_B", LocalDate.of(2026, Month.MARCH, 28));
    verify(triggerRequestMapper, never()).insert(any());
    verify(triggerOutboxPublisher, never())
        .publishLaunch(anyString(), anyString(), anyString(), any(LaunchEnvelope.class));
  }

  @Test
  @DisplayName("规范化依赖仅含跨日边时不回退到旧标量字段执行同日闸门")
  void launchScheduled_previousDayOnlyDependency_skipsSameDayFastGate() {
    TriggerDescriptor descriptor = scheduledDescriptor();
    descriptor.setDependsOnJobCode("UPSTREAM_PREVIOUS_DAY");
    descriptor.setDependsOnJobCodes(List.of());
    ScheduledTriggerCommand command = new ScheduledTriggerCommand(
        descriptor,
        Instant.parse("2026-03-28T18:00:00Z"),
        TriggerType.SCHEDULED,
        "req-previous-day-only",
        "trace-previous-day-only");
    LaunchRequest launchRequest = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 28),
        TriggerType.SCHEDULED,
        "req-previous-day-only",
        "trace-previous-day-only",
        Map.of("calendarCode", "BIZ_CAL"));
    when(launchAdapterService.fromScheduledTrigger(eq(command), any())).thenReturn(launchRequest);

    assertThat(service.launchScheduled(command)).isNotNull();

    verify(upstreamReadiness, never()).readyAt(anyString(), anyString(), any());
    verify(triggerRequestMapper).insert(any());
  }

  @Test
  @DisplayName("人工补跑登记生成待审批记录,并把 misfire 待办关联到该补跑请求")
  void createPendingCatchUp_linksMisfirePendingToCatchUpRequest() {
    ScheduledTriggerCommand command = new ScheduledTriggerCommand(
        scheduledDescriptor(),
        Instant.parse("2026-03-28T18:00:00Z"),
        TriggerType.CATCH_UP,
        "req-cu",
        "trace-cu");
    LaunchRequest launchRequest = new LaunchRequest(
        "t1",
        "IMPORT_JOB",
        LocalDate.of(2026, Month.MARCH, 28),
        TriggerType.CATCH_UP,
        "req-cu",
        "trace-cu",
        Map.of("catchUp", true));
    doAnswer(invocation -> {
          TriggerRequestEntity entity = invocation.getArgument(0);
          entity.setId(900L);
          return 1;
        })
        .when(triggerRequestMapper)
        .insert(any());
    doAnswer(invocation -> {
          TriggerMisfirePendingEntity entity = invocation.getArgument(0);
          assertThat(entity.getExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(6)));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Workflow 停用后普通定时和无持久义务的 CATCH_UP 均不得创建请求")
  void disabledWorkflow_shouldRejectScheduledRequestWithoutPersistedMisfire(boolean catchUp) {
    TriggerDescriptor descriptor = scheduledDescriptor();
    when(triggerRequestMapper.existsEnabledWorkflowDefinition(any(), any())).thenReturn(false);
    ScheduledTriggerCommand command = new ScheduledTriggerCommand(
        descriptor,
        Instant.parse("2026-03-27T04:00:00Z"),
        catchUp ? TriggerType.CATCH_UP : TriggerType.SCHEDULED,
        "disabled-new",
        "disabled-trace");
    assertThatThrownBy(() -> service.launchScheduled(command)).isInstanceOf(BizException.class);
    verify(triggerRequestMapper, never()).insert(any());
    verify(triggerOutboxPublisher, never()).publishLaunch(any(), any(), any(), any());
  }

  @Test
  @DisplayName("租户被停用时手工触发以 BizException 拒绝,不落库也不投递")
  void launch_suspendedTenant_throwsBizException() {
    when(tenantStatusMapper.selectStatus("t1")).thenReturn("SUSPENDED");

    assertThatThrownBy(() -> service.launch(
            new TriggerLaunchCommand(validRequest(), "idem-susp", "req-susp", "trace-susp")))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.TENANT_SUSPENDED);
          assertThat(failure.getMessageKey()).isEqualTo("error.trigger.tenant_suspended");
          assertThat(failure.getMessageArgs()).containsExactly("t1");
        });

    verify(triggerRequestMapper, never()).insert(any());
    verify(triggerOutboxPublisher, never())
        .publishLaunch(anyString(), anyString(), anyString(), any(LaunchEnvelope.class));
  }

  @Test
  @DisplayName("租户被停用时定时触发以稳定错误码拒绝，并携带原租户身份")
  void launchScheduled_suspendedTenant_throwsBizException() {
    when(tenantStatusMapper.selectStatus("t1")).thenReturn("SUSPENDED");

    ScheduledTriggerCommand command = new ScheduledTriggerCommand(
        scheduledDescriptor(),
        Instant.parse("2026-03-28T18:00:00Z"),
        TriggerType.SCHEDULED,
        "req-susp",
        "trace-susp");

    assertThatThrownBy(() -> service.launchScheduled(command))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.TENANT_SUSPENDED);
          assertThat(failure.getMessageKey()).isEqualTo("error.trigger.tenant_suspended");
          assertThat(failure.getMessageArgs()).containsExactly("t1");
        });
  }

  private TriggerLaunchRequest validRequest() {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setWorkflowDefinitionId(71L);
    request.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of());
    return request;
  }

  private TriggerDescriptor scheduledDescriptor() {
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId("t1");
    descriptor.setJobCode("IMPORT_JOB");
    descriptor.setWorkflowDefinitionId(71L);
    descriptor.setWorkflowCode("JOB-WF-71");
    descriptor.setTimezone("Asia/Shanghai");
    descriptor.setCalendarCode("BIZ_CAL");
    return descriptor;
  }
}
