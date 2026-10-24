package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceCoverage;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional(propagation = Propagation.NEVER)
@DisplayName("预期 Quartz occurrence 台账：唯一身份、周期对账和旧计划替代")
class ScheduledOccurrenceMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  @MockitoSpyBean
  private ScheduledOccurrenceMapper mapper;

  @Autowired
  private TriggerScheduledOccurrencePlanner planner;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private String tenantId;
  private Long workflowDefinitionId;
  private String workflowCode;

  @BeforeEach
  void setUp() {
    tenantId = "occ-it-" + System.nanoTime();
    workflowCode = "wf-" + System.nanoTime();
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')",
        tenantId,
        tenantId);
    workflowDefinitionId = jdbcTemplate.queryForObject(
        "insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name)"
            + " values (?, ?, ?) returning id",
        Long.class,
        tenantId,
        workflowCode,
        workflowCode);
  }

  @Test
  @DisplayName("两秒日程跨写入批次完整物化且保留已观察 fire 的状态与关联")
  void shouldMaterializeAcrossBatchBoundaryWithoutReplacingObservedFire() {
    Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant firstFire = now.plusSeconds(60);
    LocalDate bizDate = firstFire.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    mapper.upsertObserved(
        tenantId,
        workflowDefinitionId,
        workflowCode,
        1,
        null,
        firstFire,
        bizDate,
        "REQUESTED",
        "already-observed");
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setTimezone("UTC");
    org.quartz.Trigger trigger = org.quartz.TriggerBuilder.newTrigger()
        .withIdentity("batch-boundary", "occurrence-test")
        .startAt(java.util.Date.from(firstFire))
        .withSchedule(org.quartz.SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInSeconds(2)
            .withRepeatCount(1000))
        .build();

    assertThat(planner.materialize(descriptor, trigger, now)).isEqualTo(1001);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?",
            Integer.class,
            tenantId,
            workflowDefinitionId))
        .isEqualTo(1001);
    assertThat(status(firstFire)).isEqualTo("REQUESTED");
    assertThat(jdbcTemplate.queryForObject(
            "select request_id from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            String.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(firstFire)))
        .isEqualTo("already-observed");
    assertThat(status(firstFire.plusSeconds(2000))).isEqualTo("PLANNED");
    planner.materialize(descriptor, trigger, now);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?",
            Integer.class,
            tenantId,
            workflowDefinitionId))
        .isEqualTo(1001);
  }

  @Test
  @DisplayName("重复扫描同一计划 fire 幂等，按租户日历业务日统计待处理 occurrence")
  void shouldRepeatedMaterializationIsIdempotentAndCountedByBizDate() {
    Instant first = BatchDateTimeSupport.utcNow()
        .plusSeconds(300)
        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant second = first.plusSeconds(60);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);

    assertThat(insert(first, bizDate)).isEqualTo(1);
    assertThat(insert(first, bizDate)).isZero();
    assertThat(insert(second, bizDate)).isEqualTo(1);

    assertThat(mapper.countBlockingByCalendarAndBizDate(tenantId, "business_day", bizDate))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("新调度版本只替代尚未发生的旧计划，不修改已到期 fire")
  void shouldSupersedeFutureOnlyChangesOldUnfiredOccurrences() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant due = now.minusSeconds(10);
    Instant future = now.plusSeconds(300);
    insert(due, LocalDate.of(2026, 10, 13));
    insert(future, LocalDate.of(2026, 10, 14));

    assertThat(mapper.supersedeFuturePlanned(tenantId, workflowDefinitionId, 2, now))
        .isEqualTo(1);
    assertThat(status(due)).isEqualTo("PLANNED");
    assertThat(status(future)).isEqualTo("SUPERSEDED");
  }

  @Test
  @DisplayName("禁用或删除作业时只关闭未来未发生的计划")
  void shouldSupersedeAllFuturePlansLeavesObservedOccurrencesUntouched() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant due = now.minusSeconds(10);
    Instant future = now.plusSeconds(300);
    Instant requested = now.plusSeconds(360);
    insert(due, LocalDate.of(2026, 10, 13));
    insert(future, LocalDate.of(2026, 10, 14));
    insert(requested, LocalDate.of(2026, 10, 14));
    mapper.upsertObserved(
        tenantId,
        workflowDefinitionId,
        workflowCode,
        1,
        "business_day",
        requested,
        LocalDate.of(2026, 10, 14),
        "REQUESTED",
        "request-2");

    assertThat(mapper.supersedeAllFuturePlanned(tenantId, workflowCode, now)).isEqualTo(1);
    assertThat(status(due)).isEqualTo("PLANNED");
    assertThat(status(future)).isEqualTo("SUPERSEDED");
    assertThat(status(requested)).isEqualTo("REQUESTED");
  }

  @Test
  @DisplayName("实际 fire 状态关联同一计划身份，后续重扫不会把 REQUESTED 倒退为 PLANNED")
  void shouldObservedRequestStateSurvivesSubsequentScheduleReconciliation() {
    Instant fireTime = BatchDateTimeSupport.utcNow()
        .plusSeconds(300)
        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    insert(fireTime, bizDate);

    mapper.upsertObserved(
        tenantId,
        workflowDefinitionId,
        workflowCode,
        1,
        "business_day",
        fireTime,
        bizDate,
        "REQUESTED",
        "request-1");
    assertThat(insert(fireTime, bizDate)).isZero();

    assertThat(status(fireTime)).isEqualTo("REQUESTED");
    assertThat(jdbcTemplate.queryForObject(
            "select request_id from batch.scheduled_occurrence"
                + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            String.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime)))
        .isEqualTo("request-1");
  }

  @Test
  @DisplayName("misfire 恢复按 100 条分页,首批接纳后后续义务不会被饿死")
  void shouldPendingMisfirePageAdvancesAfterFirstPageIsAccepted() {
    Instant firstFireTime = BatchDateTimeSupport.utcNow()
        .minusSeconds(86_400)
        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    for (int index = 0; index < 101; index++) {
      Instant fireTime = firstFireTime.plusSeconds(index);
      assertThat(insert(fireTime, bizDate)).isEqualTo(1);
      assertThat(mapper.transitionStatus(
              tenantId, workflowDefinitionId, fireTime, "PLANNED", "MISFIRE_PENDING", null))
          .isEqualTo(1);
    }

    java.util.List<Instant> firstPage =
        mapper.selectPendingMisfireTimes(tenantId, workflowDefinitionId, 100);
    assertThat(firstPage).hasSize(100);
    assertThat(firstPage.getFirst()).isEqualTo(firstFireTime);
    assertThat(firstPage.getLast()).isEqualTo(firstFireTime.plusSeconds(99));

    for (Instant fireTime : firstPage) {
      assertThat(mapper.transitionStatus(
              tenantId,
              workflowDefinitionId,
              fireTime,
              "MISFIRE_PENDING",
              "REQUESTED",
              "request-" + fireTime.toEpochMilli()))
          .isEqualTo(1);
    }

    assertThat(mapper.selectPendingMisfireTimes(tenantId, workflowDefinitionId, 100))
        .containsExactly(firstFireTime.plusSeconds(100));
  }

  @Test
  @DisplayName("待人工审批的补跑 occurrence 不会被 Quartz 恢复器重新调度")
  void shouldManualApprovalWaitIsNotSelectedAsQuartzRecoveryWork() {
    Instant recoveryFireTime = BatchDateTimeSupport.utcNow()
        .minusSeconds(120)
        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant approvalFireTime = recoveryFireTime.plusSeconds(1);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    for (Instant fireTime : java.util.List.of(recoveryFireTime, approvalFireTime)) {
      assertThat(insert(fireTime, bizDate)).isEqualTo(1);
      assertThat(mapper.transitionStatus(
              tenantId, workflowDefinitionId, fireTime, "PLANNED", "MISFIRE_PENDING", null))
          .isEqualTo(1);
    }
    jdbcTemplate.update(
        "insert into batch.trigger_misfire_pending (tenant_id, workflow_definition_id, job_code,"
            + " scheduled_fire_time, status, expires_at) values (?, ?, ?, ?, 'PENDING', now() +"
            + " interval '1 day')",
        tenantId,
        workflowDefinitionId,
        "JOB_MANUAL",
        Timestamp.from(approvalFireTime));

    assertThat(mapper.selectPendingMisfireTimes(tenantId, workflowDefinitionId, 100))
        .containsExactly(recoveryFireTime);
  }

  @Test
  @DisplayName("连续覆盖区间保存并按当前调度版本读回，供重启后从水位续扫")
  void shouldCoverageIntervalRoundTripsForCurrentScheduleVersion() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant coverageFrom = now.minusSeconds(86400);
    Instant coverageThrough = now.plusSeconds(172800);
    mapper.recordCoverage(
        tenantId, workflowDefinitionId, 4, "business_day", coverageFrom, coverageThrough, now);

    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 4, "business_day"))
        .isEqualTo(new ScheduledOccurrenceCoverage(coverageFrom, coverageThrough));
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 3, "business_day"))
        .isNull();
  }

  @Test
  @DisplayName("租户日历更新使 occurrence coverage 失效，要求从当前时刻重扫未来计划")
  void shouldCalendarConfigurationChangeInvalidatesScheduleCoverage() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    jdbcTemplate.update(
        "insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)"
            + " values (?, 'business_day', 'Business day', 'UTC')",
        tenantId);
    mapper.recordCoverage(
        tenantId,
        workflowDefinitionId,
        4,
        "business_day",
        now.minusSeconds(86_400),
        now.plusSeconds(86_400),
        now);
    assertThat(mapper.isCoverageCurrentForTenantConfiguration(tenantId, workflowDefinitionId))
        .isTrue();

    jdbcTemplate.update(
        "update batch.business_calendar set cutoff_time = '05:00:00'"
            + " where tenant_id = ? and calendar_code = 'business_day'",
        tenantId);

    assertThat(mapper.isCoverageCurrentForTenantConfiguration(tenantId, workflowDefinitionId))
        .isFalse();
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 4, "business_day"))
        .isEqualTo(
            new ScheduledOccurrenceCoverage(now.minusSeconds(86_400), now.plusSeconds(86_400)));
  }

  @Test
  @DisplayName("日历变更重算未来未发生 occurrence 的业务日且保留已到期义务")
  void shouldPlannerRecomputesFutureBizDatesAfterCalendarChangeAndPreservesDueOccurrences() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant futureFire = now.plusSeconds(300);
    LocalDate fireDate = futureFire.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    Instant dueFire = now.minusSeconds(60);
    LocalDate dueBizDate = now.atZone(java.time.ZoneOffset.UTC).toLocalDate().minusDays(1);
    jdbcTemplate.update(
        "insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time)"
            + " values (?, 'business_day', 'Business day', 'UTC', '23:59:59')",
        tenantId);
    mapper.insertPlanned(
        tenantId, workflowDefinitionId, workflowCode, 7, "business_day", dueFire, dueBizDate);

    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(7);
    descriptor.setCalendarCode("business_day");
    descriptor.setTimezone("UTC");
    org.quartz.Trigger quartzTrigger = org.quartz.TriggerBuilder.newTrigger()
        .withIdentity("calendar-refresh", "occurrence-test")
        .startAt(java.util.Date.from(futureFire))
        .withSchedule(org.quartz.SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInSeconds(60)
            .withRepeatCount(0))
        .build();

    assertThat(planner.materialize(descriptor, quartzTrigger, now)).isEqualTo(1);
    assertThat(bizDate(futureFire)).isEqualTo(fireDate.minusDays(1));

    jdbcTemplate.update(
        "update batch.business_calendar set cutoff_time = '00:00:00'"
            + " where tenant_id = ? and calendar_code = 'business_day'",
        tenantId);
    Instant configurationEffectiveAt = now.plusSeconds(60);
    jdbcTemplate.update(
        "update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?",
        Timestamp.from(configurationEffectiveAt),
        tenantId);
    assertThat(planner.materialize(descriptor, quartzTrigger, now.plusSeconds(600)))
        .isEqualTo(1);

    assertThat(bizDate(futureFire)).isEqualTo(fireDate);
    assertThat(bizDate(dueFire)).isEqualTo(dueBizDate);
  }

  @Test
  @DisplayName("重新启用日程后重新接纳未发生的计划 occurrence")
  void shouldReenabledScheduleReactivatesSupersededFutureOccurrence() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(3600);
    jdbcTemplate.update(
        "insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)"
            + " values (?, 'business_day', 'Business day', 'UTC')",
        tenantId);
    jdbcTemplate.update(
        "update batch.workflow_definition set calendar_code = 'business_day', schedule_enabled = true"
            + " where tenant_id = ? and id = ?",
        tenantId,
        workflowDefinitionId);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setCalendarCode("business_day");
    descriptor.setTimezone("UTC");
    org.quartz.Trigger quartzTrigger = org.quartz.TriggerBuilder.newTrigger()
        .withIdentity("reenable-schedule", "occurrence-test")
        .startAt(java.util.Date.from(fireTime))
        .withSchedule(org.quartz.SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInSeconds(60)
            .withRepeatCount(0))
        .build();

    assertThat(planner.materialize(descriptor, quartzTrigger, now)).isEqualTo(1);
    jdbcTemplate.update(
        "update batch.workflow_definition set schedule_enabled = false"
            + " where tenant_id = ? and id = ?",
        tenantId,
        workflowDefinitionId);
    assertThat(planner.supersedeFuturePlans(tenantId, workflowCode, now.plusSeconds(1)))
        .isEqualTo(1);
    assertThat(status(fireTime)).isEqualTo("SUPERSEDED");

    jdbcTemplate.update(
        "update batch.workflow_definition set schedule_enabled = true"
            + " where tenant_id = ? and id = ?",
        tenantId,
        workflowDefinitionId);
    Instant reenabledAt = jdbcTemplate
        .queryForObject(
            "select updated_at from batch.tenant_configuration_revision where tenant_id = ?",
            Timestamp.class,
            tenantId)
        .toInstant();

    assertThat(planner.materialize(descriptor, quartzTrigger, reenabledAt.plusMillis(1)))
        .isEqualTo(1);

    assertThat(status(fireTime)).isEqualTo("PLANNED");
    assertThat(
            mapper.countBlockingByCalendarAndBizDate(tenantId, "business_day", bizDate(fireTime)))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForList(
            "select from_status || '->' || to_status"
                + " from batch.scheduled_occurrence_supersession_history"
                + " where tenant_id = ? and workflow_definition_id = ?"
                + " and scheduled_fire_time = ? order by id",
            String.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime)))
        .containsExactly("PLANNED->SUPERSEDED", "SUPERSEDED->PLANNED");
  }

  @Test
  @DisplayName("旧调度扫描不能降级未来计划版本或回退 coverage 水位")
  void shouldStaleScheduleScanCannotOverwriteNewerVersionOrCoverage() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(3600);
    LocalDate originalBizDate = LocalDate.of(2026, 10, 14);
    mapper.insertPlanned(
        tenantId, workflowDefinitionId, workflowCode, 5, "business_day", fireTime, originalBizDate);

    assertThat(mapper.insertPlanned(
            tenantId,
            workflowDefinitionId,
            workflowCode,
            4,
            "business_day",
            fireTime,
            originalBizDate.minusDays(1)))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
            "select schedule_version from batch.scheduled_occurrence"
                + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            Integer.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime)))
        .isEqualTo(5);
    assertThat(jdbcTemplate.queryForObject(
            "select biz_date from batch.scheduled_occurrence"
                + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            LocalDate.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime)))
        .isEqualTo(originalBizDate);

    Instant firstThrough = now.plusSeconds(7200);
    Instant laterThrough = now.plusSeconds(10800);
    mapper.recordCoverage(
        tenantId, workflowDefinitionId, 5, "business_day", now, laterThrough, now.plusSeconds(1));
    mapper.recordCoverage(
        tenantId, workflowDefinitionId, 4, "business_day", now.minusSeconds(10), firstThrough, now);
    mapper.recordCoverage(
        tenantId, workflowDefinitionId, 5, "business_day", now, firstThrough, now.plusSeconds(2));

    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 5, "business_day"))
        .isEqualTo(new ScheduledOccurrenceCoverage(now, laterThrough));
  }

  @Test
  @DisplayName("旧扫描事务在新版本提交后迟到写入时仍不能回退 occurrence 与 coverage")
  void shouldLateConcurrentOldScanCannotOverwriteCommittedNewVersion() throws Exception {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(3600);
    Instant oldCoverageThrough = now.plusSeconds(7200);
    Instant currentCoverageThrough = now.plusSeconds(10800);
    LocalDate oldBizDate = LocalDate.of(2026, 10, 13);
    LocalDate currentBizDate = LocalDate.of(2026, 10, 14);
    mapper.insertPlanned(
        tenantId, workflowDefinitionId, workflowCode, 4, "business_day", fireTime, oldBizDate);
    mapper.recordCoverage(
        tenantId, workflowDefinitionId, 4, "business_day", now, oldCoverageThrough, now);

    CountDownLatch oldScanReadOldVersion = new CountDownLatch(1);
    CountDownLatch allowOldScanWrite = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> oldScan;
    try {
      oldScan = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
        Integer observedVersion = jdbcTemplate.queryForObject(
            "select schedule_version from batch.scheduled_occurrence"
                + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            Integer.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime));
        assertThat(observedVersion).isEqualTo(4);
        oldScanReadOldVersion.countDown();
        awaitLatch(allowOldScanWrite);
        mapper.insertPlanned(
            tenantId, workflowDefinitionId, workflowCode, 4, "business_day", fireTime, oldBizDate);
        mapper.recordCoverage(
            tenantId, workflowDefinitionId, 4, "business_day", now, oldCoverageThrough, now);
      }));

      assertThat(oldScanReadOldVersion.await(10, TimeUnit.SECONDS)).isTrue();
      mapper.insertPlanned(
          tenantId,
          workflowDefinitionId,
          workflowCode,
          5,
          "business_day",
          fireTime,
          currentBizDate);
      mapper.recordCoverage(
          tenantId,
          workflowDefinitionId,
          5,
          "business_day",
          now,
          currentCoverageThrough,
          now.plusSeconds(1));

      allowOldScanWrite.countDown();
      oldScan.get(10, TimeUnit.SECONDS);
    } finally {
      allowOldScanWrite.countDown();
      executor.shutdownNow();
    }

    assertThat(jdbcTemplate.queryForObject(
            "select schedule_version from batch.scheduled_occurrence"
                + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            Integer.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime)))
        .isEqualTo(5);
    assertThat(jdbcTemplate.queryForObject(
            "select biz_date from batch.scheduled_occurrence"
                + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            LocalDate.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime)))
        .isEqualTo(currentBizDate);
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 5, "business_day"))
        .isEqualTo(new ScheduledOccurrenceCoverage(now, currentCoverageThrough));
  }

  @Test
  @DisplayName("物化账本后 coverage 写入失败时 occurrence 与水位在同一事务回滚")
  void shouldPlannerFailureAfterOccurrenceWriteRollsBackOccurrenceAndCoverage() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(60);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(6);
    descriptor.setTimezone("UTC");
    org.quartz.Trigger quartzTrigger = org.quartz.TriggerBuilder.newTrigger()
        .withIdentity("crash-boundary", "occurrence-test")
        .startAt(java.util.Date.from(fireTime))
        .withSchedule(org.quartz.SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInSeconds(60)
            .withRepeatCount(0))
        .build();
    doThrow(new IllegalStateException("injected coverage persistence failure"))
        .when(mapper)
        .recordCoverage(
            eq(tenantId),
            eq(workflowDefinitionId),
            eq(6),
            isNull(),
            any(Instant.class),
            any(Instant.class),
            any(Instant.class));

    assertThatThrownBy(() -> planner.materialize(descriptor, quartzTrigger, now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected coverage persistence failure");

    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.scheduled_occurrence"
                + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            Long.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime)))
        .isZero();
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 6, null)).isNull();
  }

  @Test
  @DisplayName("日历配置版本在物化期间变化时整批 occurrence 回滚并等待下一轮重扫")
  void shouldPlannerConfigurationRevisionChangeRollsBackPlannedFires() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(60);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(7);
    descriptor.setTimezone("UTC");
    org.quartz.Trigger quartzTrigger = org.quartz.TriggerBuilder.newTrigger()
        .withIdentity("configuration-change", "occurrence-test")
        .startAt(java.util.Date.from(fireTime))
        .withSchedule(org.quartz.SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInSeconds(60)
            .withRepeatCount(0))
        .build();
    doReturn(mapper.selectTenantConfigurationRevision(tenantId) + 1)
        .when(mapper)
        .lockTenantConfigurationRevision(tenantId);

    assertThatThrownBy(() -> planner.materialize(descriptor, quartzTrigger, now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("configuration changed");

    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.scheduled_occurrence"
                + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
            Long.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime)))
        .isZero();
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 7, null)).isNull();
  }

  @Test
  @DisplayName("配置 revision 稳定时成功物化的 coverage 时间晚于配置更新时间")
  void shouldSuccessfulPlannerCoverageIsFreshForCurrentConfigurationRevision() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(60);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(8);
    descriptor.setTimezone("UTC");
    org.quartz.Trigger quartzTrigger = org.quartz.TriggerBuilder.newTrigger()
        .withIdentity("current-configuration", "occurrence-test")
        .startAt(java.util.Date.from(fireTime))
        .withSchedule(org.quartz.SimpleScheduleBuilder.simpleSchedule()
            .withIntervalInSeconds(60)
            .withRepeatCount(0))
        .build();

    assertThat(planner.materialize(descriptor, quartzTrigger, now)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select coverage.last_success_at >= revision.updated_at"
                + " from batch.scheduled_occurrence_coverage coverage"
                + " join batch.tenant_configuration_revision revision"
                + " on revision.tenant_id = coverage.tenant_id"
                + " where coverage.tenant_id = ? and coverage.workflow_definition_id = ?",
            Boolean.class,
            tenantId,
            workflowDefinitionId))
        .isTrue();
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting to resume stale occurrence scan");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting to resume stale occurrence scan", exception);
    }
  }

  private int insert(Instant fireTime, LocalDate bizDate) {
    return mapper.insertPlanned(
        tenantId, workflowDefinitionId, workflowCode, 1, "business_day", fireTime, bizDate);
  }

  private String status(Instant fireTime) {
    return jdbcTemplate.queryForObject(
        "select occurrence_status from batch.scheduled_occurrence"
            + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
  }

  private LocalDate bizDate(Instant fireTime) {
    return jdbcTemplate.queryForObject(
        "select biz_date from batch.scheduled_occurrence"
            + " where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ?",
        LocalDate.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
  }
}
