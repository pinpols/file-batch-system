package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.dto.LaunchEnvelope;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyVerifier;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchResponse;
import io.github.pinpols.batch.trigger.domain.ExternalEventLaunchStatus;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.command.ExternalEventLaunchCommand;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.domain.command.TriggerLaunchCommand;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.service.ScheduledOccurrenceRecorder;
import io.github.pinpols.batch.trigger.service.TriggerService;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import io.github.pinpols.batch.trigger.web.request.ExternalEventLaunchRequest;
import io.github.pinpols.batch.trigger.web.request.ScheduledOccurrenceCatchUpRequest;
import io.github.pinpols.batch.trigger.web.request.TriggerLaunchRequest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.flyway.enabled=false",
      "spring.autoconfigure.exclude=org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
      "batch.orchestrator.base-url=http://localhost:8082",
      // 关 QuartzMetricsConfiguration：本测试用 mock(Scheduler) 而非真 Quartz，
      // 否则 mock 的 getListenerManager() 返回 null 在 @PostConstruct 触发 NPE
      "batch.trigger.quartz-metrics.enabled=false",
      // ADR-010: 默认 true，test 1 走异步路径写 outbox；test 2 的 approvePendingCatchUp 仍直接调 adapter
    })
@Import(TriggerServiceIntegrationTest.TestConfig.class)
@DisplayName("Trigger 启动链路集成:同一幂等键重复提交只落一条 ACCEPTED 请求,审批补跑同事务写出箱事件而非直连编排服务")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TriggerServiceIntegrationTest extends AbstractIntegrationTest {

  private final TriggerService triggerService;

  private final JdbcTemplate jdbcTemplate;

  private final TriggerDefinitionLoader triggerDefinitionLoader;
  private final TriggerApiKeyVerifier triggerApiKeyVerifier;
  private final TriggerRequestMapper triggerRequestMapper;
  private final PlatformTransactionManager transactionManager;

  TriggerServiceIntegrationTest(
      TriggerService triggerService,
      JdbcTemplate jdbcTemplate,
      TriggerDefinitionLoader triggerDefinitionLoader,
      TriggerApiKeyVerifier triggerApiKeyVerifier,
      TriggerRequestMapper triggerRequestMapper,
      PlatformTransactionManager transactionManager) {
    this.triggerService = triggerService;
    this.jdbcTemplate = jdbcTemplate;
    this.triggerDefinitionLoader = triggerDefinitionLoader;
    this.triggerApiKeyVerifier = triggerApiKeyVerifier;
    this.triggerRequestMapper = triggerRequestMapper;
    this.transactionManager = transactionManager;
  }

  @MockitoBean
  private RestClient orchestratorRestClient;

  @Autowired
  private ScheduledOccurrenceRecorder occurrenceRecorder;

  @MockitoSpyBean
  private ScheduledOccurrenceMapper occurrenceMapper;

  @Autowired
  private SqlSessionTemplate sqlSessionTemplate;

  @BeforeEach
  void cleanUp() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone,\s\
        holiday_roll_rule, catch_up_policy, catch_up_max_days, enabled)\s\
        values ('t1', 'test_cal', 'Trigger integration test calendar', 'UTC', 'SKIP', 'AUTO', 3, true)\s\
        on conflict (tenant_id, calendar_code) do update set enabled = true\
        """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = 't1'\s\
        and workflow_code like 'trigger-service-test-%%'\
        """;

    jdbcTemplate.update(
        insertBusinessCalendarSql);
    String ownedWorkflowIds = """
        select id from batch.workflow_definition where tenant_id = 't1'\s\
        and workflow_code like 'trigger-service-test-%%'\
        """;
    String ownedRequestIds = """
        select request_id from batch.trigger_request where tenant_id = 't1' and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    String ownedDeliveries = """
        select delivery.id from batch.external_event_delivery delivery where delivery.tenant_id = 't1'\s\
        and exists (select 1 from jsonb_array_elements_text(delivery.target_workflow_definition_ids) target(id)\s\
        where target.id::bigint in (%s))\
        """.formatted(ownedWorkflowIds);

    final String deleteExternalEventDeliveryAliasSql = """
        delete from batch.external_event_delivery_alias where tenant_id = 't1' and delivery_id in (%s)\
        """.formatted(ownedDeliveries);
    jdbcTemplate.update(deleteExternalEventDeliveryAliasSql);
    final String deleteExternalEventDeliverySql = """
        delete from batch.external_event_delivery where id in (%s)\
        """.formatted(ownedDeliveries);
    jdbcTemplate.update(
        deleteExternalEventDeliverySql);
    final String deleteScheduledOccurrenceCatchUpAuditSql = """
        delete from batch.scheduled_occurrence_catch_up_audit where tenant_id = 't1'\s\
        and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(
        deleteScheduledOccurrenceCatchUpAuditSql);
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id = 't1' and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(deleteScheduledOccurrenceSql);
    final String deleteScheduledOccurrenceCoverageSql = """
        delete from batch.scheduled_occurrence_coverage where tenant_id = 't1'\s\
        and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(deleteScheduledOccurrenceCoverageSql);
    final String deleteTriggerMisfirePendingSql = """
        delete from batch.trigger_misfire_pending where tenant_id = 't1' and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(deleteTriggerMisfirePendingSql);
    final String deleteJobDependencyWaitingLaunchSql = """
        delete from batch.job_dependency_waiting_launch where tenant_id = 't1' and request_id in (%s)\
        """.formatted(ownedRequestIds);
    jdbcTemplate.update(deleteJobDependencyWaitingLaunchSql);
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id = 't1' and request_id in (%s)\
        """.formatted(ownedRequestIds);
    jdbcTemplate.update(deleteTriggerOutboxEventSql);
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = 't1' and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(deleteTriggerRequestSql);
    final String deleteExternalEventExpectationDefinitionSql = """
        delete from batch.external_event_expectation_definition where tenant_id = 't1'\s\
        and workflow_definition_id in (%s)\
        """.formatted(ownedWorkflowIds);
    jdbcTemplate.update(
        deleteExternalEventExpectationDefinitionSql);
    jdbcTemplate.update(deleteWorkflowDefinitionSql);
  }

  @AfterEach
  void cleanUpOwnedFixtures() {
    cleanUp();
  }

  @ParameterizedTest
  @ValueSource(strings = {"SETTLED", "FAILED", "SKIPPED", "MANUAL_RELEASED"})
  @DisplayName("已关闭业务日拒绝普通 API 请求并返回明确状态冲突且无请求或出箱写入")
  void shouldRejectClosedBatchDayApiLaunchWithBusinessConflict(String dayStatus) {
  final String insertBatchDayInstanceSql = """
      insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at, settled_at)\s\
      values ('t1', 'test_cal', ?, ?, current_timestamp, current_timestamp)\s\
      on conflict (tenant_id, calendar_code, biz_date) do update set day_status = excluded.day_status\
      """;
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = 't1' and request_id = ?
  """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = 't1' and request_id = ?
  """;
    final String selectDayStatusSql = """
        select day_status from batch.batch_day_instance where tenant_id = 't1' and calendar_code = 'test_cal'\s\
        and biz_date = ?\
        """;

    LocalDate closedDate = LocalDate.of(2031, Month.JANUARY, 1)
        .plusDays(
            switch (dayStatus) {
              case "SETTLED" -> 0;
              case "FAILED" -> 1;
              case "SKIPPED" -> 2;
              case "MANUAL_RELEASED" -> 3;
              default -> throw new IllegalArgumentException(dayStatus);
            });
    Long workflowId = insertWorkflowDefinition("closed-api-" + dayStatus);
    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        closedDate,
        dayStatus);
    String requestId = "closed-api-" + dayStatus;
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setWorkflowDefinitionId(workflowId);
    request.setBizDate(closedDate);
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of("source", "closed-day-api-regression"));

    assertThatThrownBy(() -> triggerService.launch(
            new TriggerLaunchCommand(request, requestId, requestId, "trace-" + requestId)))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          assertThat(failure.getMessageKey()).isEqualTo("error.trigger.batch_day_closed");
        });
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, requestId);
    assertThat(selectedTriggerRequestCount).isZero();
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, requestId);
    assertThat(selectedTriggerOutboxEventCount).isZero();
    String selectedDayStatus =
        jdbcTemplate.queryForObject(selectDayStatusSql, String.class, closedDate);
    assertThat(selectedDayStatus).isEqualTo(dayStatus);
  }

  @Test
  @DisplayName("同幂等键的两次启动只落一条 ACCEPTED 请求与一条 outbox 事件,且按 requestId/traceId 回查状态一致")
  void shouldPersistAcceptedRequestAndDeduplicateRepeatedLaunch() {
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?
  """;
  final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and request_id = ?\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\
      """;

    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setWorkflowDefinitionId(insertWorkflowDefinition("trigger-service-integration"));
    request.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    request.setTriggerType(TriggerType.API);
    request.setParams(Map.of("source", "integration"));

    TriggerLaunchCommand command =
        new TriggerLaunchCommand(request, "idem-001", "req-001", "trace-001");

    triggerService.launch(command);
    triggerService.launch(command);

    Integer count = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        "t1",
        "idem-001");
    String status = jdbcTemplate.queryForObject(
        selectRequestStatusSql,
        String.class,
        "t1",
        "req-001");

    assertThat(count).isEqualTo(1);
    assertThat(status).isEqualTo("ACCEPTED");
    // ADR-010: 异步路径写 trigger_outbox_event，不调 adapter
    Integer outboxCount = jdbcTemplate.queryForObject(
        selectCountSql2,
        Integer.class,
        "t1",
        "req-001");
    assertThat(outboxCount).isEqualTo(1);

    var launchStatus = triggerService.findLaunchStatus("t1", "idem-001");
    assertThat(launchStatus.requestId()).isEqualTo("req-001");
    assertThat(launchStatus.traceId()).isEqualTo("trace-001");
    assertThat(launchStatus.requestStatus()).isEqualTo("ACCEPTED");
    assertThat(launchStatus.relatedJobInstanceId()).isNull();
    assertThat(launchStatus.instanceStatus()).isNull();
    assertThat(launchStatus.updatedAt()).isNotNull();
  }

  @Test
  @DisplayName("同业务日不同 Quartz fire 各自接纳，重复同一 fire 复用原 request 与 Outbox")
  void shouldKeepDistinctSameDayScheduledFiresAndDeduplicateReplay() {
  final String insertBusinessCalendarSql = """
          insert into batch.business_calendar (
              tenant_id, calendar_code, calendar_name, timezone, holiday_roll_rule,
              catch_up_policy, catch_up_max_days, cutoff_time, late_arrival_tolerance_min,
              sla_offset_min, enabled
          ) values ('t1', ?, ?, 'UTC', 'SKIP', 'AUTO', 3, ?, 30, 120, true)
          """;
  final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code
          ) values ('t1', ?, ?, 'DAG', 1, true, 'CRON', '0 0 * * * ?', 'UTC', 'SCHEDULED', true,
                    ?) returning id
          """;
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = 't1' and workflow_definition_id = ?\s\
      and trigger_type = 'SCHEDULED'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = 't1'\s\
      and request_id in ('same-day-first', 'same-day-second')\
      """;
    final String selectBizDateSql = """
        select biz_date, occurrence_status, request_id from batch.scheduled_occurrence where tenant_id = 't1'\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id = 't1' and workflow_definition_id = ?\
        """;
    final String deleteScheduledOccurrenceCoverageSql = """
        delete from batch.scheduled_occurrence_coverage where tenant_id = 't1' and workflow_definition_id = ?\
        """;
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id = 't1'\s\
        and request_id in ('same-day-first', 'same-day-replay', 'same-day-second')\
        """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = 't1'\s\
        and request_id in ('same-day-first', 'same-day-replay', 'same-day-second')\
        """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = 't1' and id = ?
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id = 't1' and calendar_code = ?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = 't1' and calendar_code = ?
    """;

    String workflowCode =
        "trigger-service-test-same-day-fires-" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "same_day_cal_" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(insertBusinessCalendarSql, calendarCode, calendarCode, LocalTime.MIDNIGHT);
    Long workflowDefinitionId =
    jdbcTemplate.queryForObject(insertWorkflowDefinitionSql, Long.class, workflowCode, workflowCode, calendarCode);
    Instant firstFire = Instant.parse("2036-06-01T12:00:00Z");
    Instant secondFire = firstFire.plusSeconds(3600);
    try {
      TriggerDescriptor descriptor =
          triggerDefinitionLoader.loadByWorkflowDefinitionId("t1", workflowDefinitionId);

      LaunchResponse first = triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor, firstFire, TriggerType.SCHEDULED, "same-day-first", "trace-first"));
      LaunchResponse replay = triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor, firstFire, TriggerType.SCHEDULED, "same-day-replay", "trace-replay"));
      LaunchResponse second = triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor, secondFire, TriggerType.SCHEDULED, "same-day-second", "trace-second"));

      assertThat(first.instanceNo()).isEqualTo("same-day-first");
      assertThat(replay.instanceNo()).isEqualTo("same-day-first");
      assertThat(second.instanceNo()).isEqualTo("same-day-second");
      Integer selectedTriggerRequestCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowDefinitionId);
      assertThat(selectedTriggerRequestCount).isEqualTo(2);
      Integer selectedTriggerOutboxEventCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
      assertThat(selectedTriggerOutboxEventCount).isEqualTo(2);
      Map<String, Object> firstOccurrence = jdbcTemplate.queryForMap(
          selectBizDateSql,
          workflowDefinitionId,
          Timestamp.from(firstFire));
      Map<String, Object> secondOccurrence = jdbcTemplate.queryForMap(
          selectBizDateSql,
          workflowDefinitionId,
          Timestamp.from(secondFire));
      assertThat(firstOccurrence.get("biz_date")).isEqualTo(secondOccurrence.get("biz_date"));
      assertThat(firstOccurrence.get("occurrence_status")).isEqualTo("REQUESTED");
      assertThat(secondOccurrence.get("occurrence_status")).isEqualTo("REQUESTED");
      assertThat(firstOccurrence.get("request_id")).isEqualTo("same-day-first");
      assertThat(secondOccurrence.get("request_id")).isEqualTo("same-day-second");
    } finally {
      jdbcTemplate.update(
          deleteScheduledOccurrenceSql,
          workflowDefinitionId);
      jdbcTemplate.update(
          deleteScheduledOccurrenceCoverageSql,
          workflowDefinitionId);
      jdbcTemplate.update(deleteTriggerOutboxEventSql);
      jdbcTemplate.update(deleteTriggerRequestSql);
      jdbcTemplate.update(
          deleteWorkflowDefinitionSql,
          workflowDefinitionId);
      jdbcTemplate.update(
          deleteBatchDayCatchupControlSql,
          calendarCode);
      jdbcTemplate.update(
          deleteBusinessCalendarSql,
          calendarCode);
    }
  }

  @Test
  @DisplayName("外部事件按来源幂等键区分投递,同一来源事件重试只保留一个请求")
  void shouldDeduplicateExternalEventPerSourceIdentity() {
  final String selectRequestIdSql = """
      select request_id, dedup_key, external_event_code, external_event_source, external_event_id\s\
      from batch.trigger_request where tenant_id = 't1' and workflow_definition_id = ? and biz_date = ?\s\
      order by dedup_key\
      """;
  final String selectCountSql = """
      select count(*) from batch.trigger_outbox_event where tenant_id = 't1'\s\
      and request_id in ('req-source-a-17', 'req-source-a-18', 'req-source-b-17')\
      """;
  final String selectPayloadSql = """
      select payload #>> '{launchRequest,externalEventSource}' from batch.trigger_outbox_event\s\
      where tenant_id = 't1' and request_id = 'req-source-a-17'\
      """;
  final String selectPayloadSql2 = """
      select payload #>> '{launchRequest,externalEventId}' from batch.trigger_outbox_event\s\
      where tenant_id = 't1' and request_id = 'req-source-a-17'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id = 't1' and workflow_definition_id = ?\s\
      and dedup_key = ?\
      """;

    Long workflowDefinitionId = insertWorkflowDefinition("external-event-identity");
    LocalDate bizDate = LocalDate.of(2026, Month.MARCH, 28);

    LaunchResponse sourceAEvent17 = launchExternalEvent(
        workflowDefinitionId, bizDate, "source-a:event-17", "source-a:event-17", "req-source-a-17");
    LaunchResponse sourceAEvent17Retry = launchExternalEvent(
        workflowDefinitionId,
        bizDate,
        "source-a:event-17",
        "retry-with-new-http-key",
        "req-source-a-17-retry");
    LaunchResponse sourceAEvent18 = launchExternalEvent(
        workflowDefinitionId, bizDate, "source-a:event-18", "source-a:event-18", "req-source-a-18");
    LaunchResponse sourceBEvent17 = launchExternalEvent(
        workflowDefinitionId, bizDate, "source-b:event-17", "source-b:event-17", "req-source-b-17");

    assertThat(sourceAEvent17Retry.instanceNo()).isEqualTo(sourceAEvent17.instanceNo());
    assertThat(triggerService.findLaunchStatus("t1", "retry-with-new-http-key").requestId())
        .isEqualTo(sourceAEvent17.instanceNo());
    assertThat(sourceAEvent18.instanceNo()).isNotEqualTo(sourceAEvent17.instanceNo());
    assertThat(sourceBEvent17.instanceNo()).isNotEqualTo(sourceAEvent17.instanceNo());

    var requests = jdbcTemplate.queryForList(
        selectRequestIdSql,
        workflowDefinitionId,
        bizDate);
    assertThat(requests).hasSize(3);
    assertThat(requests)
        .allSatisfy(row -> assertThat(row.get("external_event_code")).isEqualTo("PAYMENT_SETTLED"));
    assertThat(requests)
        .extracting(row -> row.get("external_event_source"))
        .containsExactlyInAnyOrder("source-a", "source-a", "source-b");
    assertThat(requests)
        .extracting(row -> row.get("external_event_id"))
        .containsExactlyInAnyOrder("event-17", "event-18", "event-17");
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(selectedTriggerOutboxEventCount).isEqualTo(3);
    String selectedPayload = jdbcTemplate.queryForObject(selectPayloadSql, String.class);
    assertThat(selectedPayload).isEqualTo("source-a");
    String selectedPayloadRead2 = jdbcTemplate.queryForObject(selectPayloadSql2, String.class);
    assertThat(selectedPayloadRead2).isEqualTo("event-17");

    Long secondWorkflowDefinitionId = insertWorkflowDefinition("external-event-identity-target-b");
    LaunchResponse sameSourceEventOtherTarget = launchExternalEvent(
        secondWorkflowDefinitionId,
        bizDate,
        "source-a:event-17",
        "source-a:event-17:workflow:" + secondWorkflowDefinitionId,
        "req-source-a-17-target-b");
    assertThat(sameSourceEventOtherTarget.instanceNo()).isEqualTo("req-source-a-17-target-b");
    Integer selectedTriggerRequestCount = jdbcTemplate.queryForObject(
        selectCountSql2,
        Integer.class,
        secondWorkflowDefinitionId,
        "source-a:event-17:workflow:" + secondWorkflowDefinitionId);
    assertThat(selectedTriggerRequestCount).isOne();
  }

  @Test
  @DisplayName("外部 EVENT API Key 从 PostgreSQL 验证哈希、租户、source scope 并记录使用时间")
  void shouldAuthenticateExternalEventApiKeyFromPostgres() {
    final String insertApiKeySql = """
        insert into batch.api_key (tenant_id, key_name, key_prefix, key_hash, salt, key_hash_algo, scopes,\s\
        enabled) values (?, ?, ?, ?, ?, ?, ?, true) returning id\
        """;
    final String selectLastUsedAtSql = """
        select last_used_at from batch.api_key where id = ?
    """;
    final String deleteApiKeySql = """
        delete from batch.api_key where id = ?
    """;

    String keyName = "billing-" + UUID.randomUUID();
    String rawKey = "bk_" + UUID.randomUUID().toString().replace("-", "");
    ApiKeyHasher.SaltedHash hash = ApiKeyHasher.hashWithSaltKdf(rawKey);
    Long keyId = jdbcTemplate.queryForObject(
        insertApiKeySql,
        Long.class,
        "t1",
        keyName,
        rawKey.substring(0, 8),
        hash.hash(),
        hash.salt(),
        ApiKeyHasher.ALGO_PBKDF2,
        TriggerApiKeyVerifier.EVENT_SCOPE);

    try {
      var principal = triggerApiKeyVerifier.authenticate(rawKey, "t1").orElseThrow();

      assertThat(principal.keyName()).isEqualTo(keyName);
      assertThat(triggerApiKeyVerifier.authorizesExternalEvent(principal, "t1", keyName))
          .isTrue();
      assertThat(triggerApiKeyVerifier.authorizesExternalEvent(principal, "t1", "forged-source"))
          .isFalse();
      assertThat(triggerApiKeyVerifier.authenticate(rawKey, "t2")).isEmpty();
      Timestamp selectedLastUsedAt =
          jdbcTemplate.queryForObject(selectLastUsedAtSql, Timestamp.class, keyId);
      assertThat(selectedLastUsedAt).isNotNull();
    } finally {
      jdbcTemplate.update(deleteApiKeySql, keyId);
    }
  }

  @Test
  @DisplayName("并发重投同一外部事件时唯一投递与两个幂等键状态回查保持一致")
  void shouldSerializeConcurrentExternalEventRedelivery() throws Exception {
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = 't1' and workflow_definition_id = ?\s\
      and external_event_source = 'billing' and external_event_id = 'close-99'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = 't1'\s\
      and request_id in ('req-first', 'req-retry')\
      """;

    Long workflowDefinitionId = insertWorkflowDefinition("external-event-concurrent-identity");
    LocalDate bizDate = LocalDate.of(2026, Month.MARCH, 29);
    TriggerLaunchCommand first = externalEventCommand(
        workflowDefinitionId, bizDate, "billing", "close-99", "key-first", "req-first");
    TriggerLaunchCommand second = externalEventCommand(
        workflowDefinitionId, bizDate, "billing", "close-99", "key-retry", "req-retry");

    LaunchResponse firstResponse;
    LaunchResponse secondResponse;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CyclicBarrier(2);
      var firstResult = executor.submit(() -> {
        start.await();
        return triggerService.launch(first);
      });
      var secondResult = executor.submit(() -> {
        start.await();
        return triggerService.launch(second);
      });
      firstResponse = firstResult.get();
      secondResponse = secondResult.get();
    }

    assertThat(secondResponse.instanceNo()).isEqualTo(firstResponse.instanceNo());
    assertThat(triggerService.findLaunchStatus("t1", "key-first").requestId())
        .isEqualTo(firstResponse.instanceNo());
    assertThat(triggerService.findLaunchStatus("t1", "key-retry").requestId())
        .isEqualTo(firstResponse.instanceNo());
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowDefinitionId);
    assertThat(selectedTriggerRequestCount).isOne();
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedTriggerOutboxEventCount).isOne();
  }

  @Test
  @DisplayName("自动路由事件只选择同租户、同日历且启用的预期 Workflow，并按 ID 稳定排序")
  void shouldResolveEnabledExternalEventTargetsFromExpectationDefinitions() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set enabled = false where tenant_id='t1' and id=?
    """;
    final String insertExternalEventExpectationDefinitionSql = """
        insert into batch.external_event_expectation_definition (tenant_id, calendar_code,\s\
        workflow_definition_id, event_code, expected_local_time, enabled)\s\
        values ('t1', 'test_cal', ?, ?, '04:00', true), ('t1', 'test_cal', ?, ?, '05:00', true), ('t1',\s\
        'test_cal', ?, ?, '06:00', true)\
        """;

    String eventCode = "AUTO_ROUTE_" + UUID.randomUUID().toString().replace("-", "");
    Long targetA = insertWorkflowDefinition("event-target-a-" + UUID.randomUUID());
    Long targetB = insertWorkflowDefinition("event-target-b-" + UUID.randomUUID());
    Long disabledWorkflow = insertWorkflowDefinition("event-target-disabled-" + UUID.randomUUID());
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        disabledWorkflow);
    jdbcTemplate.update(
        insertExternalEventExpectationDefinitionSql,
        targetB,
        eventCode,
        targetA,
        eventCode,
        disabledWorkflow,
        eventCode);

    assertThat(triggerRequestMapper.selectEnabledExternalEventWorkflowTargets("t1", eventCode))
        .containsExactly(targetA, targetB);
    assertThat(triggerRequestMapper.selectEnabledExternalEventWorkflowTargets("t2", eventCode))
        .isEmpty();
  }

  @Test
  @DisplayName("同一外部事件在一个事务内冻结多个目标、逐 Workflow 写入请求和 Outbox，并聚合状态")
  void shouldFanOutExternalEventAtomicallyAndKeepFirstTargetSnapshot() {
  final String selectCountSql = """
      select count(*) from batch.external_event_delivery where tenant_id='t1' and event_source='billing'\s\
      and event_id='event-700'\
      """;
  final String selectTargetWorkflowDefinitionIdsSql = """
      select target_workflow_definition_ids from batch.external_event_delivery where tenant_id='t1'\s\
      and event_source='billing' and event_id='event-700'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id='t1' and external_event_source='billing'\s\
      and external_event_id='event-700'\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.trigger_outbox_event where tenant_id='t1' and request_id in (select request_id\s\
      from batch.trigger_request where tenant_id='t1' and external_event_source='billing'\s\
      and external_event_id='event-700')\
      """;

    String eventCode = "FANOUT_" + UUID.randomUUID().toString().replace("-", "");
    LocalDate bizDate = LocalDate.of(2032, Month.APRIL, 5);
    Long targetA = insertWorkflowDefinition("event-fanout-a-" + UUID.randomUUID());
    Long targetB = insertWorkflowDefinition("event-fanout-b-" + UUID.randomUUID());
    insertEventExpectation(targetA, eventCode);
    insertEventExpectation(targetB, eventCode);

    ExternalEventLaunchCommand firstCommand = externalEventDeliveryCommand(
        eventCode, "event-700", bizDate, "key-fanout-first", "delivery-first", "trace-fanout");
    ExternalEventLaunchResponse first = triggerService.launchExternalEvent(firstCommand);
    Long laterTarget = insertWorkflowDefinition("event-fanout-late-" + UUID.randomUUID());
    insertEventExpectation(laterTarget, eventCode);
    ExternalEventLaunchResponse retry =
        triggerService.launchExternalEvent(externalEventDeliveryCommand(
            eventCode, "event-700", bizDate, "key-fanout-retry", "delivery-retry", "trace-retry"));

    assertThat(first.requestId()).isEqualTo("delivery-first");
    assertThat(retry).isEqualTo(first);
    assertThat(first.targets())
        .extracting(target -> target.workflowDefinitionId())
        .containsExactly(targetA, targetB);
    Integer selectedExternalEventDeliveryCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(selectedExternalEventDeliveryCount).isOne();
    String selectedTargetWorkflowDefinitionIds =
        jdbcTemplate.queryForObject(selectTargetWorkflowDefinitionIdsSql, String.class);
    assertThat(selectedTargetWorkflowDefinitionIds)
        .contains(targetA.toString(), targetB.toString())
        .doesNotContain(laterTarget.toString());
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedTriggerRequestCount).isEqualTo(2);
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedTriggerOutboxEventCount).isEqualTo(2);

    ExternalEventLaunchStatus status =
        triggerService.findExternalEventStatus("t1", "billing", "key-fanout-retry");
    assertThat(status.eventCode()).isEqualTo(eventCode);
    assertThat(status.targets()).hasSize(2);
    assertThat(status.targets()).allSatisfy(target -> {
      assertThat(target.requestStatus()).isEqualTo("ACCEPTED");
      assertThat(target.traceId()).isEqualTo("trace-fanout");
      assertThat(target.instanceStatus()).isNull();
    });
    assertThat(triggerService
            .findExternalEventStatus("t1", "billing", "key-fanout-retry")
            .requestId())
        .isEqualTo("delivery-first");
    assertThat(triggerRequestMapper.selectLaunchStatus("t1", "key-fanout-retry", "billing"))
        .isNull();
  }

  @Test
  @DisplayName("并发自动路由同一事件的不同幂等键只创建一组目标请求并都可回查")
  void shouldSerializeConcurrentExternalEventFanOutWithDifferentIdempotencyKeys() throws Exception {
  final String selectCountSql = """
      select count(*) from batch.external_event_delivery where tenant_id='t1' and event_source='billing'\s\
      and event_id='event-concurrent-702'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id='t1' and external_event_source='billing'\s\
      and external_event_id='event-concurrent-702'\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.external_event_delivery_alias where tenant_id='t1'\s\
      and idempotency_key in ('key-concurrent-first', 'key-concurrent-second')\
      """;

    String eventCode = "FANOUT_CONCURRENT_" + UUID.randomUUID().toString().replace("-", "");
    LocalDate bizDate = LocalDate.of(2032, Month.APRIL, 7);
    Long targetA = insertWorkflowDefinition("event-fanout-concurrent-a-" + UUID.randomUUID());
    Long targetB = insertWorkflowDefinition("event-fanout-concurrent-b-" + UUID.randomUUID());
    insertEventExpectation(targetA, eventCode);
    insertEventExpectation(targetB, eventCode);
    ExternalEventLaunchCommand firstCommand = externalEventDeliveryCommand(
        eventCode,
        "event-concurrent-702",
        bizDate,
        "key-concurrent-first",
        "delivery-concurrent-first",
        "trace-first");
    ExternalEventLaunchCommand secondCommand = externalEventDeliveryCommand(
        eventCode,
        "event-concurrent-702",
        bizDate,
        "key-concurrent-second",
        "delivery-concurrent-second",
        "trace-second");

    ExternalEventLaunchResponse first;
    ExternalEventLaunchResponse second;
    try (var executor = Executors.newFixedThreadPool(2)) {
      var start = new CyclicBarrier(2);
      var firstResult = executor.submit(() -> {
        start.await();
        return triggerService.launchExternalEvent(firstCommand);
      });
      var secondResult = executor.submit(() -> {
        start.await();
        return triggerService.launchExternalEvent(secondCommand);
      });
      first = firstResult.get();
      second = secondResult.get();
    }

    assertThat(first).isEqualTo(second);
    assertThat(first.targets())
        .extracting(target -> target.workflowDefinitionId())
        .containsExactly(targetA, targetB);
    assertThat(triggerService.findExternalEventStatus("t1", "billing", "key-concurrent-first"))
        .isEqualTo(
            triggerService.findExternalEventStatus("t1", "billing", "key-concurrent-second"));
    Integer selectedExternalEventDeliveryCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(selectedExternalEventDeliveryCount).isOne();
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedTriggerRequestCount).isEqualTo(2);
    Integer selectedExternalEventDeliveryAliasCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedExternalEventDeliveryAliasCount).isEqualTo(2);
  }

  @Test
  @DisplayName("同来源事件身份不能被不同载荷重用")
  void shouldRejectReusingExternalEventIdentityWithDifferentPayload() {
  final String selectCountSql = """
      select count(*) from batch.external_event_delivery where tenant_id='t1' and event_source='billing'\s\
      and event_id='event-conflict-703'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.external_event_delivery_alias where tenant_id='t1'\s\
      and idempotency_key in ('key-conflict-first', 'key-conflict-second')\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.trigger_request where tenant_id='t1' and external_event_source='billing'\s\
      and external_event_id='event-conflict-703'\
      """;

    String eventCode = "FANOUT_CONFLICT_" + UUID.randomUUID().toString().replace("-", "");
    LocalDate bizDate = LocalDate.of(2032, Month.APRIL, 8);
    Long target = insertWorkflowDefinition("event-fanout-conflict-" + UUID.randomUUID());
    insertEventExpectation(target, eventCode);
    triggerService.launchExternalEvent(externalEventDeliveryCommand(
        eventCode,
        "event-conflict-703",
        bizDate,
        "key-conflict-first",
        "delivery-conflict",
        "trace-conflict"));
    ExternalEventLaunchCommand changedPayload = externalEventDeliveryCommand(
        eventCode,
        "event-conflict-703",
        bizDate,
        "key-conflict-second",
        "delivery-conflict-retry",
        "trace-conflict");
    changedPayload.request().setParams(Map.of("event", "changed"));

    assertThatThrownBy(() -> triggerService.launchExternalEvent(changedPayload))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          assertThat(failure.getMessageKey())
              .isEqualTo("error.trigger.external_event_identity_conflict");
        });
    Integer selectedExternalEventDeliveryCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(selectedExternalEventDeliveryCount).isOne();
    Integer selectedExternalEventDeliveryAliasCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedExternalEventDeliveryAliasCount).isOne();
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedTriggerRequestCount).isOne();
  }

  @Test
  @DisplayName("目标写入中途失败时事件回执、幂等别名、已写目标请求与 Outbox 全部回滚")
  void shouldRollbackExternalEventFanOutAfterTargetWriteFailure() {
  final String selectCountSql = """
      select count(*) from batch.external_event_delivery where tenant_id='t1' and event_source='billing'\s\
      and event_id=?\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.external_event_delivery_alias where tenant_id='t1'\s\
      and idempotency_key='key-rollback'\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.trigger_request where tenant_id='t1' and external_event_source='billing'\s\
      and external_event_id=?\
      """;
  final String selectCountSql4 = """
      select count(*) from batch.trigger_outbox_event where tenant_id='t1' and trace_id=?\
      """;
  final String selectCountSql5 = """
      select count(*) from batch.trigger_outbox_event where tenant_id='t1' and trace_id=?
  """;

    String eventCode = "FANOUT_ROLLBACK_" + UUID.randomUUID().toString().replace("-", "");
    String eventId = "event-rollback-704";
    String traceId = "trace-rollback-" + UUID.randomUUID();
    String triggerName = "test_fail_fanout_" + UUID.randomUUID().toString().replace("-", "");
    String functionName = "test_fail_fanout_fn_" + UUID.randomUUID().toString().replace("-", "");
    LocalDate bizDate = LocalDate.of(2032, Month.APRIL, 9);
    Long targetA = insertWorkflowDefinition("event-fanout-rollback-a-" + UUID.randomUUID());
    Long targetB = insertWorkflowDefinition("event-fanout-rollback-b-" + UUID.randomUUID());
    insertEventExpectation(targetA, eventCode);
    insertEventExpectation(targetB, eventCode);
    ExternalEventLaunchCommand command = externalEventDeliveryCommand(
        eventCode, eventId, bizDate, "key-rollback", "delivery-rollback", traceId);
    final String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $fn$ begin if new.tenant_id = 't1'\s\
        and new.external_event_source = 'billing' and new.external_event_id = '%s'\s\
        and new.workflow_definition_id = %s then if not exists (select 1 from batch.trigger_outbox_event\s\
        where tenant_id = 't1' and trace_id = '%s') then raise exception 'first target outbox missing'; end if;\s\
        raise exception 'injected fanout failure' using errcode = 'P0001'; end if; return new; end; $fn$\
        """.formatted(functionName, eventId, targetB, traceId);
    jdbcTemplate.execute(
        createFunctionSql);
    final String createTriggerSql = """
        create trigger %s before insert on batch.trigger_request for each row execute function batch.%s()\
        """.formatted(triggerName, functionName);
    jdbcTemplate.execute(
        createTriggerSql);

    try {
      assertThatThrownBy(() -> triggerService.launchExternalEvent(command))
          .isInstanceOf(RuntimeException.class)
          .hasStackTraceContaining("injected fanout failure");
    } finally {
      final String dropTriggerSql = """
          drop trigger if exists %s on batch.trigger_request\
          """.formatted(triggerName);
      jdbcTemplate.execute(dropTriggerSql);
      final String dropFunctionSql = """
          drop function if exists batch.%s()\
          """.formatted(functionName);
      jdbcTemplate.execute(dropFunctionSql);
    }

    Integer selectedExternalEventDeliveryCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, eventId);
    assertThat(selectedExternalEventDeliveryCount).isZero();
    Integer selectedExternalEventDeliveryAliasCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedExternalEventDeliveryAliasCount).isZero();
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, eventId);
    assertThat(selectedTriggerRequestCount).isZero();
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql4, Integer.class, traceId);
    assertThat(selectedTriggerOutboxEventCount).isZero();

    ExternalEventLaunchResponse recovered = triggerService.launchExternalEvent(command);
    assertThat(triggerService.launchExternalEvent(command)).isEqualTo(recovered);
    assertThat(recovered.targets())
        .extracting(target -> target.workflowDefinitionId())
        .containsExactly(targetA, targetB);
    Integer selectedExternalEventDeliveryCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, eventId);
    assertThat(selectedExternalEventDeliveryCountRead2).isOne();
    Integer selectedExternalEventDeliveryAliasCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedExternalEventDeliveryAliasCountRead2).isOne();
    Integer selectedTriggerRequestCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, eventId);
    assertThat(selectedTriggerRequestCountRead2).isEqualTo(2);
    Integer selectedTriggerOutboxEventCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql5, Integer.class, traceId);
    assertThat(selectedTriggerOutboxEventCountRead2).isEqualTo(2);
    assertThat(triggerService
            .findExternalEventStatus("t1", "billing", "key-rollback")
            .targets())
        .allSatisfy(target -> {
          assertThat(target.requestStatus()).isEqualTo("ACCEPTED");
          assertThat(target.traceId()).isEqualTo(traceId);
        });
  }

  @Test
  @DisplayName("外部事件回执与幂等别名由 NOBYPASSRLS 角色按 app.tenant_id 隔离")
  void shouldEnforceTenantRlsForExternalEventDeliveryAndAliases() {
  final String selectSetConfigSql = """
      select set_config('app.tenant_id', 't1', true)
  """;
  final String selectCountSql = """
      select count(*) from batch.external_event_delivery
  """;
  final String selectCountSql2 = """
      select count(*) from batch.external_event_delivery_alias
  """;
    final String insertExternalEventDeliverySql = """
        insert into batch.external_event_delivery (tenant_id, event_source, event_id, event_code, biz_date,\s\
        request_id, payload_snapshot, target_workflow_definition_ids, trace_id)\s\
        values ('t2', 'billing', 'rls-forged', 'RLS', date '2032-04-10', 'rls-forged-request', '{}'::jsonb,\s\
        '[1]'::jsonb, 'rls-trace')\
        """;
    final String deleteExternalEventDeliveryAliasSql = """
        delete from batch.external_event_delivery_alias where idempotency_key in (?, ?)
    """;
    final String deleteExternalEventDeliverySql = """
        delete from batch.external_event_delivery where event_id in (?, ?)
    """;

    String role = "event_rls_" + UUID.randomUUID().toString().replace("-", "");
    final String createRoleSql = """
        create role %s nosuperuser nobypassrls\
        """.formatted(role);
    jdbcTemplate.execute(createRoleSql);
    final String grantUsageSql = """
        grant usage on schema batch to %s\
        """.formatted(role);
    jdbcTemplate.execute(grantUsageSql);
    final String grantSelectSql = """
        grant select, insert on batch.external_event_delivery, batch.external_event_delivery_alias to %s\
        """.formatted(role);
    jdbcTemplate.execute(grantSelectSql);
    Long t1DeliveryId = insertEventDeliveryRow("t1", "rls-event-t1", "rls-request-t1");
    Long t2DeliveryId = insertEventDeliveryRow("t2", "rls-event-t2", "rls-request-t2");
    insertEventDeliveryAliasRow("t1", "rls-key-t1", t1DeliveryId);
    insertEventDeliveryAliasRow("t2", "rls-key-t2", t2DeliveryId);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);

    try {
      Integer visibleDeliveryCount = tx.execute(ignored -> {
        final String setLocalSql = """
            set local role %s\
            """.formatted(role);
        jdbcTemplate.execute(setLocalSql);
        jdbcTemplate.queryForObject(selectSetConfigSql, String.class);
        return jdbcTemplate.queryForObject(
            selectCountSql, Integer.class);
      });
      Integer visibleAliasCount = tx.execute(ignored -> {
        final String setLocalSql = """
            set local role %s\
            """.formatted(role);
        jdbcTemplate.execute(setLocalSql);
        jdbcTemplate.queryForObject(selectSetConfigSql, String.class);
        return jdbcTemplate.queryForObject(
            selectCountSql2, Integer.class);
      });
      assertThat(visibleDeliveryCount).isOne();
      assertThat(visibleAliasCount).isOne();
      assertThatThrownBy(() -> tx.execute(ignored -> {
        final String setLocalSql = """
            set local role %s\
            """.formatted(role);
        jdbcTemplate.execute(setLocalSql);
        jdbcTemplate.queryForObject(
                selectSetConfigSql, String.class);
        jdbcTemplate.update(insertExternalEventDeliverySql);
            return null;
          }))
          .isInstanceOf(RuntimeException.class);
    } finally {
      jdbcTemplate.update(
          deleteExternalEventDeliveryAliasSql,
          "rls-key-t1",
          "rls-key-t2");
      jdbcTemplate.update(
          deleteExternalEventDeliverySql,
          "rls-event-t1",
          "rls-event-t2");
      final String dropOwnedSql = """
          drop owned by %s\
          """.formatted(role);
      jdbcTemplate.execute(dropOwnedSql);
      final String dropRoleSql = """
          drop role %s\
          """.formatted(role);
      jdbcTemplate.execute(dropRoleSql);
    }
  }

  @Test
  @DisplayName("不同 Workflow 日历收到同一事件时保留来源事件提供的业务日")
  void shouldUseSourceBusinessDateAcrossDifferentTargetCalendars() {
  final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition\s\
      set calendar_code = case id when ? then 'EVENT_CAL_A' when ? then 'EVENT_CAL_B' end where tenant_id='t1'\s\
      and id in (?, ?)\
      """;
  final String selectBizDateSql = """
      select biz_date from batch.trigger_request where tenant_id='t1' and external_event_source='billing'\s\
      and external_event_id='event-calendar-705' order by workflow_definition_id\
      """;
  final String selectBizDateSql2 = """
      select biz_date from batch.external_event_delivery where tenant_id='t1' and event_source='billing'\s\
      and event_id='event-calendar-705'\
      """;

    String eventCode = "FANOUT_CALENDAR_" + UUID.randomUUID().toString().replace("-", "");
    LocalDate sourceBizDate = LocalDate.of(2032, Month.APRIL, 10);
    Long targetA = insertWorkflowDefinition("event-fanout-calendar-a-" + UUID.randomUUID());
    Long targetB = insertWorkflowDefinition("event-fanout-calendar-b-" + UUID.randomUUID());
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        targetA,
        targetB,
        targetA,
        targetB);
    insertEventExpectation(targetA, eventCode, "EVENT_CAL_A");
    insertEventExpectation(targetB, eventCode, "EVENT_CAL_B");

    ExternalEventLaunchResponse response =
        triggerService.launchExternalEvent(externalEventDeliveryCommand(
            eventCode,
            "event-calendar-705",
            sourceBizDate,
            "key-calendar",
            "delivery-calendar",
            "trace-calendar"));

    assertThat(response.targets())
        .extracting(target -> target.workflowDefinitionId())
        .containsExactly(targetA, targetB);
    List<LocalDate> selectedBizDateValues =
        jdbcTemplate.queryForList(selectBizDateSql, LocalDate.class);
    assertThat(selectedBizDateValues).containsExactly(sourceBizDate, sourceBizDate);
    LocalDate selectedBizDate = jdbcTemplate.queryForObject(selectBizDateSql2, LocalDate.class);
    assertThat(selectedBizDate).isEqualTo(sourceBizDate);
  }

  private Long insertEventDeliveryRow(String tenantId, String eventId, String requestId) {
    final String insertExternalEventDeliverySql = """
        insert into batch.external_event_delivery (tenant_id, event_source, event_id, event_code, biz_date,\s\
        request_id, payload_snapshot, target_workflow_definition_ids, trace_id)\s\
        values (?, 'billing', ?, 'RLS', date '2032-04-10', ?, '{}'::jsonb, '[1]'::jsonb, 'rls-trace')\s\
        returning id\
        """;

    return jdbcTemplate.queryForObject(
        insertExternalEventDeliverySql,
        Long.class,
        tenantId,
        eventId,
        requestId);
  }

  private void insertEventDeliveryAliasRow(
      String tenantId, String idempotencyKey, Long deliveryId) {
        final String insertExternalEventDeliveryAliasSql = """
            insert into batch.external_event_delivery_alias (tenant_id, idempotency_key, delivery_id)\s\
            values (?, ?, ?)\
            """;

        jdbcTemplate.update(
        insertExternalEventDeliveryAliasSql,
        tenantId,
        idempotencyKey,
        deliveryId);
  }

  @Test
  @DisplayName("任一目标业务日已关闭时自动事件扇出整体回滚且不冻结部分目标")
  void shouldRollbackWholeExternalEventFanOutWhenOneTargetDayIsClosed() {
  final String insertBatchDayInstanceSql = """
      insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at, settled_at)\s\
      values ('t1', 'test_cal', ?, 'SETTLED', current_timestamp, current_timestamp)\
      """;
  final String selectCountSql = """
      select count(*) from batch.external_event_delivery where tenant_id='t1' and event_source='billing'\s\
      and event_id='event-701'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id='t1' and external_event_source='billing'\s\
      and external_event_id='event-701'\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.trigger_outbox_event where tenant_id='t1' and request_id='delivery-closed'\
      """;

    String eventCode = "FANOUT_CLOSED_" + UUID.randomUUID().toString().replace("-", "");
    LocalDate bizDate = LocalDate.of(2032, Month.APRIL, 6);
    Long targetA = insertWorkflowDefinition("event-fanout-open-" + UUID.randomUUID());
    Long targetB = insertWorkflowDefinition("event-fanout-closed-" + UUID.randomUUID());
    insertEventExpectation(targetA, eventCode);
    insertEventExpectation(targetB, eventCode);
    jdbcTemplate.update(
        insertBatchDayInstanceSql,
        bizDate);

    assertThatThrownBy(() -> triggerService.launchExternalEvent(externalEventDeliveryCommand(
            eventCode,
            "event-701",
            bizDate,
            "key-fanout-closed",
            "delivery-closed",
            "trace-closed")))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.STATE_CONFLICT);
          assertThat(failure.getMessageKey()).isEqualTo("error.trigger.batch_day_closed");
        });
    Integer selectedExternalEventDeliveryCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(selectedExternalEventDeliveryCount).isZero();
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedTriggerRequestCount).isZero();
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedTriggerOutboxEventCount).isZero();
  }

  private void insertEventExpectation(Long workflowDefinitionId, String eventCode) {
    insertEventExpectation(workflowDefinitionId, eventCode, "test_cal");
  }

  private void insertEventExpectation(
      Long workflowDefinitionId, String eventCode, String calendarCode) {
        final String insertExternalEventExpectationDefinitionSql = """
            insert into batch.external_event_expectation_definition (tenant_id, calendar_code,\s\
            workflow_definition_id, event_code, expected_local_time, enabled)\s\
            values ('t1', ?, ?, ?, '04:00', true)\
            """;

        jdbcTemplate.update(
        insertExternalEventExpectationDefinitionSql,
        calendarCode,
        workflowDefinitionId,
        eventCode);
  }

  private ExternalEventLaunchCommand externalEventDeliveryCommand(
      String eventCode,
      String eventId,
      LocalDate bizDate,
      String idempotencyKey,
      String requestId,
      String traceId) {
    ExternalEventLaunchRequest request = new ExternalEventLaunchRequest();
    request.setTenantId("t1");
    request.setExternalEventCode(eventCode);
    request.setExternalEventId(eventId);
    request.setBizDate(bizDate);
    request.setParams(Map.of("event", "payload"));
    return new ExternalEventLaunchCommand(request, "billing", idempotencyKey, requestId, traceId);
  }

  private TriggerLaunchCommand externalEventCommand(
      Long workflowDefinitionId,
      LocalDate bizDate,
      String source,
      String eventId,
      String idempotencyKey,
      String requestId) {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setWorkflowDefinitionId(workflowDefinitionId);
    request.setBizDate(bizDate);
    request.setTriggerType(TriggerType.EVENT);
    request.setExternalEventCode("PAYMENT_SETTLED");
    request.setExternalEventSource(source);
    request.setExternalEventId(eventId);
    return new TriggerLaunchCommand(request, idempotencyKey, requestId, "trace-" + requestId);
  }

  private LaunchResponse launchExternalEvent(
      Long workflowDefinitionId,
      LocalDate bizDate,
      String sourceEventIdentity,
      String idempotencyKey,
      String requestId) {
    TriggerLaunchRequest request = new TriggerLaunchRequest();
    request.setTenantId("t1");
    request.setWorkflowDefinitionId(workflowDefinitionId);
    request.setBizDate(bizDate);
    request.setTriggerType(TriggerType.EVENT);
    request.setExternalEventCode("PAYMENT_SETTLED");
    String[] identity = sourceEventIdentity.split(":", 2);
    request.setExternalEventSource(identity[0]);
    request.setExternalEventId(identity[1]);
    request.setParams(Map.of("sourceEventIdentity", sourceEventIdentity));
    return triggerService.launch(
        new TriggerLaunchCommand(request, idempotencyKey, requestId, "trace-" + requestId));
  }

  private Long insertWorkflowDefinition(String workflowCode) {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, calendar_code) values ('t1', ?, ?, 'DAG', 1, true, 'test_cal')\s\
        on conflict (tenant_id, workflow_code, version) do update set updated_at = now() returning id\
        """;

    String ownedWorkflowCode = "trigger-service-test-" + workflowCode;
    return jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        ownedWorkflowCode,
        ownedWorkflowCode);
  }

  @Test
  @DisplayName("Workflow 触发保存独立 Workflow 身份且不伪造 JobCode")
  void shouldPersistWorkflowAsExplicitLaunchTarget() {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, calendar_code) values ('t1', ?, ?, 'DAG', 1, true, 'test_cal')\
        """;
    final String selectIdSql = """
        select id from batch.workflow_definition where tenant_id = 't1' and workflow_code = ?
    """;
    final String selectJobCodeSql = """
        select job_code, workflow_definition_id from batch.trigger_request where tenant_id = 't1'\s\
        and request_id = 'req-direct-wf'\
        """;
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id = 't1' and request_id = 'req-direct-wf'\
        """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = 't1' and request_id = 'req-direct-wf'
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = 't1' and id = ?
    """;

    String workflowCode =
        "trigger-service-test-direct-workflow-" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        insertWorkflowDefinitionSql,
        workflowCode,
        workflowCode);
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        workflowCode);
    try {
      TriggerLaunchRequest request = new TriggerLaunchRequest();
      request.setTenantId("t1");
      request.setWorkflowDefinitionId(workflowDefinitionId);
      request.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
      request.setTriggerType(TriggerType.API);

      triggerService.launch(
          new TriggerLaunchCommand(request, "idem-direct-workflow", "req-direct-wf", "trace-wf"));

      Map<String, Object> target = jdbcTemplate.queryForMap(
          selectJobCodeSql);
      assertThat(target.get("job_code")).isNull();
      assertThat(((Number) target.get("workflow_definition_id")).longValue())
          .isEqualTo(workflowDefinitionId);
    } finally {
      jdbcTemplate.update(deleteTriggerOutboxEventSql);
      jdbcTemplate.update(
          deleteTriggerRequestSql);
      jdbcTemplate.update(
          deleteWorkflowDefinitionSql,
          workflowDefinitionId);
    }
  }

  @Test
  @DisplayName("假日定时 fire 被显式记为 SKIPPED_CALENDAR 且不创建 TriggerRequest")
  void shouldPersistSkippedHolidayOccurrenceWithoutCreatingTriggerRequest() throws Exception {
  final String insertTenantSql = """
      insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
  """;
  final String insertBusinessCalendarSql = """
      insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
      holiday_roll_rule, enabled, catch_up_policy) values (?, ?, ?, 'UTC', ?, 'SKIP', true, 'AUTO')\
      """;
  final String insertCalendarHolidaySql = """
            insert into batch.calendar_holiday
              (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)
            select ?, id, ?, 'HOLIDAY', 'scheduled skip integration', 'CALENDAR'
            from batch.business_calendar where tenant_id = ? and calendar_code = ?
            """;
  final String insertCalendarHolidaySql2 = """
          insert into batch.calendar_holiday
            (tenant_id, calendar_id, biz_date, day_type, holiday_name, scope)
          select ?, id, ?, 'HOLIDAY', 'catch-up skip integration', 'CALENDAR'
          from batch.business_calendar where tenant_id = ? and calendar_code = ?
          """;
    final String insertWorkflowDefinitionSql = """
            insert into batch.workflow_definition (
                tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
                schedule_type, schedule_expr, timezone, trigger_mode, schedule_enabled, calendar_code
            ) values (?, ?, ?, 'DAG', 1, true, 'CRON', '0 0 12 * * ?', 'UTC', 'SCHEDULED', true,
                      ?) returning id
            """;
    final String selectBizDateSql = """
        select biz_date, occurrence_status, request_id from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
  final String insertBatchDayInstanceSql = """
      insert into batch.batch_day_instance (tenant_id, calendar_code, biz_date, day_status, open_at, settled_at)\s\
      values (?, ?, ?, 'SETTLED', current_timestamp, current_timestamp)\
      """;
  final String insertScheduledOccurrenceSql = """
      insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
      schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
      values (?, ?, ?, 1, ?, ?, ?, 'PLANNED')\
      """;
  final String selectBizDateSql2 = """
      select biz_date, occurrence_status, last_rejection_reason, request_id from batch.scheduled_occurrence\s\
      where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?\
      """;
  final String selectOccurrenceStatusSql = """
      select occurrence_status, last_rejection_reason, request_id from batch.scheduled_occurrence\s\
      where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?\
      """;
  final String selectTriggerTypeSql = """
      select trigger_type, request_status from batch.trigger_request where tenant_id = ? and request_id = ?\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?
  """;
  final String selectOccurrenceStatusSql2 = """
      select occurrence_status, request_id from batch.scheduled_occurrence where tenant_id = ?\s\
      and workflow_definition_id = ? and scheduled_fire_time = ?\
      """;
  final String selectRequestedBySql = """
      select requested_by, reason, request_id from batch.scheduled_occurrence_catch_up_audit where tenant_id = ?\s\
      and workflow_definition_id = ? and scheduled_fire_time = ?\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.trigger_request where tenant_id = ? and dedup_key = ?
  """;
  final String updateBusinessCalendarSql = """
      update batch.business_calendar set catch_up_policy = 'MANUAL_APPROVAL' where tenant_id = ?\s\
      and calendar_code = ?\
      """;
  final String selectStatusSql = """
      select p.status, p.catch_up_request_id, r.request_status from batch.trigger_misfire_pending p\s\
      join batch.trigger_request r on r.id = p.catch_up_request_id where p.tenant_id = ?\s\
      and p.workflow_definition_id = ? and p.scheduled_fire_time = ?\
      """;
  final String selectCountSql4 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\
      """;
    final String deleteTriggerMisfirePendingSql = """
        delete from batch.trigger_misfire_pending where tenant_id = ?
    """;
    final String deleteScheduledOccurrenceCatchUpAuditSql = """
        delete from batch.scheduled_occurrence_catch_up_audit where tenant_id = ?
    """;
    final String deleteScheduledOccurrenceSql = """
        delete from batch.scheduled_occurrence where tenant_id = ?
    """;
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id = ?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and id = ?
    """;
    final String deleteCalendarHolidaySql = """
        delete from batch.calendar_holiday where tenant_id = ?
    """;
    final String deleteBatchDayCatchupControlSql = """
        delete from batch.batch_day_catchup_control where tenant_id = ?
    """;
    final String deleteBusinessCalendarSql = """
        delete from batch.business_calendar where tenant_id = ?
    """;
    final String deleteTenantSql = """
        delete from batch.tenant where tenant_id = ?
    """;

    String tenantId = "trigger-holiday-" + UUID.randomUUID().toString().replace("-", "");
    String workflowCode = "WF_" + UUID.randomUUID().toString().replace("-", "");
    String calendarCode = "cal_" + UUID.randomUUID().toString().replace("-", "");
    LocalDate holiday = LocalDate.of(2026, Month.MAY, 7);
    Instant fireTime = holiday.atTime(12, 0).toInstant(ZoneOffset.UTC);
    Long workflowDefinitionId = null;
    try {
      jdbcTemplate.update(
          insertTenantSql,
          tenantId,
          tenantId);
      jdbcTemplate.update(
          insertBusinessCalendarSql,
          tenantId,
          calendarCode,
          calendarCode,
          LocalTime.MIDNIGHT);
      jdbcTemplate.update(insertCalendarHolidaySql, tenantId, holiday, tenantId, calendarCode);
      jdbcTemplate.update(insertCalendarHolidaySql2, tenantId, holiday.plusDays(1), tenantId, calendarCode);
      workflowDefinitionId = jdbcTemplate.queryForObject(
          insertWorkflowDefinitionSql, Long.class, tenantId, workflowCode, workflowCode, calendarCode);

      TriggerDescriptor descriptor = new TriggerDescriptor();
      descriptor.setTenantId(tenantId);
      descriptor.setWorkflowDefinitionId(workflowDefinitionId);
      descriptor.setWorkflowCode(workflowCode);
      descriptor.setWorkflowVersion(1);
      descriptor.setCalendarCode(calendarCode);
      descriptor.setTimezone("UTC");
      descriptor.setScheduleType("CRON");
      descriptor.setScheduleExpression("0 0 12 * * ?");
      descriptor.setTriggerMode("SCHEDULED");
      descriptor.setCatchUpPolicy("AUTO");
      descriptor.setEnabled(true);

      LaunchResponse response = triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor, fireTime, TriggerType.SCHEDULED, "req-holiday-skip", "trace-holiday-skip"));

      assertThat(response.instanceNo()).isNull();
      assertThat(response.traceId()).isEqualTo("trace-holiday-skip");
      Map<String, Object> occurrence = jdbcTemplate.queryForMap(
          selectBizDateSql,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(fireTime));
      assertThat(occurrence.get("biz_date")).isNull();
      assertThat(occurrence.get("occurrence_status")).isEqualTo("SKIPPED_CALENDAR");
      assertThat(occurrence.get("request_id")).isNull();
      Integer requestCount = jdbcTemplate.queryForObject(
          selectCountSql,
          Integer.class,
          tenantId,
          "req-holiday-skip");
      assertThat(requestCount).isZero();

      Instant lateFireTime = holiday.plusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC);
      LaunchResponse catchUpResponse =
          triggerService.createPendingCatchUp(new ScheduledTriggerCommand(
              descriptor,
              lateFireTime,
              TriggerType.CATCH_UP,
              "req-holiday-catch-up",
              "trace-catch-up"));
      assertThat(catchUpResponse.instanceNo()).isNull();
      assertThat(catchUpResponse.traceId()).isEqualTo("trace-catch-up");
      Map<String, Object> catchUpOccurrence = jdbcTemplate.queryForMap(
          selectBizDateSql,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(lateFireTime));
      assertThat(catchUpOccurrence.get("biz_date")).isNull();
      assertThat(catchUpOccurrence.get("occurrence_status")).isEqualTo("SKIPPED_CALENDAR");
      assertThat(catchUpOccurrence.get("request_id")).isNull();
      Integer catchUpRequestCount = jdbcTemplate.queryForObject(
          selectCountSql,
          Integer.class,
          tenantId,
          "req-holiday-catch-up");
      assertThat(catchUpRequestCount).isZero();

      LocalDate closedDate = holiday.plusDays(2);
      Instant closedFireTime = closedDate.atTime(12, 0).toInstant(ZoneOffset.UTC);
      jdbcTemplate.update(
          insertBatchDayInstanceSql,
          tenantId,
          calendarCode,
          closedDate);
      jdbcTemplate.update(
          insertScheduledOccurrenceSql,
          tenantId,
          workflowDefinitionId,
          workflowCode,
          calendarCode,
          Timestamp.from(closedFireTime),
          closedDate);
      LaunchResponse closedDayResponse = triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor, closedFireTime, TriggerType.SCHEDULED, "req-closed-day", "trace-closed-day"));
      assertThat(closedDayResponse.instanceNo()).isNull();
      assertThat(closedDayResponse.traceId()).isEqualTo("trace-closed-day");
      Map<String, Object> closedOccurrence = jdbcTemplate.queryForMap(
          selectBizDateSql2,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(closedFireTime));
      assertThat(closedOccurrence.get("biz_date").toString()).isEqualTo(closedDate.toString());
      assertThat(closedOccurrence.get("occurrence_status")).isEqualTo("CLOSED_DAY_REJECTED");
      assertThat(closedOccurrence.get("last_rejection_reason").toString())
          .contains("explicit CATCH_UP or RERUN request required");
      assertThat(closedOccurrence.get("request_id")).isNull();
      Integer closedDayRequestCount = jdbcTemplate.queryForObject(
          selectCountSql,
          Integer.class,
          tenantId,
          "req-closed-day");
      assertThat(closedDayRequestCount).isZero();

      LaunchResponse catchUpAfterRejection =
          triggerService.launchScheduled(new ScheduledTriggerCommand(
              descriptor,
              closedFireTime,
              TriggerType.CATCH_UP,
              "req-closed-catch-up",
              "trace-closed-catch-up"));
      assertThat(catchUpAfterRejection.instanceNo()).isEqualTo("req-closed-catch-up");
      assertThat(catchUpAfterRejection.traceId()).isEqualTo("trace-closed-catch-up");
      Map<String, Object> recoveredOccurrence = jdbcTemplate.queryForMap(
          selectOccurrenceStatusSql,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(closedFireTime));
      assertThat(recoveredOccurrence.get("occurrence_status")).isEqualTo("REQUESTED");
      assertThat(recoveredOccurrence.get("last_rejection_reason").toString())
          .contains("explicit CATCH_UP or RERUN request required");
      assertThat(recoveredOccurrence.get("request_id")).isEqualTo("req-closed-catch-up");
      Map<String, Object> catchUpRequest = jdbcTemplate.queryForMap(
          selectTriggerTypeSql,
          tenantId,
          "req-closed-catch-up");
      assertThat(catchUpRequest.get("trigger_type")).isEqualTo("CATCH_UP");
      assertThat(catchUpRequest.get("request_status")).isEqualTo("ACCEPTED");

      TriggerLaunchRequest rerunRequest = new TriggerLaunchRequest();
      rerunRequest.setTenantId(tenantId);
      rerunRequest.setWorkflowDefinitionId(workflowDefinitionId);
      rerunRequest.setBizDate(closedDate);
      rerunRequest.setTriggerType(TriggerType.RERUN);
      rerunRequest.setParams(Map.of("rerunReason", "approved closed-day rerun"));
      LaunchResponse rerunResponse = triggerService.launch(new TriggerLaunchCommand(
          rerunRequest, "idem-closed-rerun", "req-closed-rerun", "trace-closed-rerun"));
      assertThat(rerunResponse.instanceNo()).isEqualTo("req-closed-rerun");
      assertThat(rerunResponse.traceId()).isEqualTo("trace-closed-rerun");
      Map<String, Object> rerunTriggerRequest = jdbcTemplate.queryForMap(
          selectTriggerTypeSql,
          tenantId,
          "req-closed-rerun");
      assertThat(rerunTriggerRequest.get("trigger_type")).isEqualTo("RERUN");
      assertThat(rerunTriggerRequest.get("request_status")).isEqualTo("ACCEPTED");
      Integer rerunOutboxCount = jdbcTemplate.queryForObject(
          selectCountSql2,
          Integer.class,
          tenantId,
          "req-closed-rerun");
      assertThat(rerunOutboxCount).isEqualTo(1);
      Map<String, Object> occurrenceAfterRerun = jdbcTemplate.queryForMap(
          selectOccurrenceStatusSql2,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(closedFireTime));
      assertThat(occurrenceAfterRerun.get("occurrence_status")).isEqualTo("REQUESTED");
      assertThat(occurrenceAfterRerun.get("request_id")).isEqualTo("req-closed-catch-up");

      Instant operatorFireTime = closedDate.atTime(14, 0).toInstant(ZoneOffset.UTC);
      jdbcTemplate.update(
          insertScheduledOccurrenceSql,
          tenantId,
          workflowDefinitionId,
          workflowCode,
          calendarCode,
          Timestamp.from(operatorFireTime),
          closedDate);
      triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor,
          operatorFireTime,
          TriggerType.SCHEDULED,
          "req-operator-fire",
          "trace-operator-fire"));

      ScheduledOccurrenceCatchUpRequest operatorRequest = new ScheduledOccurrenceCatchUpRequest();
      operatorRequest.setTenantId(tenantId);
      operatorRequest.setWorkflowDefinitionId(workflowDefinitionId);
      operatorRequest.setScheduledFireTime(operatorFireTime);
      operatorRequest.setBizDate(closedDate);
      operatorRequest.setRequestedBy("ops-user");
      operatorRequest.setReason("approved recovery for missing scheduled fire");
      var operatorCommand = new ScheduledOccurrenceCatchUpCommand(
          operatorRequest,
          "operator-occurrence-catch-up",
          "req-operator-catch-up",
          "trace-operator-catch-up");
      ScheduledOccurrenceCatchUpResponse operatorCatchUp;
      ScheduledOccurrenceCatchUpResponse repeatedOperatorCatchUp;
      try (var executor = Executors.newFixedThreadPool(2)) {
        var start = new CyclicBarrier(2);
        var first = executor.submit(() -> {
          start.await();
          return triggerService.catchUpRejectedOccurrence(operatorCommand);
        });
        var second = executor.submit(() -> {
          start.await();
          return triggerService.catchUpRejectedOccurrence(new ScheduledOccurrenceCatchUpCommand(
              operatorRequest,
              "different-retry-key",
              "req-operator-retry",
              "trace-operator-retry"));
        });
        operatorCatchUp = first.get();
        repeatedOperatorCatchUp = second.get();
      }
      assertThat(operatorCatchUp.requestId()).isIn("req-operator-catch-up", "req-operator-retry");
      assertThat(repeatedOperatorCatchUp.requestId()).isEqualTo(operatorCatchUp.requestId());
      assertThat(operatorCatchUp.status()).isEqualTo("ACCEPTED");
      Map<String, Object> operatorOccurrence = jdbcTemplate.queryForMap(
          selectOccurrenceStatusSql2,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(operatorFireTime));
      assertThat(operatorOccurrence.get("occurrence_status")).isEqualTo("REQUESTED");
      assertThat(operatorOccurrence.get("request_id")).isEqualTo(operatorCatchUp.requestId());
      Map<String, Object> actionAudit = jdbcTemplate.queryForMap(
          selectRequestedBySql,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(operatorFireTime));
      assertThat(actionAudit.get("requested_by")).isEqualTo("ops-user");
      assertThat(actionAudit.get("reason"))
          .isEqualTo("approved recovery for missing scheduled fire");
      assertThat(actionAudit.get("request_id")).isEqualTo(operatorCatchUp.requestId());
      Integer operatorRequestCount = jdbcTemplate.queryForObject(
          selectCountSql3,
          Integer.class,
          tenantId,
          "scheduled-occurrence-catch-up:" + workflowDefinitionId + ":"
              + operatorFireTime.toEpochMilli());
      assertThat(operatorRequestCount).isEqualTo(1);

      jdbcTemplate.update(
          updateBusinessCalendarSql,
          tenantId,
          calendarCode);
      Instant approvalFireTime = closedDate.atTime(16, 0).toInstant(ZoneOffset.UTC);
      jdbcTemplate.update(
          insertScheduledOccurrenceSql,
          tenantId,
          workflowDefinitionId,
          workflowCode,
          calendarCode,
          Timestamp.from(approvalFireTime),
          closedDate);
      triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor,
          approvalFireTime,
          TriggerType.SCHEDULED,
          "req-approval-fire",
          "trace-approval-fire"));
      ScheduledOccurrenceCatchUpRequest approvalRequest = new ScheduledOccurrenceCatchUpRequest();
      approvalRequest.setTenantId(tenantId);
      approvalRequest.setWorkflowDefinitionId(workflowDefinitionId);
      approvalRequest.setScheduledFireTime(approvalFireTime);
      approvalRequest.setBizDate(closedDate);
      approvalRequest.setRequestedBy("ops-user");
      approvalRequest.setReason("must follow manual approval policy");
      ScheduledOccurrenceCatchUpResponse pendingCatchUp;
      ScheduledOccurrenceCatchUpResponse repeatedPendingCatchUp;
      try (var executor = Executors.newFixedThreadPool(2)) {
        var start = new CyclicBarrier(2);
        var first = executor.submit(() -> {
          start.await();
          return triggerService.catchUpRejectedOccurrence(new ScheduledOccurrenceCatchUpCommand(
              approvalRequest,
              "approval-policy-key",
              "req-policy-catch-up",
              "trace-policy-catch-up"));
        });
        var second = executor.submit(() -> {
          start.await();
          return triggerService.catchUpRejectedOccurrence(new ScheduledOccurrenceCatchUpCommand(
              approvalRequest,
              "another-approval-retry-key",
              "req-policy-catch-up-retry",
              "trace-policy-catch-up-retry"));
        });
        pendingCatchUp = first.get();
        repeatedPendingCatchUp = second.get();
      }
      assertThat(pendingCatchUp.requestId())
          .isIn("req-policy-catch-up", "req-policy-catch-up-retry");
      assertThat(repeatedPendingCatchUp.requestId()).isEqualTo(pendingCatchUp.requestId());
      assertThat(pendingCatchUp.status()).isEqualTo("PENDING_APPROVAL");
      Map<String, Object> approvalOccurrence = jdbcTemplate.queryForMap(
          selectOccurrenceStatusSql2,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(approvalFireTime));
      assertThat(approvalOccurrence.get("occurrence_status")).isEqualTo("MISFIRE_PENDING");
      assertThat(approvalOccurrence.get("request_id")).isEqualTo(pendingCatchUp.requestId());
      Integer policyRequestCount = jdbcTemplate.queryForObject(
          selectCountSql,
          Integer.class,
          tenantId,
          pendingCatchUp.requestId());
      assertThat(policyRequestCount).isEqualTo(1);
      Map<String, Object> pendingApproval = jdbcTemplate.queryForMap(
          selectStatusSql,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(approvalFireTime));
      assertThat(pendingApproval.get("status")).isEqualTo("PENDING");
      assertThat(pendingApproval.get("request_status")).isEqualTo("ACCEPTED");
      assertThat(pendingApproval.get("catch_up_request_id")).isNotNull();
      Map<String, Object> approvalAudit = jdbcTemplate.queryForMap(
          selectRequestedBySql,
          tenantId,
          workflowDefinitionId,
          Timestamp.from(approvalFireTime));
      assertThat(approvalAudit.get("requested_by")).isEqualTo("ops-user");
      assertThat(approvalAudit.get("reason")).isEqualTo("must follow manual approval policy");
      assertThat(approvalAudit.get("request_id")).isEqualTo(pendingCatchUp.requestId());
      Integer selectedTriggerOutboxEventCount = jdbcTemplate.queryForObject(
          selectCountSql4, Integer.class, tenantId, pendingCatchUp.requestId());
      assertThat(selectedTriggerOutboxEventCount).isZero();
    } finally {
      jdbcTemplate.update(
          deleteTriggerMisfirePendingSql, tenantId);
      jdbcTemplate.update(
          deleteScheduledOccurrenceCatchUpAuditSql, tenantId);
      jdbcTemplate.update(deleteScheduledOccurrenceSql, tenantId);
      jdbcTemplate.update(deleteTriggerOutboxEventSql, tenantId);
      jdbcTemplate.update(deleteTriggerRequestSql, tenantId);
      if (workflowDefinitionId != null) {
        jdbcTemplate.update(
            deleteWorkflowDefinitionSql,
            tenantId,
            workflowDefinitionId);
      }
      jdbcTemplate.update(deleteCalendarHolidaySql, tenantId);
      jdbcTemplate.update(
          deleteBatchDayCatchupControlSql, tenantId);
      jdbcTemplate.update(deleteBusinessCalendarSql, tenantId);
      jdbcTemplate.update(deleteTenantSql, tenantId);
    }
  }

  @Test
  @DisplayName("Trigger 启停只更新 Workflow 调度开关，不改变 Job 执行启用状态或版本")
  void shouldToggleWorkflowScheduleWithoutChangingJobExecutionState() {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone,\s\
        enabled) values (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', true)\
        """;
  final String selectIdSql = """
      select id from batch.job_definition where tenant_id = ? and job_code = ?
  """;
  final String insertWorkflowDefinitionSql = """
      insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
      enabled, single_job_definition_id) values ('system', ?, ?, 'DAG', 1, true, ?)\
      """;
  final String selectVersionSql = """
      select version from batch.job_definition where tenant_id = ? and job_code = ?
  """;
  final String selectVersionSql2 = """
      select version, enabled from batch.job_definition where tenant_id = ? and job_code = ?
  """;
    final String selectScheduleEnabledSql = """
        select schedule_enabled from batch.workflow_definition where tenant_id = 'system' and workflow_code = ?\
        """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ? and job_code = ?
    """;

    String jobCode = "version-" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        insertJobDefinitionSql,
        "system",
        jobCode,
        jobCode);
    Long jobDefinitionId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        "system",
        jobCode);
    String workflowCode = "SINGLE-" + jobCode;
    jdbcTemplate.update(
        insertWorkflowDefinitionSql,
        workflowCode,
        workflowCode,
        jobDefinitionId);
    try {
      Integer initialVersion = jdbcTemplate.queryForObject(
          selectVersionSql,
          Integer.class,
          "system",
          jobCode);

      int updated =
          triggerDefinitionLoader.setWorkflowScheduleEnabled("system", workflowCode, false);

      Map<String, Object> job = jdbcTemplate.queryForMap(
          selectVersionSql2,
          "system",
          jobCode);
      Boolean scheduleEnabled = jdbcTemplate.queryForObject(
          selectScheduleEnabledSql,
          Boolean.class,
          workflowCode);
      assertThat(updated).isEqualTo(1);
      assertThat(job.get("version")).isEqualTo(initialVersion);
      assertThat(job.get("enabled")).isEqualTo(true);
      assertThat(scheduleEnabled).isFalse();
    } finally {
      jdbcTemplate.update(
          deleteWorkflowDefinitionSql,
          "system",
          workflowCode);
      jdbcTemplate.update(
          deleteJobDefinitionSql,
          "system",
          jobCode);
    }
  }

  @Test
  @DisplayName("定时定义从受管 Workflow 读取调度字段，并保留关联 Job 的执行目标")
  void shouldLoadScheduledFieldsFromManagedWorkflow() {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, schedule_expr,\s\
        timezone, trigger_mode, enabled) values (?, ?, ?, 'IMPORT', 'MANUAL', null, 'UTC', 'MANUAL', true)\
        """;
    final String selectIdSql = """
        select id from batch.job_definition where tenant_id = ? and job_code = ?
    """;
    final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
              tenant_id, workflow_code, workflow_name, workflow_type, version, enabled,
              single_job_definition_id, schedule_type, schedule_expr, timezone, trigger_mode,
              calendar_code, depends_on_job_code
          ) values (?, ?, ?, 'DAG', 7, true, ?, 'CRON', '0 15 2 * * ?', 'Asia/Shanghai',
                    'SCHEDULED', 'BUSINESS_DAY', 'UPSTREAM')
          """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and workflow_code = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ? and job_code = ?
    """;

    String jobCode = "workflow-schedule-" + UUID.randomUUID().toString().replace("-", "");
    String workflowCode = "JOB-WF-" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        insertJobDefinitionSql,
        "system",
        jobCode,
        jobCode);
    Long jobDefinitionId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        "system",
        jobCode);
    jdbcTemplate.update(insertWorkflowDefinitionSql, "system", workflowCode, workflowCode, jobDefinitionId);
    try {
      var descriptor = triggerDefinitionLoader.loadByWorkflowCode("system", workflowCode);

      assertThat(descriptor).isNotNull().satisfies(value -> {
        assertThat(value.getJobCode()).isEqualTo(jobCode);
        assertThat(value.getJobDefinitionId()).isEqualTo(jobDefinitionId);
        assertThat(value.getWorkflowCode()).isEqualTo(workflowCode);
        assertThat(value.getWorkflowVersion()).isEqualTo(7);
        assertThat(value.getScheduleType()).isEqualTo("CRON");
        assertThat(value.getScheduleExpression()).isEqualTo("0 15 2 * * ?");
        assertThat(value.getTimezone()).isEqualTo("Asia/Shanghai");
        assertThat(value.getTriggerMode()).isEqualTo("SCHEDULED");
        assertThat(value.getCalendarCode()).isEqualTo("BUSINESS_DAY");
        assertThat(value.getDependsOnJobCode()).isEqualTo("UPSTREAM");
        assertThat(value.isEnabled()).isTrue();
      });
      assertThat(triggerDefinitionLoader.loadAll())
          .anySatisfy(value -> assertThat(value.getWorkflowCode()).isEqualTo(workflowCode));
    } finally {
      jdbcTemplate.update(
          deleteWorkflowDefinitionSql,
          "system",
          workflowCode);
      jdbcTemplate.update(
          deleteJobDefinitionSql,
          "system",
          jobCode);
    }
  }

  @Test
  @DisplayName("组合 Workflow 可按自身身份注册定时描述，不要求关联包装 Job")
  void shouldLoadScheduledComposedWorkflowWithoutJobDefinition() {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, schedule_enabled, schedule_type, schedule_expr, timezone, trigger_mode)\s\
        values ('system', ?, ?, 'DAG', 1, true, true, 'CRON', '0 0 3 * * ?', 'UTC', 'SCHEDULED')\
        """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = 'system' and workflow_code = ?
    """;

    String workflowCode = "composed-schedule-" + UUID.randomUUID().toString().replace("-", "");
    jdbcTemplate.update(
        insertWorkflowDefinitionSql,
        workflowCode,
        workflowCode);
    try {
      var descriptor = triggerDefinitionLoader.loadByWorkflowCode("system", workflowCode);

      assertThat(descriptor).isNotNull().satisfies(value -> {
        assertThat(value.getWorkflowCode()).isEqualTo(workflowCode);
        assertThat(value.getWorkflowDefinitionId()).isNotNull();
        assertThat(value.getJobCode()).isNull();
        assertThat(value.getScheduleType()).isEqualTo("CRON");
        assertThat(value.isEnabled()).isTrue();
      });
      assertThat(triggerDefinitionLoader.loadAll())
          .anySatisfy(value -> assertThat(value.getWorkflowCode()).isEqualTo(workflowCode));

      int updated =
          triggerDefinitionLoader.setWorkflowScheduleEnabled("system", workflowCode, false);
      assertThat(updated).isEqualTo(1);
      assertThat(
              triggerDefinitionLoader.loadByWorkflowCode("system", workflowCode).isEnabled())
          .isFalse();
    } finally {
      jdbcTemplate.update(
          deleteWorkflowDefinitionSql,
          workflowCode);
    }
  }

  @Test
  @DisplayName("Trigger 只加载同日依赖执行早期 readiness gate，Workflow 依赖字段是权威来源")
  void shouldLoadNormalizedDependenciesAndWorkflowScalarWrites() {
  final String insertJobDependencySql = """
      insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order,\s\
      biz_date_alignment) values ('t1', ?, ?, 1, 'PREVIOUS_BIZ_DAY')\
      """;
  final String insertJobDependencySql2 = """
      insert into batch.job_dependency (tenant_id, downstream_job_code, upstream_job_code, dependency_order,\s\
      biz_date_alignment) values ('t1', ?, ?, 2, 'SAME_DAY')\
      """;
    final String updateJobDependencySql = """
        update batch.job_dependency set biz_date_alignment = 'PREVIOUS_BIZ_DAY' where tenant_id = 't1'\s\
        and downstream_job_code = ? and upstream_job_code = ?\
        """;
    final String deleteJobDependencySql = """
        delete from batch.job_dependency where tenant_id = 't1' and downstream_job_code = ?
    """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set depends_on_job_code = ? where tenant_id = 't1'\s\
        and workflow_code = ?\
        """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = 't1' and workflow_code in (?, ?, ?)
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = 't1' and job_code in (?, ?, ?)
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String upstreamA = "UP_A_" + suffix;
    String upstreamB = "UP_B_" + suffix;
    String downstream = "DOWN_" + suffix;
    try {
      insertScheduledDefinition(upstreamA, null);
      insertScheduledDefinition(upstreamB, null);
      String downstreamWorkflow = insertScheduledDefinition(downstream, upstreamA);
      jdbcTemplate.update(
          insertJobDependencySql,
          downstream,
          upstreamB);
      jdbcTemplate.update(
          insertJobDependencySql2,
          downstream,
          upstreamA);

      var descriptor = triggerDefinitionLoader.loadByWorkflowCode("t1", downstreamWorkflow);
      assertThat(descriptor.getDependsOnJobCode()).isEqualTo(upstreamA);
      // Trigger 的早期 readiness gate 只做同业务日检查；跨日依赖留给 Orchestrator。
      assertThat(descriptor.getDependsOnJobCodes()).containsExactly(upstreamA);

      jdbcTemplate.update(
          updateJobDependencySql,
          downstream,
          upstreamA);
      var previousDayOnly = triggerDefinitionLoader.loadByWorkflowCode("t1", downstreamWorkflow);
      assertThat(previousDayOnly.getDependsOnJobCode()).isEqualTo(upstreamA);
      assertThat(previousDayOnly.getDependsOnJobCodes()).isEmpty();

      jdbcTemplate.update(
          deleteJobDependencySql,
          downstream);

      jdbcTemplate.update(
          updateWorkflowDefinitionSql,
          upstreamB,
          downstreamWorkflow);
      var workflowUpdated = triggerDefinitionLoader.loadByWorkflowCode("t1", downstreamWorkflow);
      assertThat(workflowUpdated.getDependsOnJobCode()).isEqualTo(upstreamB);
      assertThat(workflowUpdated.getDependsOnJobCodes()).containsExactly(upstreamB);
    } finally {
      jdbcTemplate.update(
          deleteWorkflowDefinitionSql,
          "TEST-WF-" + downstream,
          "TEST-WF-" + upstreamA,
          "TEST-WF-" + upstreamB);
      jdbcTemplate.update(
          deleteJobDefinitionSql,
          downstream,
          upstreamA,
          upstreamB);
    }
  }

  private String insertScheduledDefinition(String jobCode, String dependsOnJobCode) {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, schedule_expr,\s\
        timezone, trigger_mode, enabled)\s\
        values ('t1', ?, ?, 'IMPORT', 'MANUAL', null, 'Asia/Shanghai', 'MANUAL', true)\
        """;
    final String selectIdSql = """
        select id from batch.job_definition where tenant_id = 't1' and job_code = ?
    """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, single_job_definition_id, schedule_type, schedule_expr, timezone, trigger_mode,\s\
        schedule_enabled, calendar_code, depends_on_job_code)\s\
        values ('t1', ?, ?, 'DAG', 1, true, ?, 'CRON', '0 0 1 * * ?', 'Asia/Shanghai', 'SCHEDULED', true,\s\
        'test_cal', ?)\
        """;

    jdbcTemplate.update(
        insertJobDefinitionSql,
        jobCode,
        jobCode);
    Long jobDefinitionId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        jobCode);
    String workflowCode = "TEST-WF-" + jobCode;
    jdbcTemplate.update(
        insertWorkflowDefinitionSql,
        workflowCode,
        workflowCode,
        jobDefinitionId,
        dependsOnJobCode);
    return workflowCode;
  }

  @Test
  @DisplayName("审批补跑请求同事务写出箱并保持 PROCESSING,未建根运行时不报告 LAUNCHED")
  void shouldApprovePendingCatchUpAndKeepProcessingUntilRootIsCreated() {
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key, request_status,\s\
              trace_id
          ) values (?, ?, ?, ?, ?, ?, ?, ?)
          """;
  final String updateTriggerRequestSql = """
      update batch.trigger_request set launch_payload=cast(? as jsonb) where tenant_id='t1' and request_id=?
  """;
  final String selectRequestStatusSql = """
      select request_status from batch.trigger_request where tenant_id = ? and request_id = ?\
      """;
  final String selectCountSql = """
      select count(*) from batch.workflow_run where tenant_id = ? and trigger_request_id = (select id\s\
      from batch.trigger_request where tenant_id = ? and request_id = ?)\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?\
      """;

    Long workflowDefinitionId = insertWorkflowDefinition("pending-catch-up-" + UUID.randomUUID());
    jdbcTemplate.update(
        insertTriggerRequestSql,
        "t1",
        "pending-001",
        TriggerType.CATCH_UP.code(),
        workflowDefinitionId,
        LocalDate.of(2026, Month.MARCH, 27),
        "dedup-pending-001",
        "ACCEPTED",
        "trace-pending-001");
    LaunchRequest originalLaunchRequest = LaunchRequest.builder()
        .tenantId("t1")
        .workflowDefinitionId(workflowDefinitionId)
        .bizDate(LocalDate.of(2026, Month.MARCH, 27))
        .triggerType(TriggerType.CATCH_UP)
        .requestId("pending-001")
        .traceId("trace-pending-001")
        .params(Map.of("source", "original"))
        .build();
    jdbcTemplate.update(
        updateTriggerRequestSql,
        JsonUtils.toJson(originalLaunchRequest),
        "pending-001");
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setRequestId("pending-001");
    command.setReason("manual approval");

    LaunchResponse response = triggerService.approvePendingCatchUp(command);

    String status = jdbcTemplate.queryForObject(
        selectRequestStatusSql,
        String.class,
        "t1",
        "pending-001");

    // 已完成节点的报告携带该节点自己的 Job Instance。它只是执行细节，
    assertThat(response.instanceNo()).isEqualTo("pending-001");
    assertThat(response.traceId()).isEqualTo("trace-pending-001");
    assertThat(status).isEqualTo("PROCESSING");
    Integer selectedWorkflowRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, "t1", "t1", "pending-001");
    assertThat(selectedWorkflowRunCount).isZero();
    Long outboxCount = jdbcTemplate.queryForObject(
        selectCountSql2,
        Long.class,
        "t1",
        "pending-001");
    assertThat(outboxCount).isEqualTo(1L);
    verify(orchestratorRestClient, never()).post();
  }

  @Test
  @DisplayName("misfire 人工审批后仍使用首次接纳的 fire、区间、日期和 Workflow 版本")
  void shouldPreserveScheduledSnapshotAcrossManualApproval() {
    final String selectWorkflowCodeSql = """
        select workflow_code from batch.workflow_definition where tenant_id='t1' and id=?
    """;
    final String selectBizDateSql = """
        select biz_date from batch.trigger_request where tenant_id='t1' and request_id=?
    """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set version=2, schedule_expr='0 0 9 * * ?' where tenant_id='t1'\s\
        and id=?
    """;
    final String selectPayloadSql = """
        select payload::text from batch.trigger_outbox_event where tenant_id='t1' and request_id=?
    """;

    Long workflowId = insertWorkflowDefinition("approval-snapshot-" + UUID.randomUUID());
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId("t1");
    descriptor.setWorkflowDefinitionId(workflowId);
    descriptor.setWorkflowVersion(1);
    descriptor.setWorkflowCode(jdbcTemplate.queryForObject(
        selectWorkflowCodeSql,
        String.class,
        workflowId));
    descriptor.setCalendarCode("test_cal");
    descriptor.setTimezone("UTC");
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 4 * * ?");
    descriptor.setCatchUpPolicy("MANUAL_APPROVAL");
    Instant originalFire = Instant.parse("2026-03-27T04:00:00Z");
    triggerService.createPendingCatchUp(new ScheduledTriggerCommand(
        descriptor, originalFire, TriggerType.CATCH_UP, "snapshot-request", "snapshot-trace"));
    LocalDate originalBizDate = jdbcTemplate.queryForObject(
        selectBizDateSql,
        LocalDate.class,
        "snapshot-request");

    // 不能作为 Workflow 下一节点的虚构父作业。
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        workflowId);
    PendingCatchUpApprovalCommand command = new PendingCatchUpApprovalCommand();
    command.setTenantId("t1");
    command.setRequestId("snapshot-request");
    command.setReason("approved after schedule changed");
    triggerService.approvePendingCatchUp(command);
    triggerService.approvePendingCatchUp(command);

    String payload = jdbcTemplate.queryForObject(
        selectPayloadSql,
        String.class,
        "snapshot-request");
    LaunchEnvelope envelope = JsonUtils.fromJsonStrict(payload, LaunchEnvelope.class);
    assertThat(envelope.sourceFireTime()).isEqualTo(originalFire);
    assertThat(envelope.launchRequest().bizDate()).isEqualTo(originalBizDate);
    assertThat(envelope.launchRequest().dataIntervalStart()).isEqualTo(originalFire);
    assertThat(envelope.launchRequest().dataIntervalEnd())
        .isEqualTo(originalFire.plusSeconds(86400));
    assertThat(envelope.launchRequest().params())
        .containsEntry("workflowVersion", 1)
        .containsEntry("scheduledAt", originalFire.toString())
        .containsEntry("scheduleExpression", "0 0 4 * * ?")
        .containsEntry("catchUpApproved", true);
  }

  @Test
  @DisplayName("Console 使用 requestId 审批停用 Workflow 的 misfire 时,出箱发布前须完成原待办与 occurrence 审批")
  void shouldApproveMisfireByRequestIdBeforePublishingDisabledWorkflowRecovery() {
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set enabled=false, schedule_enabled=false where tenant_id='t1' and id=?
    """;
    final String selectStatusSql = """
        select p.status from batch.trigger_misfire_pending p\s\
        join batch.trigger_request r on r.tenant_id=p.tenant_id and r.id=p.catch_up_request_id\s\
        where r.tenant_id='t1' and r.request_id=?\
        """;
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id='t1'\s\
        and workflow_definition_id=? and scheduled_fire_time=?\
        """;
    final String selectCountSql = """
        select count(*) from batch.trigger_outbox_event where tenant_id='t1' and request_id=?
    """;

    String code = "approve-request-id-" + UUID.randomUUID();
    Long workflowId = insertWorkflowDefinition(code);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId("t1");
    descriptor.setWorkflowDefinitionId(workflowId);
    descriptor.setWorkflowCode(code);
    descriptor.setWorkflowVersion(1);
    descriptor.setCalendarCode("test_cal");
    descriptor.setTimezone("UTC");
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 4 * * ?");
    descriptor.setCatchUpPolicy("MANUAL_APPROVAL");
    Instant fire = Instant.parse("2026-03-27T04:00:00Z");
    String requestId = "approve-request-id";
    triggerService.createPendingCatchUp(new ScheduledTriggerCommand(
        descriptor, fire, TriggerType.CATCH_UP, requestId, "approve-request-trace"));
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        workflowId);
    PendingCatchUpApprovalCommand approval = new PendingCatchUpApprovalCommand();
    approval.setTenantId("t1");
    approval.setRequestId(requestId);
    approval.setReason("approved through Console requestId contract");
    triggerService.approvePendingCatchUp(approval);

    String selectedStatus = jdbcTemplate.queryForObject(selectStatusSql, String.class, requestId);
    assertThat(selectedStatus).isEqualTo("APPROVED");
    String selectedOccurrenceStatus = jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql, String.class, workflowId, Timestamp.from(fire));
    assertThat(selectedOccurrenceStatus).isEqualTo("REQUESTED");
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, requestId);
    assertThat(selectedTriggerOutboxEventCount).isEqualTo(1);
  }

  @Test
  @DisplayName("暂停日程后到达的普通 Quartz 回调不得接纳请求或写出箱")
  void shouldRejectNewScheduledAdmissionAfterWorkflowSchedulePaused() {
  final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition\s\
      set schedule_type='CRON', schedule_expr='0 0 4 * * ?', timezone='UTC', trigger_mode='SCHEDULED',\s\
      schedule_enabled=false where tenant_id='t1' and id=?\
      """;
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id='t1' and request_id='paused-admission'
  """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id='t1' and request_id='paused-admission'
  """;

    String code = "paused-admission-" + UUID.randomUUID();
    Long workflowId = insertWorkflowDefinition(code);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        workflowId);
    TriggerDescriptor descriptor =
        triggerDefinitionLoader.loadByWorkflowDefinitionId("t1", workflowId);
    LaunchResponse response = triggerService.launchScheduled(new ScheduledTriggerCommand(
        descriptor,
        Instant.parse("2026-03-27T04:00:00Z"),
        TriggerType.SCHEDULED,
        "paused-admission",
        "paused-admission-trace"));

    assertThat(response.instanceNo()).isNull();
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class);
    assertThat(selectedTriggerRequestCount).isZero();
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedTriggerOutboxEventCount).isZero();
  }

  @Test
  @DisplayName("Workflow 版本更新后旧 Quartz fire 不得接纳或改写新版本 occurrence")
  void shouldRejectStaleWorkflowScheduleVersionWithoutSupersedingCurrentPlan() throws Exception {
  final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition\s\
      set schedule_type='CRON', schedule_expr='0 0 4 * * ?', timezone='UTC', trigger_mode='SCHEDULED',\s\
      schedule_enabled=true, calendar_code=null where tenant_id='t1' and id=?\
      """;
  final String updateWorkflowDefinitionSql2 = """
      update batch.workflow_definition set version=2, schedule_expr='0 0 9 * * ?' where tenant_id='t1' and id=?\
      """;
  final String insertScheduledOccurrenceSql = """
      insert into batch.scheduled_occurrence (tenant_id, workflow_definition_id, workflow_code,\s\
      schedule_version, calendar_code, scheduled_fire_time, biz_date, occurrence_status)\s\
      values ('t1', ?, ?, 2, null, ?, ?, 'PLANNED')\
      """;
  final String selectCountSql = """
      select count(*)::int from pg_stat_activity where wait_event_type='Lock' and pid<>pg_backend_pid()\s\
      and query ilike '%%workflow_definition wf%%'\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id='t1' and request_id='stale-schedule-version'\
      """;
  final String selectCountSql3 = """
      select count(*) from batch.trigger_outbox_event where tenant_id='t1'\s\
      and request_id='stale-schedule-version'\
      """;
    final String selectScheduleVersionSql = """
        select schedule_version, biz_date, occurrence_status from batch.scheduled_occurrence\s\
        where tenant_id='t1' and workflow_definition_id=? and scheduled_fire_time=?\
        """;

    String code = "stale-schedule-version-" + UUID.randomUUID().toString().replace("-", "");
    Long workflowId = insertWorkflowDefinition(code);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        workflowId);
    TriggerDescriptor staleDescriptor =
        triggerDefinitionLoader.loadByWorkflowDefinitionId("t1", workflowId);
    Instant fire = Instant.parse("2036-03-27T04:00:00Z");
    LocalDate currentBizDate = LocalDate.of(2036, 3, 27);
    CountDownLatch versionUpdated = new CountDownLatch(1);
    CountDownLatch releaseVersionUpdate = new CountDownLatch(1);
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    LaunchResponse response;
    try (var pool = Executors.newFixedThreadPool(2)) {
      var versionUpdate = pool.submit(() -> tx.execute(ignored -> {
        jdbcTemplate.update(
            updateWorkflowDefinitionSql2,
            workflowId);
        jdbcTemplate.update(
            insertScheduledOccurrenceSql,
            workflowId,
            code,
            Timestamp.from(fire),
            currentBizDate);
        versionUpdated.countDown();
        try {
          assertThat(releaseVersionUpdate.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(failure);
        }
        return true;
      }));
      Future<LaunchResponse> staleFire;
      try {
        assertThat(versionUpdated.await(5, TimeUnit.SECONDS)).isTrue();
        staleFire = pool.submit(() -> triggerService.launchScheduled(new ScheduledTriggerCommand(
            staleDescriptor,
            fire,
            TriggerType.SCHEDULED,
            "stale-schedule-version",
            "stale-trace")));
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
              Integer selectedCount = jdbcTemplate.queryForObject(selectCountSql, Integer.class);
              assertThat(selectedCount).isGreaterThan(0);
            });
        assertThat(staleFire.isDone()).isFalse();
      } finally {
        releaseVersionUpdate.countDown();
      }
      assertThat(versionUpdate.get(10, TimeUnit.SECONDS)).isTrue();
      response = staleFire.get(10, TimeUnit.SECONDS);
    }

    assertThat(response.instanceNo()).isNull();
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class);
    assertThat(selectedTriggerRequestCount).isZero();
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedTriggerOutboxEventCount).isZero();
    Map<String, Object> occurrence = jdbcTemplate.queryForMap(
        selectScheduleVersionSql,
        workflowId,
        Timestamp.from(fire));
    assertThat(occurrence)
        .containsEntry("schedule_version", 2)
        .containsEntry("biz_date", Date.valueOf(currentBizDate))
        .containsEntry("occurrence_status", "PLANNED");
  }

  @Test
  @DisplayName("暂停更新必须等待已接纳请求事务提交,之后普通调度准入为 false")
  void shouldSerializePauseAgainstScheduledAdmissionTransaction() throws Exception {
    final String queryStatementSql = "Lock";

    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String updateWorkflowDefinitionSql = """
        update batch.workflow_definition set schedule_enabled=false where tenant_id='t1' and id=?
    """;
    final String selectWaitEventTypeSql = """
        select wait_event_type from pg_stat_activity where pid=?
    """;

    Long workflowId = insertWorkflowDefinition("pause-fence-" + UUID.randomUUID());
    CountDownLatch admitted = new CountDownLatch(1);
    CountDownLatch releaseAdmission = new CountDownLatch(1);
    CountDownLatch writerStarted = new CountDownLatch(1);
    AtomicInteger writerPid = new AtomicInteger();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var admission = pool.submit(() -> tx.execute(ignored -> {
        assertThat(triggerRequestMapper.lockScheduledWorkflowAdmission(
                "t1", workflowId, Instant.parse("2026-03-27T04:00:00Z"), 1))
            .isTrue();
        admitted.countDown();
        try {
          assertThat(releaseAdmission.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(failure);
        }
        return true;
      }));
      Future<Boolean> pause = null;
      try {
        assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
        pause = pool.submit(() -> tx.execute(ignored -> {
          writerPid.set(jdbcTemplate.queryForObject(selectPgBackendPidSql, Integer.class));
          writerStarted.countDown();
          jdbcTemplate.update(
              updateWorkflowDefinitionSql,
              workflowId);
          return true;
        }));
        assertThat(writerStarted.await(5, TimeUnit.SECONDS)).isTrue();
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
              String selectedWaitEventType =
                  jdbcTemplate.queryForObject(selectWaitEventTypeSql, String.class, writerPid.get());
              assertThat(selectedWaitEventType).isEqualTo(queryStatementSql);
            });
        assertThat(pause.isDone()).isFalse();
      } finally {
        releaseAdmission.countDown();
      }
      assertThat(admission.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(pause.get(10, TimeUnit.SECONDS)).isTrue();
      Boolean admissionAfterPause =
          tx.execute(ignored -> triggerRequestMapper.lockScheduledWorkflowAdmission(
              "t1", workflowId, Instant.parse("2026-03-27T04:00:00Z"), 1));
      assertThat(admissionAfterPause).isFalse();
    }
  }

  @Test
  @DisplayName("readiness 等待后修改日历假日，不重算原 fire 的业务日")
  void shouldKeepObservedReadinessBizDateAfterCalendarHolidayChange() {
    String calendarCode = "wait_freeze_" + UUID.randomUUID().toString().replace("-", "");
    Long workflowId = insertWorkflowDefinition("readiness-calendar-" + UUID.randomUUID());
    String insertCalendarSql = """
        insert into batch.business_calendar (
            tenant_id, calendar_code, calendar_name, timezone, cutoff_time,
            holiday_roll_rule, catch_up_policy, catch_up_max_days, enabled
        ) values ('t1', ?, 'Readiness frozen date', 'UTC', ?, 'PREV_WORKDAY', 'NONE', 0, true)
        """;
    String updateWorkflowSql = """
        update batch.workflow_definition
        set calendar_code = ?, schedule_type = 'CRON', schedule_expr = '0 0 12 * * ?',
            timezone = 'UTC', trigger_mode = 'SCHEDULED', schedule_enabled = true
        where tenant_id = 't1' and id = ?
        """;
    String insertHolidaySql = """
        insert into batch.calendar_holiday (
            tenant_id, calendar_id, biz_date, day_type, holiday_name, scope
        ) select 't1', id, ?, 'HOLIDAY', 'Edited after readiness wait', 'CALENDAR'
        from batch.business_calendar where tenant_id = 't1' and calendar_code = ?
        """;
    String selectOccurrenceBizDateSql = """
        select biz_date from batch.scheduled_occurrence
        where tenant_id = 't1' and workflow_definition_id = ? and scheduled_fire_time = ?
        """;
    String selectRequestBizDateSql = """
        select biz_date from batch.trigger_request
        where tenant_id = 't1' and request_id = ?
        """;
    String selectLaunchPayloadSql = """
        select launch_payload::text from batch.trigger_request
        where tenant_id = 't1' and request_id = ?
        """;
    String deleteHolidaySql = """
        delete from batch.calendar_holiday
        where tenant_id = 't1' and calendar_id in (
            select id from batch.business_calendar where tenant_id = 't1' and calendar_code = ?
        )
        """;
    String deleteCalendarSql = """
        delete from batch.business_calendar where tenant_id = 't1' and calendar_code = ?
        """;
    try {
      jdbcTemplate.update(insertCalendarSql, calendarCode, LocalTime.MIDNIGHT);
      jdbcTemplate.update(updateWorkflowSql, calendarCode, workflowId);
      TriggerDescriptor descriptor =
          triggerDefinitionLoader.loadByWorkflowDefinitionId("t1", workflowId);
      Instant fireTime = Instant.parse("2026-03-27T12:00:00Z");
      LocalDate originalBizDate = LocalDate.of(2026, 3, 27);
      boolean waitingRecorded =
          occurrenceRecorder.recordWaitingReadiness(descriptor, fireTime, originalBizDate);
      assertThat(waitingRecorded).isTrue();
      jdbcTemplate.update(insertHolidaySql, originalBizDate, calendarCode);
      String requestId = "wait-frozen-" + UUID.randomUUID();
      ScheduledTriggerCommand retry = new ScheduledTriggerCommand(
          descriptor, fireTime, TriggerType.SCHEDULED, requestId, "wait-frozen-trace");

      triggerService.launchScheduled(retry);

      Timestamp scheduledTimestamp = Timestamp.from(fireTime);
      LocalDate occurrenceBizDate = jdbcTemplate.queryForObject(
          selectOccurrenceBizDateSql, LocalDate.class, workflowId, scheduledTimestamp);
      LocalDate requestBizDate =
          jdbcTemplate.queryForObject(selectRequestBizDateSql, LocalDate.class, requestId);
      String payload =
          jdbcTemplate.queryForObject(selectLaunchPayloadSql, String.class, requestId);
      LaunchRequest storedLaunch = JsonUtils.fromJsonStrict(payload, LaunchRequest.class);
      assertThat(occurrenceBizDate).isEqualTo(originalBizDate);
      assertThat(requestBizDate).isEqualTo(originalBizDate);
      assertThat(storedLaunch.bizDate()).isEqualTo(originalBizDate);
      assertThat(storedLaunch.dataIntervalStart()).isEqualTo(fireTime);
    } finally {
      cleanUp();
      jdbcTemplate.update(deleteHolidaySql, calendarCode);
      jdbcTemplate.update(deleteCalendarSql, calendarCode);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"READINESS_TIMEOUT", "SKIPPED_CALENDAR", "MISFIRE_SKIPPED", "SUPERSEDED", "CLOSED_DAY_REJECTED"})
  @DisplayName("已终结occurrence的迟到回调不能创建请求、审批待办或Outbox")
  void shouldNotReviveTerminalOccurrence_whenScheduledCallbackArrivesLate(String status) {
    TriggerDescriptor descriptor = seedObservedCallbackDescriptor();
    Instant fire = Instant.parse("2026-03-27T12:00:00Z");
    LocalDate date = LocalDate.of(2026, 3, 27);
    ScheduledOccurrenceStatus terminal = ScheduledOccurrenceStatus.valueOf(status);
    if (terminal == ScheduledOccurrenceStatus.CLOSED_DAY_REJECTED) {
      occurrenceRecorder.recordClosedBatchDayRejection(descriptor, fire, date, "test closed day");
    } else {
      occurrenceRecorder.record(descriptor, fire, date, terminal, null);
    }
    ScheduledTriggerCommand scheduled = new ScheduledTriggerCommand(
        descriptor, fire, TriggerType.SCHEDULED, "late-scheduled-" + UUID.randomUUID(), "late-scheduled");
    ScheduledTriggerCommand pending = new ScheduledTriggerCommand(
        descriptor, fire, TriggerType.CATCH_UP, "late-pending-" + UUID.randomUUID(), "late-pending");

    LaunchResponse scheduledResponse = triggerService.launchScheduled(scheduled);

    assertThat(scheduledResponse.instanceNo()).isNull();
    if (terminal != ScheduledOccurrenceStatus.CLOSED_DAY_REJECTED) {
      LaunchResponse pendingResponse = triggerService.createPendingCatchUp(pending);
      assertThat(pendingResponse.instanceNo()).isNull();
    }
    // CLOSED_DAY_REJECTED 的显式 CATCH_UP 由既有假日/关闭日测试独立证明可用。
    assertNoCallbackSideEffects(descriptor.getWorkflowDefinitionId(), status, List.of(scheduled.requestId(), pending.requestId()));
  }

  @Test
  @DisplayName("原等待在接纳提交前并发超时，请求和Outbox必须全回滚且不复活occurrence")
  void shouldRollbackScheduledAcceptance_whenOccurrenceTimesOutBeforeFinalCas() {
    TriggerDescriptor descriptor = seedObservedCallbackDescriptor();
    Long workflowId = descriptor.getWorkflowDefinitionId();
    Instant fire = Instant.parse("2026-03-27T12:00:00Z");
    LocalDate date = LocalDate.of(2026, 3, 27);
    boolean recorded = occurrenceRecorder.recordWaitingReadiness(descriptor, fire, date);
    assertThat(recorded).isTrue();
    String expireSql = """
        update batch.scheduled_occurrence set occurrence_status = 'READINESS_TIMEOUT'
        where tenant_id = 't1' and workflow_definition_id = ? and scheduled_fire_time = ?
          and occurrence_status = 'WAITING_READINESS'
        """;
    Timestamp scheduledTimestamp = Timestamp.from(fire);
    String requestId = "timeout-race-" + UUID.randomUUID();
    try (var executor = Executors.newSingleThreadExecutor()) {
      doAnswer(invocation -> {
        // 独立连接真实提交超时，保持请求与Outbox仍在原接纳事务中未提交。
        Future<Integer> timeout = executor.submit(() -> jdbcTemplate.update(expireSql, workflowId, scheduledTimestamp));
        Integer changed = timeout.get(5, TimeUnit.SECONDS);
        assertThat(changed).isEqualTo(1);
        String statement = ScheduledOccurrenceMapper.class.getName() + ".upsertObserved";
        Object parameters = invocation.getArgument(0);
        return sqlSessionTemplate.insert(statement, parameters);
      }).when(occurrenceMapper).upsertObserved(argThat(parameters ->
          requestId.equals(parameters.requestId()) && "REQUESTED".equals(parameters.status())));
      ScheduledTriggerCommand command = new ScheduledTriggerCommand(
          descriptor, fire, TriggerType.SCHEDULED, requestId, "timeout-race");

      assertThatThrownBy(() -> triggerService.launchScheduled(command)).isInstanceOf(BizException.class);
    }
    assertNoCallbackSideEffects(workflowId, "READINESS_TIMEOUT", List.of(requestId));
  }

  private TriggerDescriptor seedObservedCallbackDescriptor() {
    Long workflowId = insertWorkflowDefinition("observed-callback-" + UUID.randomUUID());
    String updateScheduleSql = """
        update batch.workflow_definition
        set schedule_type = 'CRON', schedule_expr = '0 0 12 * * ?', timezone = 'UTC',
            trigger_mode = 'SCHEDULED', schedule_enabled = true
        where tenant_id = 't1' and id = ?
        """;
    jdbcTemplate.update(updateScheduleSql, workflowId);
    return triggerDefinitionLoader.loadByWorkflowDefinitionId("t1", workflowId);
  }

  private void assertNoCallbackSideEffects(Long workflowId, String expectedStatus, List<String> requestIds) {
    String selectEffectsSql = """
        select
          (select count(*) from batch.trigger_request where tenant_id = 't1' and workflow_definition_id = ?) requests,
          (select count(*) from batch.trigger_outbox_event where tenant_id = 't1' and request_id in (?, ?)) outboxes,
          (select count(*) from batch.trigger_misfire_pending where tenant_id = 't1' and workflow_definition_id = ?) pending
        """;
    String firstRequestId = requestIds.getFirst();
    String lastRequestId = requestIds.getLast();
    Map<String, Object> effects = jdbcTemplate.queryForMap(selectEffectsSql, workflowId, firstRequestId, lastRequestId, workflowId);
    assertThat(effects.values()).containsOnly(0L);
    String selectStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = 't1' and workflow_definition_id = ?
        """;
    String actualStatus = jdbcTemplate.queryForObject(selectStatusSql, String.class, workflowId);
    assertThat(actualStatus).isEqualTo(expectedStatus);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("暂停后不产生新 readiness 义务,暂停前已观察的原等待仍可恢复接纳")
  void shouldFenceNewReadinessButRetainObservedWaitWhenSchedulePaused(boolean observedBeforePause) {
  final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition\s\
      set schedule_type='CRON', schedule_expr='0 0 4 * * ?', timezone='UTC', trigger_mode='SCHEDULED'\s\
      where tenant_id='t1' and id=?\
      """;
  final String updateWorkflowDefinitionSql2 = """
      update batch.workflow_definition set schedule_enabled=false where tenant_id='t1' and id=?
  """;
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id='t1' and workflow_definition_id=?\s\
        and occurrence_status='WAITING_READINESS'\
        """;

    String code = "readiness-pause-" + UUID.randomUUID();
    Long workflowId = insertWorkflowDefinition(code);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        workflowId);
    TriggerDescriptor descriptor =
        triggerDefinitionLoader.loadByWorkflowDefinitionId("t1", workflowId);
    Instant fire = Instant.parse("2026-03-27T04:00:00Z");
    LocalDate bizDate = LocalDate.of(2026, 3, 27);
    if (observedBeforePause) {
      assertThat(occurrenceRecorder.recordWaitingReadiness(descriptor, fire, bizDate))
          .isTrue();
    }
    jdbcTemplate.update(
        updateWorkflowDefinitionSql2,
        workflowId);
    assertThat(occurrenceRecorder.recordWaitingReadiness(descriptor, fire, bizDate))
        .isEqualTo(observedBeforePause);
    Integer selectedScheduledOccurrenceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowId);
    assertThat(selectedScheduledOccurrenceCount).isEqualTo(observedBeforePause ? 1 : 0);
    if (observedBeforePause) {
      LaunchResponse response = triggerService.launchScheduled(new ScheduledTriggerCommand(
          descriptor, fire, TriggerType.SCHEDULED, "observed-readiness", "readiness-trace"));
      assertThat(response.instanceNo()).isEqualTo("observed-readiness");
    }
  }

  @Test
  @DisplayName("完整停用后的同一人工 misfire 重复恢复仍只保留原请求和待办,不重置审批期限")
  void shouldDeduplicateManualMisfireRecoveryAfterWorkflowDisabled() {
  final String selectExpiresAtSql = """
      select expires_at from batch.trigger_misfire_pending where tenant_id='t1' and workflow_definition_id=?
  """;
  final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set enabled=false where tenant_id='t1' and id=?
  """;
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id='t1' and workflow_definition_id=?
  """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_misfire_pending where tenant_id='t1' and workflow_definition_id=?
  """;
  final String selectCountSql3 = """
      select count(*) from batch.trigger_outbox_event where tenant_id='t1' and request_id='repeat-first'
  """;

    String code = "repeat-pending-" + UUID.randomUUID();
    Long workflowId = insertWorkflowDefinition(code);
    TriggerDescriptor descriptor =
        triggerDefinitionLoader.loadByWorkflowDefinitionId("t1", workflowId);
    descriptor.setScheduleType("CRON");
    descriptor.setScheduleExpression("0 0 4 * * ?");
    descriptor.setCatchUpPolicy("MANUAL_APPROVAL");
    Instant fire = Instant.parse("2026-03-27T04:00:00Z");
    LaunchResponse first = triggerService.createPendingCatchUp(new ScheduledTriggerCommand(
        descriptor, fire, TriggerType.CATCH_UP, "repeat-first", "repeat-original-trace"));
    Timestamp originalExpiry = jdbcTemplate.queryForObject(
        selectExpiresAtSql,
        Timestamp.class,
        workflowId);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        workflowId);
    LaunchResponse repeated = triggerService.createPendingCatchUp(new ScheduledTriggerCommand(
        descriptor, fire, TriggerType.CATCH_UP, "repeat-second", "repeat-other-trace"));
    assertThat(repeated).isEqualTo(first);
    Integer selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowId);
    assertThat(selectedTriggerRequestCount).isEqualTo(1);
    Integer selectedTriggerMisfirePendingCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowId);
    assertThat(selectedTriggerMisfirePendingCount).isEqualTo(1);
    Timestamp selectedExpiresAt =
        jdbcTemplate.queryForObject(selectExpiresAtSql, Timestamp.class, workflowId);
    assertThat(selectedExpiresAt).isEqualTo(originalExpiry);
    Integer selectedTriggerOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class);
    assertThat(selectedTriggerOutboxEventCount).isZero();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {

    @Bean
    Scheduler scheduler() {
      return mock(Scheduler.class);
    }
  }
}
