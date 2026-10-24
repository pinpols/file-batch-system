package io.github.pinpols.batch.trigger.integration;

import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceInsertPlannedParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceRecordCoverageParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceTransitionStatusParam;
import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceUpsertObservedParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.domain.query.UnobservedPlannedOccurrenceQuery;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.service.TriggerScheduledOccurrencePlanner;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceCoverage;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
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

  @Autowired
  private SqlSessionTemplate sqlSessionTemplate;

  private String tenantId;
  private Long workflowDefinitionId;
  private String workflowCode;

  @BeforeEach
  void setUp() {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
        values (?, ?, ?, 'UTC')\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, calendar_code)\s\
        values (?, ?, ?, 'occ-calendar') returning id\
        """;

    tenantId = "occ-it-" + System.nanoTime();
    workflowCode = "wf-" + System.nanoTime();
    jdbcTemplate.update(
        insertTenantSql,
        tenantId,
        tenantId);
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId,
        "occ-calendar",
        "Occurrence test calendar");
    workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        workflowCode,
        workflowCode);
  }

  @Test
  @DisplayName("两秒日程跨写入批次完整物化且保留已观察 fire 的状态与关联")
  void shouldMaterializeAcrossBatchBoundaryWithoutReplacingObservedFire() {
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?
    """;
    final String selectRequestIdSql = """
        select request_id from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?
    """;

    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Instant firstFire = now.plusSeconds(60);
    LocalDate bizDate = firstFire.atZone(ZoneOffset.UTC).toLocalDate();
    ensureRequest("already-observed");
    mapper.upsertObserved(
        ScheduledOccurrenceUpsertObservedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(1)
            .calendarCode(null)
            .scheduledFireTime(firstFire)
            .bizDate(bizDate)
            .status("REQUESTED")
            .requestId("already-observed")
            .build());
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setTimezone("UTC");
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity("batch-boundary", "occurrence-test")
        .startAt(Date.from(firstFire))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(2).withRepeatCount(1000))
        .build();

    assertThat(planner.materialize(descriptor, trigger, now)).isEqualTo(1001);
    Integer selectedScheduledOccurrenceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, workflowDefinitionId);
    assertThat(selectedScheduledOccurrenceCount).isEqualTo(1001);
    assertThat(status(firstFire)).isEqualTo("REQUESTED");
    String selectedRequestId = jdbcTemplate.queryForObject(
        selectRequestIdSql,
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(firstFire));
    assertThat(selectedRequestId).isEqualTo("already-observed");
    assertThat(status(firstFire.plusSeconds(2000))).isEqualTo("PLANNED");
    planner.materialize(descriptor, trigger, now);
    Integer selectedScheduledOccurrenceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, workflowDefinitionId);
    assertThat(selectedScheduledOccurrenceCountRead2).isEqualTo(1001);
  }

  @Test
  @DisplayName("首次物化以首次观察时刻建立可审计 coverage 且不追造此前计划 fire")
  void shouldFirstMaterializationStartCoverageAtFirstObservationWithoutHistoricalBackfill() {
  final String updateTenantConfigurationRevisionSql = """
      update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?
  """;
  final String selectCountSql = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and scheduled_fire_time < ?\
      """;
  final String selectCountSql2 = """
      select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
      and scheduled_fire_time >= ? and occurrence_status = 'PLANNED'\
      """;

    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Instant firstQuartzFire = now.minusSeconds(300);
    jdbcTemplate.update(
        updateTenantConfigurationRevisionSql,
        Timestamp.from(now.minus(Duration.ofDays(30))),
        tenantId);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setTimezone("UTC");
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity("first-observation", "occurrence-test")
        .startAt(Date.from(firstQuartzFire))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(10))
        .build();

    assertThat(planner.materialize(descriptor, trigger, now)).isEqualTo(6);

    ScheduledOccurrenceCoverage coverage =
        mapper.selectCoverage(tenantId, workflowDefinitionId, 1, null);
    assertThat(coverage.coverageFrom()).isEqualTo(now.minusMillis(1));
    assertThat(coverage.coverageThrough()).isEqualTo(now.plus(Duration.ofDays(2)));
    Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, tenantId, workflowDefinitionId, Timestamp.from(now));
    assertThat(selectedScheduledOccurrenceCount).isZero();
    Integer selectedScheduledOccurrenceCountRead2 = jdbcTemplate.queryForObject(
        selectCountSql2, Integer.class, tenantId, workflowDefinitionId, Timestamp.from(now));
    assertThat(selectedScheduledOccurrenceCountRead2).isEqualTo(6);
  }

  @Test
  @DisplayName("Quartz DST 跳时前后 fire instant 持久化为 cutoff 规则对应的业务日")
  void shouldPersistSpringDstFireInstantsWithCalendarBusinessDates() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time)\s\
        values (?, 'dst_calendar', 'DST calendar', 'America/New_York', '02:30:00')\
        """;
    final String updateTenantConfigurationRevisionSql = """
        update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?
    """;
    final String selectScheduledFireTimeSql = """
        select scheduled_fire_time from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? order by scheduled_fire_time\
        """;
    final String selectBizDateSql = """
        select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        order by scheduled_fire_time\
        """;

    Instant now = Instant.parse("2026-03-08T06:59:58Z");
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId);
    jdbcTemplate.update(
        updateTenantConfigurationRevisionSql,
        Timestamp.from(now.minus(Duration.ofDays(1))),
        tenantId);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setCalendarCode("dst_calendar");
    descriptor.setTimezone("UTC");
    Instant firstFire = Instant.parse("2026-03-08T06:59:59Z");
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity("spring-dst-cutoff", "occurrence-test")
        .startAt(Date.from(firstFire))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).withRepeatCount(2))
        .build();

    assertThat(planner.materialize(descriptor, trigger, now)).isEqualTo(3);

    List<Timestamp> selectedScheduledFireTimeValues = jdbcTemplate.queryForList(
        selectScheduledFireTimeSql, Timestamp.class, tenantId, workflowDefinitionId);
    assertThat(selectedScheduledFireTimeValues)
        .extracting(Timestamp::toInstant)
        .containsExactly(
            Instant.parse("2026-03-08T06:59:59Z"),
            Instant.parse("2026-03-08T07:00:00Z"),
            Instant.parse("2026-03-08T07:00:01Z"));
    List<LocalDate> selectedBizDateValues = jdbcTemplate.queryForList(
        selectBizDateSql, LocalDate.class, tenantId, workflowDefinitionId);
    assertThat(selectedBizDateValues)
        .containsExactly(
            LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 8));
  }

  @Test
  @DisplayName("租户日历节假日滚动结果写入计划 occurrence 的 bizDate")
  void shouldPersistHolidayRolledBusinessDateForScheduledOccurrence() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time,\s\
        holiday_roll_rule) values (?, 'holiday_calendar', 'Holiday calendar', 'America/New_York', '02:30:00',\s\
        'NEXT_WORKDAY')\
        """;
    final String selectIdSql = """
        select id from batch.business_calendar where tenant_id = ? and calendar_code = ?
    """;
    final String insertCalendarHolidaySql = """
        insert into batch.calendar_holiday (tenant_id, calendar_id, biz_date, day_type, scope)\s\
        values (?, ?, ?, 'HOLIDAY', 'CALENDAR')\
        """;
    final String updateTenantConfigurationRevisionSql = """
        update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?
    """;
    final String selectBizDateSql = """
        select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant now = Instant.parse("2026-03-08T12:00:00Z");
    Instant fireTime = now.plusSeconds(60);
    LocalDate rawBizDate = LocalDate.of(2026, 3, 8);
    LocalDate rolledBizDate = LocalDate.of(2026, 3, 9);
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId);
    Long calendarId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        tenantId,
        "holiday_calendar");
    jdbcTemplate.update(
        insertCalendarHolidaySql,
        tenantId,
        calendarId,
        rawBizDate);
    jdbcTemplate.update(
        updateTenantConfigurationRevisionSql,
        Timestamp.from(now.minus(Duration.ofDays(1))),
        tenantId);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setCalendarCode("holiday_calendar");
    descriptor.setTimezone("UTC");
    Trigger trigger = TriggerBuilder.newTrigger()
        .withIdentity("holiday-roll", "occurrence-test")
        .startAt(Date.from(fireTime))
        .withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(0))
        .build();

    assertThat(planner.materialize(descriptor, trigger, now)).isEqualTo(1);

    LocalDate selectedBizDate = jdbcTemplate.queryForObject(
        selectBizDateSql,
        LocalDate.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    assertThat(selectedBizDate).isEqualTo(rolledBizDate);
  }

  @Test
  @DisplayName("超出单轮 occurrence 上限时分段推进连续 coverage 并在后续扫描追平")
  void shouldResumeBoundedCoverageAfterScheduleExceedsSinglePassLimit() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
        values (?, 'business_day', 'Business day', 'UTC')\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set cutoff_time = '05:00:00' where tenant_id = ?\s\
        and calendar_code = 'business_day'\
        """;
    final String selectConfigurationRevisionSql = """
        select configuration_revision from batch.scheduled_occurrence_coverage where tenant_id = ?\s\
        and workflow_definition_id = ?\
        """;
    final String selectCountSql = """
        select count(*) from ( select scheduled_fire_time, lag(scheduled_fire_time) over (order by\s\
        scheduled_fire_time) previous_fire_time from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ?) fires where previous_fire_time is not null\s\
        and scheduled_fire_time <> previous_fire_time + interval '1 second'\
        """;
    final String selectMinSql = """
        select min(scheduled_fire_time) from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ?\
        """;
    final String selectMaxSql = """
        select max(scheduled_fire_time) from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ?\
        """;

    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Instant requestedThrough = now.plus(Duration.ofDays(2));
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId);
    long previousRevision = mapper.selectTenantConfigurationRevision(tenantId);
    assertThat(mapper.recordCoverage(
            ScheduledOccurrenceRecordCoverageParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .configurationRevision(previousRevision)
                .scheduleVersion(1)
                .calendarCode("business_day")
                .coverageFrom(now.minus(Duration.ofDays(1)))
                .coverageThrough(requestedThrough)
                .completedAt(now)
                .build()))
        .isEqualTo(1);
    jdbcTemplate.update(
        updateBusinessCalendarSql,
        tenantId);
    Instant firstFire = mapper
        .selectTenantConfigurationUpdatedAt(tenantId)
        .plusSeconds(1)
        .truncatedTo(ChronoUnit.MILLIS);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setTimezone("UTC");
    Trigger denseTrigger = TriggerBuilder.newTrigger()
        .withIdentity("over-capacity", "occurrence-test")
        .startAt(Date.from(firstFire))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(1).repeatForever())
        .build();

    int firstPass = planner.materialize(descriptor, denseTrigger, now);
    assertThat(firstPass).isBetween(1, 100_000);
    ScheduledOccurrenceCoverage partialCoverage =
        mapper.selectCoverage(tenantId, workflowDefinitionId, 1, null);
    assertThat(partialCoverage).isNotNull();
    assertThat(partialCoverage.coverageThrough()).isBefore(requestedThrough);
    assertThat(occurrenceCount()).isEqualTo(firstPass);
    Long selectedConfigurationRevision = jdbcTemplate.queryForObject(
        selectConfigurationRevisionSql, Long.class, tenantId, workflowDefinitionId);
    assertThat(selectedConfigurationRevision)
        .isEqualTo(mapper.selectTenantConfigurationRevision(tenantId));

    int secondPass = planner.materialize(descriptor, denseTrigger, now);
    assertThat(secondPass).isPositive();
    ScheduledOccurrenceCoverage completedCoverage =
        mapper.selectCoverage(tenantId, workflowDefinitionId, 1, null);
    assertThat(completedCoverage.coverageThrough()).isEqualTo(requestedThrough);

    long lastFireOffsetSeconds = Duration.between(firstFire, requestedThrough).getSeconds();
    Instant expectedLastFire = firstFire.plusSeconds(lastFireOffsetSeconds);
    int expectedOccurrences = Math.toIntExact(lastFireOffsetSeconds + 1);
    assertThat(occurrenceCount()).isEqualTo(expectedOccurrences);
    Integer selectedCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, workflowDefinitionId);
    assertThat(selectedCount).isZero();
    assertThat(jdbcTemplate
            .queryForObject(
                selectMinSql,
                Timestamp.class,
                tenantId,
                workflowDefinitionId)
            .toInstant())
        .isEqualTo(firstFire);
    assertThat(jdbcTemplate
            .queryForObject(
                selectMaxSql,
                Timestamp.class,
                tenantId,
                workflowDefinitionId)
            .toInstant())
        .isEqualTo(expectedLastFire);
  }

  private int occurrenceCount() {
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        tenantId,
        workflowDefinitionId);
  }

  @Test
  @DisplayName("重复扫描同一计划 fire 幂等，按租户日历业务日统计待处理 occurrence")
  void shouldRepeatedMaterializationIsIdempotentAndCountedByBizDate() {
    Instant first = BatchDateTimeSupport.utcNow().plusSeconds(300).truncatedTo(ChronoUnit.MILLIS);
    Instant second = first.plusSeconds(60);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);

    assertThat(insert(first, bizDate)).isEqualTo(1);
    assertThat(insert(first, bizDate)).isZero();
    assertThat(insert(second, bizDate)).isEqualTo(1);

    assertThat(mapper.countBlockingByCalendarAndBizDate(tenantId, "business_day", bizDate))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("同一毫秒 fire 并发写入只保留一个 occurrence 身份")
  void shouldConcurrentSameMillisecondFireKeepOneOccurrenceIdentity() throws Exception {
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant fireTime =
        BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS).plusSeconds(60);
    LocalDate bizDate = fireTime.atZone(ZoneOffset.UTC).toLocalDate();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> firstFire = executor.submit(() -> {
        ready.countDown();
        awaitLatch(start);
        return insert(fireTime, bizDate);
      });
      Future<Integer> duplicateFire = executor.submit(() -> {
        ready.countDown();
        awaitLatch(start);
        return insert(fireTime, bizDate);
      });

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      int firstResult = firstFire.get(10, TimeUnit.SECONDS);
      int duplicateResult = duplicateFire.get(10, TimeUnit.SECONDS);
      assertThat(firstResult).isBetween(0, 1);
      assertThat(duplicateResult).isBetween(0, 1);
      assertThat(firstResult + duplicateResult).isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
    }

    Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, tenantId, workflowDefinitionId, Timestamp.from(fireTime));
    assertThat(selectedScheduledOccurrenceCount).isEqualTo(1);
    assertThat(status(fireTime)).isEqualTo("PLANNED");
  }

  @Test
  @DisplayName("readiness 超时是已放弃的终态,不再作为未完成 occurrence 阻塞")
  void shouldNotCountReadinessTimeoutAsBlockingOccurrence() {
    Instant fireTime =
        BatchDateTimeSupport.utcNow().plusSeconds(300).truncatedTo(ChronoUnit.MILLIS);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    mapper.upsertObserved(
        ScheduledOccurrenceUpsertObservedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(1)
            .calendarCode("business_day")
            .scheduledFireTime(fireTime)
            .bizDate(bizDate)
            .status("READINESS_TIMEOUT")
            .requestId(null)
            .build());

    assertThat(mapper.countBlockingByCalendarAndBizDate(tenantId, "business_day", bizDate))
        .isZero();
  }

  @Test
  @DisplayName("新调度版本只替代尚未发生的旧计划，不修改已到期 fire")
  void shouldSupersedeFutureOnlyChangesOldUnfiredOccurrences() {
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
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
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant due = now.minusSeconds(10);
    Instant future = now.plusSeconds(300);
    Instant requested = now.plusSeconds(360);
    insert(due, LocalDate.of(2026, 10, 13));
    insert(future, LocalDate.of(2026, 10, 14));
    insert(requested, LocalDate.of(2026, 10, 14));
    ensureRequest("request-2");
    mapper.upsertObserved(
        ScheduledOccurrenceUpsertObservedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(1)
            .calendarCode("business_day")
            .scheduledFireTime(requested)
            .bizDate(LocalDate.of(2026, 10, 14))
            .status("REQUESTED")
            .requestId("request-2")
            .build());

    assertThat(mapper.supersedeAllFuturePlanned(tenantId, workflowCode, now)).isEqualTo(1);
    assertThat(status(due)).isEqualTo("PLANNED");
    assertThat(status(future)).isEqualTo("SUPERSEDED");
    assertThat(status(requested)).isEqualTo("REQUESTED");
  }

  @Test
  @DisplayName("实际 fire 状态关联同一计划身份，后续重扫不会把 REQUESTED 倒退为 PLANNED")
  void shouldObservedRequestStateSurvivesSubsequentScheduleReconciliation() {
    final String selectRequestIdSql = """
        select request_id from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant fireTime =
        BatchDateTimeSupport.utcNow().plusSeconds(300).truncatedTo(ChronoUnit.MILLIS);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    insert(fireTime, bizDate);

    ensureRequest("request-1");
    mapper.upsertObserved(
        ScheduledOccurrenceUpsertObservedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(1)
            .calendarCode("business_day")
            .scheduledFireTime(fireTime)
            .bizDate(bizDate)
            .status("REQUESTED")
            .requestId("request-1")
            .build());
    assertThat(insert(fireTime, bizDate)).isZero();

    assertThat(status(fireTime)).isEqualTo("REQUESTED");
    String selectedRequestId = jdbcTemplate.queryForObject(
        selectRequestIdSql, String.class, tenantId, workflowDefinitionId, Timestamp.from(fireTime));
    assertThat(selectedRequestId).isEqualTo("request-1");
  }

  @Test
  @DisplayName("同一 fire 已关联请求后重复观察不改写原日历日期版本和请求身份")
  void shouldRepeatedObservedFireKeepOriginalScheduleSnapshotAndRequestIdentity() {
    final String selectWorkflowCodeSql = """
        select workflow_code, schedule_version, calendar_code, biz_date::text as biz_date, occurrence_status,\s\
        request_id from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant fireTime = Instant.parse("2036-06-01T12:00:00Z");
    LocalDate originalBizDate = LocalDate.of(2036, 6, 1);
    ensureRequest("original-request");
    ensureRequest("replacement-request");
    mapper.upsertObserved(
        ScheduledOccurrenceUpsertObservedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(4)
            .calendarCode("original_calendar")
            .scheduledFireTime(fireTime)
            .bizDate(originalBizDate)
            .status("REQUESTED")
            .requestId("original-request")
            .build());

    mapper.upsertObserved(
        ScheduledOccurrenceUpsertObservedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode + "_renamed")
            .scheduleVersion(5)
            .calendarCode("changed_calendar")
            .scheduledFireTime(fireTime)
            .bizDate(originalBizDate.plusDays(1))
            .status("REQUESTED")
            .requestId("replacement-request")
            .build());

    TriggerDescriptor renamedDescriptor = new TriggerDescriptor();
    renamedDescriptor.setTenantId(tenantId);
    renamedDescriptor.setWorkflowDefinitionId(workflowDefinitionId);
    renamedDescriptor.setWorkflowCode(workflowCode + "_renamed");
    renamedDescriptor.setWorkflowVersion(5);
    renamedDescriptor.setTimezone("Pacific/Kiritimati");
    Trigger renamedSchedule = TriggerBuilder.newTrigger()
        .withIdentity("renamed-observed-fire", "occurrence-test")
        .startAt(Date.from(fireTime))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(0))
        .build();
    assertThat(fireTime.atZone(ZoneId.of("Pacific/Kiritimati")).toLocalDate())
        .isEqualTo(originalBizDate.plusDays(1));
    assertThat(planner.materialize(
            renamedDescriptor, renamedSchedule, Instant.parse("2036-05-31T00:00:00Z")))
        .isEqualTo(1);

    Map<String, Object> occurrence = jdbcTemplate.queryForMap(
        selectWorkflowCodeSql,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    assertThat(occurrence)
        .containsEntry("workflow_code", workflowCode)
        .containsEntry("schedule_version", 4)
        .containsEntry("calendar_code", "original_calendar")
        .containsEntry("biz_date", originalBizDate.toString())
        .containsEntry("occurrence_status", "REQUESTED")
        .containsEntry("request_id", "original-request");
  }

  @Test
  @DisplayName("补跑审计的请求或业务日与 occurrence 不一致时拒绝关联")
  void shouldRejectCatchUpAuditWhoseRequestOrBusinessDayDiffersFromOccurrence() {
    Instant fireTime = Instant.parse("2036-06-02T12:00:00Z");
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    String occurrenceRequestId = "occurrence-catch-up-request";
    ensureRequest(occurrenceRequestId);
    ensureRequest("different-catch-up-request");
    assertThat(insert(fireTime, bizDate)).isEqualTo(1);
    assertThat(mapper.transitionStatus(
            ScheduledOccurrenceTransitionStatusParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .scheduledFireTime(fireTime)
                .expectedStatus("PLANNED")
                .newStatus("REQUESTED")
                .requestId(occurrenceRequestId)
                .build()))
        .isEqualTo(1);

    assertThatThrownBy(() -> insertCatchUpAudit(fireTime, bizDate, "different-catch-up-request"))
        .hasMessageContaining("fk_scheduled_occurrence_catch_up_identity");
    assertThatThrownBy(() -> insertCatchUpAudit(fireTime, bizDate.plusDays(1), occurrenceRequestId))
        .hasMessageContaining("fk_scheduled_occurrence_catch_up_identity");
  }

  @Test
  @DisplayName("未观察计划候选按租户/Workflow/版本及严格时间边界分页,排除已接纳义务")
  void shouldSelectUnobservedPlannedFiresWithinIdentityAndExclusiveTimeBound() {
    Instant firstFire =
        BatchDateTimeSupport.utcNow().minusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    for (int index = 0; index < 6; index++) {
      assertThat(insert(firstFire.plusSeconds(index), bizDate)).isEqualTo(1);
    }
    ensureRequest("observed-fire");
    assertThat(mapper.transitionStatus(
            ScheduledOccurrenceTransitionStatusParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .scheduledFireTime(firstFire.plusSeconds(2))
                .expectedStatus("PLANNED")
                .newStatus("REQUESTED")
                .requestId("observed-fire")
                .build()))
        .isEqualTo(1);
    assertThat(mapper.insertPlanned(
            ScheduledOccurrenceInsertPlannedParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .workflowCode(workflowCode)
                .scheduleVersion(2)
                .calendarCode("business_day")
                .scheduledFireTime(firstFire.minusSeconds(1))
                .bizDate(bizDate)
                .build()))
        .isEqualTo(1);
    Instant beforeExclusive = firstFire.plusSeconds(4);

    assertThat(mapper.selectUnobservedPlannedTimesBefore(new UnobservedPlannedOccurrenceQuery(
            tenantId, workflowDefinitionId, 1, beforeExclusive, 2)))
        .containsExactly(firstFire, firstFire.plusSeconds(1));
    assertThat(mapper.selectUnobservedPlannedTimesBefore(new UnobservedPlannedOccurrenceQuery(
            tenantId, workflowDefinitionId, 1, beforeExclusive, 100)))
        .containsExactly(firstFire, firstFire.plusSeconds(1), firstFire.plusSeconds(3));
    assertThat(mapper.selectUnobservedPlannedTimesBefore(new UnobservedPlannedOccurrenceQuery(
            "other-" + tenantId, workflowDefinitionId, 1, beforeExclusive, 100)))
        .isEmpty();
    assertThat(mapper.selectUnobservedPlannedTimesBefore(
            new UnobservedPlannedOccurrenceQuery(tenantId, -1L, 1, beforeExclusive, 100)))
        .isEmpty();
    assertThat(mapper.selectUnobservedPlannedTimesBefore(new UnobservedPlannedOccurrenceQuery(
            tenantId, workflowDefinitionId, 2, beforeExclusive, 100)))
        .containsExactly(firstFire.minusSeconds(1));
    assertThat(status(firstFire.plusSeconds(2))).isEqualTo("REQUESTED");
    assertThat(status(firstFire)).isEqualTo("PLANNED");
  }

  @Test
  @DisplayName("misfire 恢复按 100 条分页,首批接纳后后续义务不会被饿死")
  void shouldPendingMisfirePageAdvancesAfterFirstPageIsAccepted() {
    Instant firstFireTime =
        BatchDateTimeSupport.utcNow().minusSeconds(86_400).truncatedTo(ChronoUnit.SECONDS);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    for (int index = 0; index < 101; index++) {
      Instant fireTime = firstFireTime.plusSeconds(index);
      assertThat(insert(fireTime, bizDate)).isEqualTo(1);
      assertThat(mapper.transitionStatus(
              ScheduledOccurrenceTransitionStatusParam.builder()
                  .tenantId(tenantId)
                  .workflowDefinitionId(workflowDefinitionId)
                  .scheduledFireTime(fireTime)
                  .expectedStatus("PLANNED")
                  .newStatus("MISFIRE_PENDING")
                  .requestId(null)
                  .build()))
          .isEqualTo(1);
    }

    List<Instant> firstPage = mapper.selectPendingMisfireTimes(tenantId, workflowDefinitionId, 100);
    assertThat(firstPage).hasSize(100);
    assertThat(firstPage.getFirst()).isEqualTo(firstFireTime);
    assertThat(firstPage.getLast()).isEqualTo(firstFireTime.plusSeconds(99));

    for (Instant fireTime : firstPage) {
      ensureRequest("request-" + fireTime.toEpochMilli());
      assertThat(mapper.transitionStatus(
              ScheduledOccurrenceTransitionStatusParam.builder()
                  .tenantId(tenantId)
                  .workflowDefinitionId(workflowDefinitionId)
                  .scheduledFireTime(fireTime)
                  .expectedStatus("MISFIRE_PENDING")
                  .newStatus("REQUESTED")
                  .requestId("request-" + fireTime.toEpochMilli())
                  .build()))
          .isEqualTo(1);
    }

    assertThat(mapper.selectPendingMisfireTimes(tenantId, workflowDefinitionId, 100))
        .containsExactly(firstFireTime.plusSeconds(100));
  }

  @Test
  @DisplayName("misfire 并发持续新增时旧积压仍按 fire time 先到先服务")
  void shouldDrainOlderMisfireBacklogWhileNewMisfiresContinueArriving() {
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and occurrence_status = 'MISFIRE_PENDING'\
        """;

    Instant firstBacklogFireTime =
        BatchDateTimeSupport.utcNow().minusSeconds(172_800).truncatedTo(ChronoUnit.SECONDS);
    Instant newArrivalFireTime = firstBacklogFireTime.plusSeconds(10_000);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    int backlogSize = 250;
    int arrivalWaves = 3;
    int arrivalsPerPage = 120;
    for (int index = 0; index < backlogSize; index++) {
      Instant fireTime = firstBacklogFireTime.plusSeconds(index);
      assertThat(insert(fireTime, bizDate)).isEqualTo(1);
      assertThat(mapper.transitionStatus(
              ScheduledOccurrenceTransitionStatusParam.builder()
                  .tenantId(tenantId)
                  .workflowDefinitionId(workflowDefinitionId)
                  .scheduledFireTime(fireTime)
                  .expectedStatus("PLANNED")
                  .newStatus("MISFIRE_PENDING")
                  .requestId(null)
                  .build()))
          .isEqualTo(1);
    }

    CountDownLatch firstArrivalWritten = new CountDownLatch(1);
    CountDownLatch firstBacklogPageConsumed = new CountDownLatch(1);
    AtomicBoolean producerFinished = new AtomicBoolean();
    AtomicInteger acceptedWhileProducerActive = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> producer = executor.submit(() -> {
      try {
        for (int index = 0; index < arrivalWaves * arrivalsPerPage; index++) {
          Instant arrival = newArrivalFireTime.plusSeconds(index);
          assertThat(insert(arrival, bizDate)).isEqualTo(1);
          assertThat(mapper.transitionStatus(
                  ScheduledOccurrenceTransitionStatusParam.builder()
                      .tenantId(tenantId)
                      .workflowDefinitionId(workflowDefinitionId)
                      .scheduledFireTime(arrival)
                      .expectedStatus("PLANNED")
                      .newStatus("MISFIRE_PENDING")
                      .requestId(null)
                      .build()))
              .isEqualTo(1);
          if (index == 0) {
            firstArrivalWritten.countDown();
            awaitLatch(firstBacklogPageConsumed);
          }
          TimeUnit.MILLISECONDS.sleep(2);
        }
      } finally {
        producerFinished.set(true);
      }
      return null;
    });
    Future<List<Instant>> consumer = executor.submit(() -> {
      awaitLatch(firstArrivalWritten);
      List<Instant> accepted = new ArrayList<>();
      while (true) {
        List<Instant> page = mapper.selectPendingMisfireTimes(tenantId, workflowDefinitionId, 100);
        if (page.isEmpty()) {
          if (producerFinished.get()) {
            break;
          }
          TimeUnit.MILLISECONDS.sleep(1);
          continue;
        }
        assertThat(page).isSortedAccordingTo(Comparator.naturalOrder());
        for (Instant fireTime : page) {
          ensureRequest("request-" + fireTime.toEpochMilli());
          assertThat(mapper.transitionStatus(
                  ScheduledOccurrenceTransitionStatusParam.builder()
                      .tenantId(tenantId)
                      .workflowDefinitionId(workflowDefinitionId)
                      .scheduledFireTime(fireTime)
                      .expectedStatus("MISFIRE_PENDING")
                      .newStatus("REQUESTED")
                      .requestId("request-" + fireTime.toEpochMilli())
                      .build()))
              .isEqualTo(1);
          accepted.add(fireTime);
          if (!producerFinished.get()) {
            acceptedWhileProducerActive.incrementAndGet();
          }
        }
        if (accepted.size() >= 100) {
          firstBacklogPageConsumed.countDown();
        }
      }
      return accepted;
    });
    List<Instant> acceptedFireTimes;
    try {
      producer.get(30, TimeUnit.SECONDS);
      acceptedFireTimes = consumer.get(30, TimeUnit.SECONDS);
    } catch (Exception exception) {
      throw new IllegalStateException("concurrent misfire backlog did not drain", exception);
    } finally {
      firstBacklogPageConsumed.countDown();
      executor.shutdownNow();
    }

    assertThat(acceptedFireTimes).hasSize(backlogSize + arrivalWaves * arrivalsPerPage);
    assertThat(acceptedWhileProducerActive.get())
        .as("recovery must make progress while a concurrent producer is still writing")
        .isGreaterThan(0);
    assertThat(acceptedFireTimes.subList(0, backlogSize))
        .containsExactlyElementsOf(IntStream.range(0, backlogSize)
            .mapToObj(firstBacklogFireTime::plusSeconds)
            .toList());
    Integer selectedScheduledOccurrenceCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, workflowDefinitionId);
    assertThat(selectedScheduledOccurrenceCount).isZero();
  }

  @Test
  @DisplayName("待人工审批的补跑 occurrence 不会被 Quartz 恢复器重新调度")
  void shouldManualApprovalWaitIsNotSelectedAsQuartzRecoveryWork() {
    final String insertTriggerMisfirePendingSql = """
        insert into batch.trigger_misfire_pending (tenant_id, workflow_definition_id, job_code,\s\
        scheduled_fire_time, status, expires_at) values (?, ?, ?, ?, 'PENDING', now() + interval '1 day')\
        """;

    Instant recoveryFireTime =
        BatchDateTimeSupport.utcNow().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
    Instant approvalFireTime = recoveryFireTime.plusSeconds(1);
    LocalDate bizDate = LocalDate.of(2026, 10, 14);
    for (Instant fireTime : List.of(recoveryFireTime, approvalFireTime)) {
      assertThat(insert(fireTime, bizDate)).isEqualTo(1);
      assertThat(mapper.transitionStatus(
              ScheduledOccurrenceTransitionStatusParam.builder()
                  .tenantId(tenantId)
                  .workflowDefinitionId(workflowDefinitionId)
                  .scheduledFireTime(fireTime)
                  .expectedStatus("PLANNED")
                  .newStatus("MISFIRE_PENDING")
                  .requestId(null)
                  .build()))
          .isEqualTo(1);
    }
    jdbcTemplate.update(
        insertTriggerMisfirePendingSql,
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
    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant coverageFrom = now.minusSeconds(86400);
    Instant coverageThrough = now.plusSeconds(172800);
    recordCoverage(
        tenantId, workflowDefinitionId, 4, "business_day", coverageFrom, coverageThrough, now);

    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 4, "business_day"))
        .isEqualTo(new ScheduledOccurrenceCoverage(coverageFrom, coverageThrough));
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 3, "business_day"))
        .isNull();
  }

  @Test
  @DisplayName("租户日历更新使 occurrence coverage 失效，即使旧写入时间戳位于未来")
  void shouldCalendarConfigurationChangeInvalidatesScheduleCoverage() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
        values (?, 'business_day', 'Business day', 'UTC')\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set cutoff_time = '05:00:00' where tenant_id = ?\s\
        and calendar_code = 'business_day'\
        """;
    final String selectConfigurationRevisionSql = """
        select configuration_revision from batch.scheduled_occurrence_coverage where tenant_id = ?\s\
        and workflow_definition_id = ?\
        """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId);
    long initialConfigurationRevision = mapper.selectTenantConfigurationRevision(tenantId);
    mapper.recordCoverage(
        ScheduledOccurrenceRecordCoverageParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .configurationRevision(initialConfigurationRevision)
            .scheduleVersion(4)
            .calendarCode("business_day")
            .coverageFrom(now.minusSeconds(86_400))
            .coverageThrough(now.plusSeconds(86_400))
            .completedAt(now.plusSeconds(3_600))
            .build());
    assertThat(mapper.isCoverageCurrentForTenantConfiguration(tenantId, workflowDefinitionId))
        .isTrue();

    jdbcTemplate.update(
        updateBusinessCalendarSql,
        tenantId);

    assertThat(mapper.isCoverageCurrentForTenantConfiguration(tenantId, workflowDefinitionId))
        .isFalse();
    Long selectedConfigurationRevision = jdbcTemplate.queryForObject(
        selectConfigurationRevisionSql, Long.class, tenantId, workflowDefinitionId);
    assertThat(selectedConfigurationRevision).isEqualTo(initialConfigurationRevision);
    long updatedConfigurationRevision = mapper.selectTenantConfigurationRevision(tenantId);
    assertThat(recordCoverage(
            tenantId,
            workflowDefinitionId,
            4,
            "business_day",
            now.minusSeconds(86_400),
            now.plusSeconds(86_400),
            now))
        .isEqualTo(1);
    assertThat(mapper.recordCoverage(
            ScheduledOccurrenceRecordCoverageParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .configurationRevision(initialConfigurationRevision)
                .scheduleVersion(4)
                .calendarCode("business_day")
                .coverageFrom(now.minusSeconds(86_400))
                .coverageThrough(now.plusSeconds(172_800))
                .completedAt(now.plusSeconds(7_200))
                .build()))
        .isZero();
    Long selectedConfigurationRevisionRead2 = jdbcTemplate.queryForObject(
        selectConfigurationRevisionSql, Long.class, tenantId, workflowDefinitionId);
    assertThat(selectedConfigurationRevisionRead2).isEqualTo(updatedConfigurationRevision);
    assertThat(mapper.isCoverageCurrentForTenantConfiguration(tenantId, workflowDefinitionId))
        .isTrue();
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 4, "business_day"))
        .isEqualTo(
            new ScheduledOccurrenceCoverage(now.minusSeconds(86_400), now.plusSeconds(86_400)));
  }

  @Test
  @DisplayName("日历变更重算未来未发生 occurrence 的业务日且保留已到期义务")
  void shouldPlannerRecomputesFutureBizDatesAfterCalendarChangeAndPreservesDueOccurrences() {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone, cutoff_time)\s\
        values (?, 'business_day', 'Business day', 'UTC', '23:59:59')\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set cutoff_time = '00:00:00' where tenant_id = ?\s\
        and calendar_code = 'business_day'\
        """;
    final String updateTenantConfigurationRevisionSql = """
        update batch.tenant_configuration_revision set updated_at = ? where tenant_id = ?
    """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant futureFire = now.plusSeconds(300);
    LocalDate fireDate = futureFire.atZone(ZoneOffset.UTC).toLocalDate();
    Instant dueFire = now.minusSeconds(60);
    LocalDate dueBizDate = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId);
    mapper.insertPlanned(
        ScheduledOccurrenceInsertPlannedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(7)
            .calendarCode("business_day")
            .scheduledFireTime(dueFire)
            .bizDate(dueBizDate)
            .build());

    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(7);
    descriptor.setCalendarCode("business_day");
    descriptor.setTimezone("UTC");
    Trigger quartzTrigger = TriggerBuilder.newTrigger()
        .withIdentity("calendar-refresh", "occurrence-test")
        .startAt(Date.from(futureFire))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(0))
        .build();

    assertThat(planner.materialize(descriptor, quartzTrigger, now)).isEqualTo(1);
    assertThat(bizDate(futureFire)).isEqualTo(fireDate.minusDays(1));

    jdbcTemplate.update(
        updateBusinessCalendarSql,
        tenantId);
    Instant configurationEffectiveAt = now.plusSeconds(60);
    jdbcTemplate.update(
        updateTenantConfigurationRevisionSql,
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
  final String insertBusinessCalendarSql = """
      insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
      values (?, 'business_day', 'Business day', 'UTC')\
      """;
  final String updateWorkflowDefinitionSql = """
      update batch.workflow_definition set calendar_code = 'business_day', schedule_enabled = true\s\
      where tenant_id = ? and id = ?\
      """;
  final String updateWorkflowDefinitionSql2 = """
      update batch.workflow_definition set schedule_enabled = false where tenant_id = ? and id = ?\
      """;
  final String updateWorkflowDefinitionSql3 = """
      update batch.workflow_definition set schedule_enabled = true where tenant_id = ? and id = ?\
      """;
    final String selectUpdatedAtSql = """
        select updated_at from batch.tenant_configuration_revision where tenant_id = ?
    """;
    final String selectFromStatusSql = """
        select from_status || '->' || to_status from batch.scheduled_occurrence_supersession_history\s\
        where tenant_id = ? and workflow_definition_id = ? and scheduled_fire_time = ? order by id\
        """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(3600);
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql,
        tenantId,
        workflowDefinitionId);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(1);
    descriptor.setCalendarCode("business_day");
    descriptor.setTimezone("UTC");
    Trigger quartzTrigger = TriggerBuilder.newTrigger()
        .withIdentity("reenable-schedule", "occurrence-test")
        .startAt(Date.from(fireTime))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(0))
        .build();

    assertThat(planner.materialize(descriptor, quartzTrigger, now)).isEqualTo(1);
    jdbcTemplate.update(
        updateWorkflowDefinitionSql2,
        tenantId,
        workflowDefinitionId);
    assertThat(planner.supersedeFuturePlans(tenantId, workflowCode, now.plusSeconds(1)))
        .isEqualTo(1);
    assertThat(status(fireTime)).isEqualTo("SUPERSEDED");

    jdbcTemplate.update(
        updateWorkflowDefinitionSql3,
        tenantId,
        workflowDefinitionId);
    Instant reenabledAt = jdbcTemplate
        .queryForObject(
            selectUpdatedAtSql,
            Timestamp.class,
            tenantId)
        .toInstant();

    assertThat(planner.materialize(descriptor, quartzTrigger, reenabledAt.plusMillis(1)))
        .isEqualTo(1);

    assertThat(status(fireTime)).isEqualTo("PLANNED");
    assertThat(
            mapper.countBlockingByCalendarAndBizDate(tenantId, "business_day", bizDate(fireTime)))
        .isEqualTo(1);
    List<String> selectedFromStatusValues = jdbcTemplate.queryForList(
        selectFromStatusSql,
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    assertThat(selectedFromStatusValues)
        .containsExactly("PLANNED->SUPERSEDED", "SUPERSEDED->PLANNED");
  }

  @Test
  @DisplayName("旧调度扫描不能降级未来计划版本或回退 coverage 水位")
  void shouldStaleScheduleScanCannotOverwriteNewerVersionOrCoverage() {
    final String selectScheduleVersionSql = """
        select schedule_version from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectBizDateSql = """
        select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(3600);
    LocalDate originalBizDate = LocalDate.of(2026, 10, 14);
    mapper.insertPlanned(
        ScheduledOccurrenceInsertPlannedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(5)
            .calendarCode("business_day")
            .scheduledFireTime(fireTime)
            .bizDate(originalBizDate)
            .build());

    assertThat(mapper.insertPlanned(
            ScheduledOccurrenceInsertPlannedParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .workflowCode(workflowCode)
                .scheduleVersion(4)
                .calendarCode("business_day")
                .scheduledFireTime(fireTime)
                .bizDate(originalBizDate.minusDays(1))
                .build()))
        .isZero();
    Integer selectedScheduleVersion = jdbcTemplate.queryForObject(
        selectScheduleVersionSql,
        Integer.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    assertThat(selectedScheduleVersion).isEqualTo(5);
    LocalDate selectedBizDate = jdbcTemplate.queryForObject(
        selectBizDateSql,
        LocalDate.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    assertThat(selectedBizDate).isEqualTo(originalBizDate);

    Instant firstThrough = now.plusSeconds(7200);
    Instant laterThrough = now.plusSeconds(10800);
    recordCoverage(
        tenantId, workflowDefinitionId, 5, "business_day", now, laterThrough, now.plusSeconds(1));
    recordCoverage(
        tenantId, workflowDefinitionId, 4, "business_day", now.minusSeconds(10), firstThrough, now);
    recordCoverage(
        tenantId, workflowDefinitionId, 5, "business_day", now, firstThrough, now.plusSeconds(2));

    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 5, "business_day"))
        .isEqualTo(new ScheduledOccurrenceCoverage(now, laterThrough));
  }

  @Test
  @DisplayName("旧扫描事务在新版本提交后迟到写入时仍不能回退 occurrence 与 coverage")
  void shouldLateConcurrentOldScanCannotOverwriteCommittedNewVersion() throws Exception {
    final String selectScheduleVersionSql = """
        select schedule_version from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;
    final String selectBizDateSql = """
        select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(3600);
    Instant oldCoverageThrough = now.plusSeconds(7200);
    Instant currentCoverageThrough = now.plusSeconds(10800);
    LocalDate oldBizDate = LocalDate.of(2026, 10, 13);
    LocalDate currentBizDate = LocalDate.of(2026, 10, 14);
    mapper.insertPlanned(
        ScheduledOccurrenceInsertPlannedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(4)
            .calendarCode("business_day")
            .scheduledFireTime(fireTime)
            .bizDate(oldBizDate)
            .build());
    recordCoverage(tenantId, workflowDefinitionId, 4, "business_day", now, oldCoverageThrough, now);

    CountDownLatch oldScanReadOldVersion = new CountDownLatch(1);
    CountDownLatch allowOldScanWrite = new CountDownLatch(1);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<?> oldScan;
    try {
      oldScan = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
        Integer observedVersion = jdbcTemplate.queryForObject(
            selectScheduleVersionSql,
            Integer.class,
            tenantId,
            workflowDefinitionId,
            Timestamp.from(fireTime));
        assertThat(observedVersion).isEqualTo(4);
        oldScanReadOldVersion.countDown();
        awaitLatch(allowOldScanWrite);
        mapper.insertPlanned(
            ScheduledOccurrenceInsertPlannedParam.builder()
                .tenantId(tenantId)
                .workflowDefinitionId(workflowDefinitionId)
                .workflowCode(workflowCode)
                .scheduleVersion(4)
                .calendarCode("business_day")
                .scheduledFireTime(fireTime)
                .bizDate(oldBizDate)
                .build());
        recordCoverage(
            tenantId, workflowDefinitionId, 4, "business_day", now, oldCoverageThrough, now);
      }));

      assertThat(oldScanReadOldVersion.await(10, TimeUnit.SECONDS)).isTrue();
      mapper.insertPlanned(
          ScheduledOccurrenceInsertPlannedParam.builder()
              .tenantId(tenantId)
              .workflowDefinitionId(workflowDefinitionId)
              .workflowCode(workflowCode)
              .scheduleVersion(5)
              .calendarCode("business_day")
              .scheduledFireTime(fireTime)
              .bizDate(currentBizDate)
              .build());
      recordCoverage(
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

    Integer selectedScheduleVersion = jdbcTemplate.queryForObject(
        selectScheduleVersionSql,
        Integer.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    assertThat(selectedScheduleVersion).isEqualTo(5);
    LocalDate selectedBizDate = jdbcTemplate.queryForObject(
        selectBizDateSql,
        LocalDate.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
    assertThat(selectedBizDate).isEqualTo(currentBizDate);
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 5, "business_day"))
        .isEqualTo(new ScheduledOccurrenceCoverage(now, currentCoverageThrough));
  }

  @Test
  @DisplayName("物化账本后 coverage 写入失败时 occurrence 与水位在同一事务回滚")
  void shouldPlannerFailureAfterOccurrenceWriteRollsBackOccurrenceAndCoverage() {
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(60);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(6);
    descriptor.setTimezone("UTC");
    Trigger quartzTrigger = TriggerBuilder.newTrigger()
        .withIdentity("crash-boundary", "occurrence-test")
        .startAt(Date.from(fireTime))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(0))
        .build();
    doThrow(new IllegalStateException("injected coverage persistence failure"))
        .when(mapper)
        .recordCoverage(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.tenantId(), tenantId) && java.util.Objects.equals(parameters.workflowDefinitionId(), workflowDefinitionId) && java.util.Objects.equals(parameters.scheduleVersion(), 6) && parameters.calendarCode() == null && parameters.coverageFrom() != null && parameters.coverageThrough() != null && parameters.completedAt() != null));

    assertThatThrownBy(() -> planner.materialize(descriptor, quartzTrigger, now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("injected coverage persistence failure");

    Long selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, tenantId, workflowDefinitionId, Timestamp.from(fireTime));
    assertThat(selectedScheduledOccurrenceCount).isZero();
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 6, null)).isNull();
  }

  @Test
  @DisplayName("occurrence 写入后数据库连接在提交前终止时整笔回滚且重扫无缺口")
  void shouldRecoverPlannerAfterDatabaseConnectionTerminatesBeforeCoverageCommit()
      throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String selectPgTerminateBackendSql = """
        select pg_terminate_backend(?)
    """;
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and occurrence_status = 'PLANNED'\
        """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant firstFire = now.plusSeconds(60);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(7);
    descriptor.setTimezone("UTC");
    Trigger quartzTrigger = TriggerBuilder.newTrigger()
        .withIdentity("database-terminated-scan", "occurrence-test")
        .startAt(Date.from(firstFire))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(1))
        .build();
    CountDownLatch batchWritten = new CountDownLatch(1);
    CountDownLatch continueAfterTermination = new CountDownLatch(1);
    AtomicInteger backendPid = new AtomicInteger();
    AtomicBoolean terminateOnce = new AtomicBoolean(true);
        doAnswer(invocation -> {
          int inserted = sqlSessionTemplate.insert(
              "io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper.insertPlannedBatch",
              invocation.getArgument(0));
          if (terminateOnce.compareAndSet(true, false)) {
            backendPid.set(jdbcTemplate.queryForObject(selectPgBackendPidSql, Integer.class));
            batchWritten.countDown();
            awaitLatch(continueAfterTermination);
          }
          return inserted;
        })
        .when(mapper)
        .insertPlannedBatch(any());

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<Integer> firstScan =
          executor.submit(() -> planner.materialize(descriptor, quartzTrigger, now));
      boolean didWriteBatch = batchWritten.await(10, TimeUnit.SECONDS);
      if (!didWriteBatch && firstScan.isDone()) {
        firstScan.get(1, TimeUnit.SECONDS);
      }
      assertThat(didWriteBatch).isTrue();
      Boolean selectedPgTerminateBackend =
          jdbcTemplate.queryForObject(selectPgTerminateBackendSql, Boolean.class, backendPid.get());
      assertThat(selectedPgTerminateBackend).isTrue();
      continueAfterTermination.countDown();
      assertThatThrownBy(() -> firstScan.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class);
    } finally {
      continueAfterTermination.countDown();
      executor.shutdownNow();
    }

    assertThat(occurrenceCount()).isZero();
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 7, null)).isNull();
    assertThat(planner.materialize(descriptor, quartzTrigger, now)).isEqualTo(2);
    assertThat(occurrenceCount()).isEqualTo(2);
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 7, null))
        .isEqualTo(
            new ScheduledOccurrenceCoverage(now.minusMillis(1), now.plus(Duration.ofDays(2))));
        Integer selectedScheduledOccurrenceCount =
            jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, workflowDefinitionId);
        assertThat(selectedScheduledOccurrenceCount).isEqualTo(2);
  }

  @Test
  @DisplayName("日历配置版本在物化期间变化时整批 occurrence 回滚并等待下一轮重扫")
  void shouldPlannerConfigurationRevisionChangeRollsBackPlannedFires() {
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(60);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(7);
    descriptor.setTimezone("UTC");
    Trigger quartzTrigger = TriggerBuilder.newTrigger()
        .withIdentity("configuration-change", "occurrence-test")
        .startAt(Date.from(fireTime))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(0))
        .build();
    doReturn(mapper.selectTenantConfigurationRevision(tenantId) + 1)
        .when(mapper)
        .lockTenantConfigurationRevision(tenantId);

    assertThatThrownBy(() -> planner.materialize(descriptor, quartzTrigger, now))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("configuration changed");

    Long selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
        selectCountSql, Long.class, tenantId, workflowDefinitionId, Timestamp.from(fireTime));
    assertThat(selectedScheduledOccurrenceCount).isZero();
    assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 7, null)).isNull();
  }

  @Test
  @DisplayName("真实并发日历变更使计划物化整体回滚，下一轮按新修订补齐 coverage")
  void shouldConcurrentCalendarChangeRollbackPlannerAndNextPassRestoreCoverage() throws Exception {
    final String insertBusinessCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone)\s\
        values (?, 'business_day', 'Business day', 'UTC')\
        """;
    final String updateBusinessCalendarSql = """
        update batch.business_calendar set cutoff_time = '05:00:00' where tenant_id = ?\s\
        and calendar_code = 'business_day'\
        """;
    final String selectCountSql = """
        select count(*) from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(300);
    jdbcTemplate.update(
        insertBusinessCalendarSql,
        tenantId);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(9);
    descriptor.setCalendarCode("business_day");
    descriptor.setTimezone("UTC");
    Trigger quartzTrigger = TriggerBuilder.newTrigger()
        .withIdentity("concurrent-configuration-change", "occurrence-test")
        .startAt(Date.from(fireTime))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(0))
        .build();
    CountDownLatch occurrenceWritten = new CountDownLatch(1);
    CountDownLatch configurationChanged = new CountDownLatch(1);
    doAnswer(invocation -> {
          int superseded = (int) invocation.callRealMethod();
          occurrenceWritten.countDown();
          awaitLatch(configurationChanged);
          return superseded;
        })
        .when(mapper)
        .supersedeFuturePlanned(eq(tenantId), eq(workflowDefinitionId), eq(9), any(Instant.class));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Integer> materialization =
        executor.submit(() -> planner.materialize(descriptor, quartzTrigger, now));
    try {
      assertThat(occurrenceWritten.await(10, TimeUnit.SECONDS)).isTrue();
      long oldRevision = mapper.selectTenantConfigurationRevision(tenantId);
      jdbcTemplate.update(
          updateBusinessCalendarSql,
          tenantId);
      long newRevision = mapper.selectTenantConfigurationRevision(tenantId);
      assertThat(newRevision).isGreaterThan(oldRevision);
      configurationChanged.countDown();

      assertThatThrownBy(() -> materialization.get(10, TimeUnit.SECONDS))
          .hasRootCauseInstanceOf(IllegalStateException.class)
          .hasRootCauseMessage(
              "Tenant configuration changed during scheduled occurrence materialization");
      Integer selectedScheduledOccurrenceCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, tenantId, workflowDefinitionId, Timestamp.from(fireTime));
      assertThat(selectedScheduledOccurrenceCount).isZero();
      assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 9, "business_day"))
          .isNull();

      Instant retryAt = mapper.selectTenantConfigurationUpdatedAt(tenantId).plusMillis(1);
      assertThat(planner.materialize(descriptor, quartzTrigger, retryAt)).isEqualTo(1);
      assertThat(mapper.selectCoverage(tenantId, workflowDefinitionId, 9, "business_day"))
          .isNotNull();
      assertThat(mapper.isCoverageCurrentForTenantConfiguration(tenantId, workflowDefinitionId))
          .isTrue();
      assertThat(status(fireTime)).isEqualTo("PLANNED");
    } finally {
      configurationChanged.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("配置 revision 稳定时成功物化的 coverage 记录当前配置代")
  void shouldSuccessfulPlannerCoverageIsFreshForCurrentConfigurationRevision() {
    final String selectConfigurationRevisionSql = """
        select coverage.configuration_revision = revision.revision\s\
        from batch.scheduled_occurrence_coverage coverage\s\
        join batch.tenant_configuration_revision revision on revision.tenant_id = coverage.tenant_id\s\
        where coverage.tenant_id = ? and coverage.workflow_definition_id = ?\
        """;

    Instant now = BatchDateTimeSupport.utcNow().truncatedTo(ChronoUnit.MILLIS);
    Instant fireTime = now.plusSeconds(60);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId(tenantId);
    descriptor.setWorkflowDefinitionId(workflowDefinitionId);
    descriptor.setWorkflowCode(workflowCode);
    descriptor.setWorkflowVersion(8);
    descriptor.setTimezone("UTC");
    Trigger quartzTrigger = TriggerBuilder.newTrigger()
        .withIdentity("current-configuration", "occurrence-test")
        .startAt(Date.from(fireTime))
        .withSchedule(
            SimpleScheduleBuilder.simpleSchedule().withIntervalInSeconds(60).withRepeatCount(0))
        .build();

    assertThat(planner.materialize(descriptor, quartzTrigger, now)).isEqualTo(1);
    Boolean selectedConfigurationRevision = jdbcTemplate.queryForObject(
        selectConfigurationRevisionSql, Boolean.class, tenantId, workflowDefinitionId);
    assertThat(selectedConfigurationRevision).isTrue();
  }

  private int recordCoverage(
      String coverageTenantId,
      Long coverageWorkflowDefinitionId,
      int scheduleVersion,
      String calendarCode,
      Instant coverageFrom,
      Instant coverageThrough,
      Instant completedAt) {
    long configurationRevision = mapper.selectTenantConfigurationRevision(coverageTenantId);
    return mapper.recordCoverage(
        ScheduledOccurrenceRecordCoverageParam.builder()
            .tenantId(coverageTenantId)
            .workflowDefinitionId(coverageWorkflowDefinitionId)
            .configurationRevision(configurationRevision)
            .scheduleVersion(scheduleVersion)
            .calendarCode(calendarCode)
            .coverageFrom(coverageFrom)
            .coverageThrough(coverageThrough)
            .completedAt(completedAt)
            .build());
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
        ScheduledOccurrenceInsertPlannedParam.builder()
            .tenantId(tenantId)
            .workflowDefinitionId(workflowDefinitionId)
            .workflowCode(workflowCode)
            .scheduleVersion(1)
            .calendarCode("business_day")
            .scheduledFireTime(fireTime)
            .bizDate(bizDate)
            .build());
  }

  private void ensureRequest(String requestId) {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status) values (?, ?, 'SCHEDULED', ?, ?, ?, 'ACCEPTED')\s\
        on conflict (tenant_id, request_id) do nothing\
        """;

    jdbcTemplate.update(
        insertTriggerRequestSql,
        tenantId,
        requestId,
        workflowDefinitionId,
        LocalDate.of(2026, 10, 14),
        "scheduled-test-" + requestId);
  }

  private void insertCatchUpAudit(Instant fireTime, LocalDate bizDate, String requestId) {
    final String insertScheduledOccurrenceCatchUpAuditSql = """
        insert into batch.scheduled_occurrence_catch_up_audit (tenant_id, workflow_definition_id,\s\
        scheduled_fire_time, biz_date, request_id, requested_by, reason)\s\
        values (?, ?, ?, ?, ?, 'ops-user', 'mismatch test')\
        """;

    jdbcTemplate.update(
        insertScheduledOccurrenceCatchUpAuditSql,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime),
        bizDate,
        requestId);
  }

  private String status(Instant fireTime) {
    final String selectOccurrenceStatusSql = """
        select occurrence_status from batch.scheduled_occurrence where tenant_id = ?\s\
        and workflow_definition_id = ? and scheduled_fire_time = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectOccurrenceStatusSql,
        String.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
  }

  private LocalDate bizDate(Instant fireTime) {
    final String selectBizDateSql = """
        select biz_date from batch.scheduled_occurrence where tenant_id = ? and workflow_definition_id = ?\s\
        and scheduled_fire_time = ?\
        """;

    return jdbcTemplate.queryForObject(
        selectBizDateSql,
        LocalDate.class,
        tenantId,
        workflowDefinitionId,
        Timestamp.from(fireTime));
  }
}
