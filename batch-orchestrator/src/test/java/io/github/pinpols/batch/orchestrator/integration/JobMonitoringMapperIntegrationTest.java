package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("作业监控 mapper 的真实 PostgreSQL 查询语义")
class JobMonitoringMapperIntegrationTest {

  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static final String LEGACY_FIXED_RATE_TENANT = "legacy-fixed-rate-migration";

  private static DataSource dataSource;
  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void initializePostgresOnlyFixture() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();
    postgresDataSource.setURL(POSTGRES.getJdbcUrl());
    postgresDataSource.setUser(POSTGRES.getUsername());
    postgresDataSource.setPassword(POSTGRES.getPassword());
    dataSource = postgresDataSource;
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute("create schema batch");
    jdbcTemplate.execute("""
        create table batch.job_definition (
          id bigserial primary key,
          tenant_id varchar(64) not null,
          job_code varchar(128) not null,
          job_name varchar(256) not null,
          job_type varchar(32) not null,
          biz_type varchar(64) not null,
          schedule_type varchar(32) not null,
          depends_on_job_code varchar(128),
          timezone varchar(64) not null,
          priority integer not null,
          trigger_mode varchar(32) not null,
          dag_enabled boolean not null,
          shard_strategy varchar(32) not null,
          retry_policy varchar(32) not null,
          retry_max_count integer not null,
          timeout_seconds integer not null,
          enabled boolean not null,
          version integer not null
        )
        """);
    jdbcTemplate.execute("""
        create table batch.job_instance (
          id bigserial primary key,
          tenant_id varchar(64) not null,
          job_definition_id bigint not null,
          job_code varchar(128) not null,
          instance_no varchar(128) not null,
          biz_date date not null,
          dedup_key varchar(256) not null,
          instance_status varchar(32) not null,
          trigger_type varchar(32) not null,
          trace_id varchar(128),
          created_at timestamptz not null default current_timestamp,
          started_at timestamptz,
          finished_at timestamptz,
          params_snapshot jsonb,
          failed_partition_count integer not null default 0,
          dry_run boolean not null default false
        )
        """);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V223__job_monitoring_policy_and_alert_claim.sql"))
        .execute(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V224__job_monitoring_failed_partition_alert.sql"))
        .execute(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V225__job_monitoring_policy_severity.sql"))
        .execute(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V226__scheduled_job_completion_deadline_time.sql"))
        .execute(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V227__job_not_completed_deadline_alert.sql"))
        .execute(dataSource);
    Long legacyDefinitionId =
        jdbcTemplate.queryForObject("""
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
          priority, trigger_mode, dag_enabled, shard_strategy, retry_policy,
          retry_max_count, timeout_seconds, enabled, version
        ) values (?, 'LEGACY_FIXED_RATE', 'legacy fixed rate', 'GENERAL', 'IT', 'FIXED_RATE',
                  'UTC', 5, 'API', false, 'NONE', 'NONE', 0, 0, true, 1)
        returning id
        """, Long.class, LEGACY_FIXED_RATE_TENANT);
    jdbcTemplate.update("""
        insert into batch.job_monitoring_policy (
          tenant_id, job_definition_id, soft_runtime_seconds, start_grace_seconds,
          completion_deadline_seconds, completion_deadline_local_time, completion_deadline_day_offset
        ) values (?, ?, 0, 0, 0, time '04:00', 1)
        """, LEGACY_FIXED_RATE_TENANT, legacyDefinitionId);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V228__fixed_rate_completion_grace.sql"))
        .execute(dataSource);

    org.apache.ibatis.session.Configuration configuration =
        new org.apache.ibatis.session.Configuration(
            new Environment("job-monitoring-it", new JdbcTransactionFactory(), dataSource));
    String mapperPath = "mapper/JobMonitoringMapper.xml";
    try (var mapperXml = Resources.getResourceAsStream(mapperPath)) {
      new XMLMapperBuilder(mapperXml, configuration, mapperPath, configuration.getSqlFragments())
          .parse();
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @DisplayName("迁移清除无法换算的固定频率旧钟点，不伪造宽限时长")
  @Test
  void shouldClearLegacyFixedRateWallClockPolicyDuringMigration() {
    Map<String, Object> policy = jdbcTemplate.queryForMap("""
        select mp.completion_deadline_local_time, mp.completion_deadline_day_offset,
               mp.dependency_completion_window_seconds
        from batch.job_monitoring_policy mp
        join batch.job_definition jd
          on jd.tenant_id = mp.tenant_id and jd.id = mp.job_definition_id
        where mp.tenant_id = ?
        """, LEGACY_FIXED_RATE_TENANT);

    assertThat(policy.get("completion_deadline_local_time")).isNull();
    assertThat(((Number) policy.get("completion_deadline_day_offset")).intValue())
        .isZero();
    assertThat(((Number) policy.get("dependency_completion_window_seconds")).intValue())
        .isZero();
  }

  @DisplayName("识别耗时过久、启动过晚和完成过晚，并排除不适用作业与 dry-run")
  @Test
  void shouldSelectConfiguredViolationsWithoutChangingRuntimeRows() {
    String tenant = "monitor-it-" + UUID.randomUUID();
    long runningDefinitionId = createJobDefinition(tenant, "RUNNING", "MANUAL");
    long notStartedDefinitionId = createJobDefinition(tenant, "NOT_STARTED", "CRON");
    long deadlineDefinitionId = createJobDefinition(tenant, "NOT_COMPLETED", "CRON");
    long manualDeadlineDefinitionId = createJobDefinition(tenant, "MANUAL_DEADLINE", "MANUAL");
    long dryRunDefinitionId = createJobDefinition(tenant, "DRY_RUN", "MANUAL");

    savePolicy(tenant, runningDefinitionId, 30, 0, null, 0, 0);
    savePolicy(tenant, notStartedDefinitionId, 0, 60, null, 0, 0);
    savePolicy(tenant, deadlineDefinitionId, 0, 0, java.time.LocalTime.MIDNIGHT, 0, 0);
    savePolicy(tenant, manualDeadlineDefinitionId, 0, 0, java.time.LocalTime.MIDNIGHT, 0, 0);
    jdbcTemplate.update(
        "update batch.job_monitoring_policy set completion_deadline_updated_at = current_timestamp - interval '1 hour' "
            + "where tenant_id = ? and job_definition_id = ?",
        tenant,
        deadlineDefinitionId);
    savePolicy(tenant, dryRunDefinitionId, 30, 0, null, 0, 0);

    long runningId = insertInstance(
        tenant, runningDefinitionId, "RUNNING", Instant.now().minusSeconds(120), null, null, false);
    long notStartedId = insertInstance(
        tenant,
        notStartedDefinitionId,
        "READY",
        null,
        null,
        Instant.now().minusSeconds(3600),
        false);
    long stillRunningAtDeadlineId = insertInstance(
        tenant,
        deadlineDefinitionId,
        "RUNNING",
        Instant.now().minusSeconds(3000),
        null,
        Instant.now().minusSeconds(3600),
        false);
    long finishedAfterDeadlineId = insertInstance(
        tenant,
        deadlineDefinitionId,
        "SUCCESS",
        Instant.now().minusSeconds(3000),
        Instant.now().minusSeconds(1200),
        Instant.now().minusSeconds(3600),
        false);
    long historicalCompletionId = insertInstance(
        tenant,
        deadlineDefinitionId,
        "SUCCESS",
        Instant.now().minusSeconds(10800),
        Instant.now().minusSeconds(7200),
        Instant.now().minusSeconds(10800),
        false);
    long manualJobId = insertInstance(
        tenant,
        manualDeadlineDefinitionId,
        "RUNNING",
        Instant.now().minusSeconds(3000),
        null,
        Instant.now().minusSeconds(3600),
        false);
    long dryRunId = insertInstance(
        tenant, dryRunDefinitionId, "RUNNING", Instant.now().minusSeconds(120), null, null, true);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      assertContains(mapper.selectRunningTooLong(100), runningId);
      assertThat(mapper.selectRunningTooLong(100))
          .noneMatch(row -> row.jobInstanceId().equals(dryRunId));
      assertContains(mapper.selectNotStarted(100), notStartedId);
      assertThat(mapper.selectNotCompletedByDeadline(100))
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .contains(stillRunningAtDeadlineId, finishedAfterDeadlineId);
      assertThat(mapper.selectNotCompletedByDeadline(100))
          .noneMatch(row -> row.jobInstanceId().equals(historicalCompletionId));
      assertThat(mapper.selectNotCompletedByDeadline(100))
          .noneMatch(row -> row.jobInstanceId().equals(manualJobId));
    }

    assertThat(jdbcTemplate.queryForMap(
            "select instance_status, started_at, finished_at from batch.job_instance where id = ?",
            runningId))
        .containsEntry("instance_status", "RUNNING")
        .containsEntry("finished_at", null);
  }

  @DisplayName("固定频率独立作业不适用启动和完成过晚告警")
  @Test
  void shouldMonitorOnlyRuntimeForStandaloneFixedRateJobs() {
    String tenant = "monitor-fixed-rate-it-" + UUID.randomUUID();
    long fixedRateDefinitionId = createJobDefinition(tenant, "FIXED_RATE", "FIXED_RATE");
    savePolicy(tenant, fixedRateDefinitionId, 0, 300, null, 0, 0);

    Instant justExpiredScheduledAt = Instant.now().minusSeconds(90);
    Instant stillWithinGraceScheduledAt = Instant.now().minusSeconds(30);
    long expiredInstance = insertInstance(
        tenant,
        fixedRateDefinitionId,
        "RUNNING",
        justExpiredScheduledAt,
        null,
        justExpiredScheduledAt,
        false);
    long withinGraceInstance = insertInstance(
        tenant,
        fixedRateDefinitionId,
        "RUNNING",
        stillWithinGraceScheduledAt,
        null,
        stillWithinGraceScheduledAt,
        false);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      assertThat(mapper.selectNotStarted(100))
          .noneMatch(row -> row.jobInstanceId().equals(expiredInstance)
              || row.jobInstanceId().equals(withinGraceInstance));
      assertThat(mapper.selectNotCompletedByDeadline(100))
          .noneMatch(row -> row.jobInstanceId().equals(expiredInstance)
              || row.jobInstanceId().equals(withinGraceInstance));
    }
  }

  @DisplayName("依赖作业以 EFFECTIVE 时刻作为执行资格基准")
  @Test
  void shouldUseUpstreamEffectiveAtForDependentJobDeadlines() {
    String tenant = "monitor-dependent-it-" + UUID.randomUUID();
    long definitionId = createJobDefinition(tenant, "DEPENDENT", "MANUAL", "UPSTREAM");
    savePolicy(tenant, definitionId, 0, 60, null, 0, 120);

    Instant readyAt = Instant.now().minusSeconds(300);
    long eligibleButNotStarted =
        insertInstance(tenant, definitionId, "READY", null, null, null, readyAt, false);
    long eligibleAndStillRunning = insertInstance(
        tenant, definitionId, "RUNNING", readyAt.plusSeconds(30), null, null, readyAt, false);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> notStarted = mapper.selectNotStarted(100);
      List<JobMonitoringAlertCandidate> notCompleted = mapper.selectNotCompletedByDeadline(100);

      assertContains(notStarted, eligibleButNotStarted);
      assertThat(notStarted)
          .filteredOn(row -> row.jobInstanceId().equals(eligibleButNotStarted))
          .singleElement()
          .satisfies(
              row -> assertThat(row.monitoringDeadlineAt()).isEqualTo(readyAt.plusSeconds(60)));
      assertContains(notCompleted, eligibleAndStillRunning);
      assertThat(notCompleted)
          .filteredOn(row -> row.jobInstanceId().equals(eligibleAndStillRunning))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.thresholdSeconds()).isEqualTo(120);
            assertThat(row.monitoringDeadlineAt()).isEqualTo(readyAt.plusSeconds(120));
          });
    }
  }

  @DisplayName("Cron 依赖作业以计划触发时刻和上游生效时刻中的较晚者起算")
  @Test
  void shouldUseLaterOfScheduledAndUpstreamEffectiveAtForDependentCronDeadlines() {
    String tenant = "monitor-dependent-cron-it-" + UUID.randomUUID();
    long definitionId = createJobDefinition(tenant, "DEPENDENT_CRON", "CRON", "UPSTREAM");
    savePolicy(tenant, definitionId, 0, 60, null, 0, 120);

    Instant upstreamReadyAt = Instant.now().minusSeconds(600);
    Instant plannedAfterReadyAt = upstreamReadyAt.plusSeconds(200);
    Instant readyAfterPlannedAt = upstreamReadyAt.plusSeconds(400);
    long plannedWinsId = insertInstance(
        tenant, definitionId, "READY", null, null, plannedAfterReadyAt, upstreamReadyAt, false);
    long upstreamWinsId = insertInstance(
        tenant, definitionId, "READY", null, null, upstreamReadyAt, readyAfterPlannedAt, false);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> notStarted = mapper.selectNotStarted(100);
      List<JobMonitoringAlertCandidate> notCompleted = mapper.selectNotCompletedByDeadline(100);

      assertThat(notStarted)
          .filteredOn(row -> row.jobInstanceId().equals(plannedWinsId))
          .singleElement()
          .satisfies(row -> assertThat(row.monitoringDeadlineAt())
              .isEqualTo(plannedAfterReadyAt.plusSeconds(60)));
      assertThat(notStarted)
          .filteredOn(row -> row.jobInstanceId().equals(upstreamWinsId))
          .singleElement()
          .satisfies(row -> assertThat(row.monitoringDeadlineAt())
              .isEqualTo(readyAfterPlannedAt.plusSeconds(60)));
      assertThat(notCompleted)
          .filteredOn(row -> row.jobInstanceId().equals(plannedWinsId))
          .singleElement()
          .satisfies(row -> assertThat(row.monitoringDeadlineAt())
              .isEqualTo(plannedAfterReadyAt.plusSeconds(120)));
      assertThat(notCompleted)
          .filteredOn(row -> row.jobInstanceId().equals(upstreamWinsId))
          .singleElement()
          .satisfies(row -> assertThat(row.monitoringDeadlineAt())
              .isEqualTo(readyAfterPlannedAt.plusSeconds(120)));
    }
  }

  @DisplayName("告警 claim 按租户、实例和违规类型原子去重")
  @Test
  void shouldClaimEachViolationOnceWithinTenantAndInstance() {
    String tenant = "monitor-claim-it-" + UUID.randomUUID();
    long definitionId = createJobDefinition(tenant, "CLAIM", "MANUAL");
    long instanceId = insertInstance(
        tenant, definitionId, "RUNNING", Instant.now().minusSeconds(120), null, null, false);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      assertThat(mapper.claimAlert(tenant, instanceId, "RUNNING_TOO_LONG")).isEqualTo(1);
      assertThat(mapper.claimAlert(tenant, instanceId, "RUNNING_TOO_LONG")).isZero();
      assertThat(mapper.claimAlert(tenant, instanceId, "NOT_STARTED")).isEqualTo(1);
      assertThat(mapper.claimAlert(tenant, instanceId, "NOT_COMPLETED_BY_DEADLINE"))
          .isEqualTo(1);
      assertThat(mapper.claimAlert("other-tenant", instanceId, "RUNNING_TOO_LONG"))
          .isEqualTo(1);
      assertThat(mapper.claimAlert(tenant, instanceId, "FAILED_PARTITION")).isEqualTo(1);
    }
  }

  @DisplayName("只选近期非 dry-run 的终态失败分区实例")
  @Test
  void shouldSelectOnlyRecentFinalPartitionFailures() {
    String tenant = "monitor-partition-it-" + UUID.randomUUID();
    long definitionId = createJobDefinition(tenant, "FAILED_PARTITION", "MANUAL");
    long recentFailure = insertInstance(
        tenant,
        definitionId,
        "PARTIAL_FAILED",
        Instant.now().minusSeconds(300),
        Instant.now().minusSeconds(60),
        null,
        false);
    long zeroFailedPartitions = insertInstance(
        tenant,
        definitionId,
        "FAILED",
        Instant.now().minusSeconds(300),
        Instant.now().minusSeconds(40),
        null,
        false);
    long dryRunFailure = insertInstance(
        tenant,
        definitionId,
        "FAILED",
        Instant.now().minusSeconds(300),
        Instant.now().minusSeconds(30),
        null,
        true);
    long historicalFailure = insertInstance(
        tenant,
        definitionId,
        "FAILED",
        Instant.now().minusSeconds(7200),
        Instant.now().minusSeconds(7200),
        null,
        false);
    jdbcTemplate.update(
        "update batch.job_instance set failed_partition_count = 2 where id = ?", recentFailure);
    jdbcTemplate.update(
        "update batch.job_instance set failed_partition_count = 3 where id in (?, ?)",
        dryRunFailure,
        historicalFailure);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> candidates = mapper.selectFinalPartitionFailures(100, 3600);

      assertContains(candidates, recentFailure);
      assertThat(candidates)
          .singleElement()
          .satisfies(candidate -> assertThat(candidate.failedPartitionCount()).isEqualTo(2));
      assertThat(candidates)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(zeroFailedPartitions, dryRunFailure, historicalFailure);
    }
  }

  private long createJobDefinition(String tenant, String suffix, String scheduleType) {
    return createJobDefinition(tenant, suffix, scheduleType, null);
  }

  private long createJobDefinition(
      String tenant, String suffix, String scheduleType, String dependsOnJobCode) {
    Long id = jdbcTemplate.queryForObject(
        """
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type, biz_type, schedule_type, depends_on_job_code, timezone,
          priority, trigger_mode, dag_enabled, shard_strategy, retry_policy,
          retry_max_count, timeout_seconds, enabled, version
        ) values (?, ?, ?, 'GENERAL', 'IT', ?, ?, 'UTC', 5, 'API', false,
                  'NONE', 'NONE', 0, 0, true, 1)
        returning id
        """,
        Long.class,
        tenant,
        suffix + "_" + UUID.randomUUID(),
        "monitor integration " + suffix,
        scheduleType,
        dependsOnJobCode);
    assertThat(id).isNotNull();
    return id;
  }

  private void savePolicy(
      String tenant,
      long definitionId,
      int runtimeSeconds,
      int startGraceSeconds,
      java.time.LocalTime completionTime,
      int completionDayOffset,
      int dependencyCompletionWindowSeconds) {
    jdbcTemplate.update(
        """
        insert into batch.job_monitoring_policy (
          tenant_id, job_definition_id, soft_runtime_seconds, start_grace_seconds,
          completion_deadline_seconds, completion_deadline_local_time,
          completion_deadline_day_offset, dependency_completion_window_seconds
        ) values (?, ?, ?, ?, 0, ?, ?, ?)
        """,
        tenant,
        definitionId,
        runtimeSeconds,
        startGraceSeconds,
        completionTime,
        completionDayOffset,
        dependencyCompletionWindowSeconds);
  }

  private long insertInstance(
      String tenant,
      long definitionId,
      String status,
      Instant startedAt,
      Instant finishedAt,
      Instant scheduledAt,
      boolean dryRun) {
    return insertInstance(
        tenant, definitionId, status, startedAt, finishedAt, scheduledAt, null, dryRun);
  }

  private long insertInstance(
      String tenant,
      long definitionId,
      String status,
      Instant startedAt,
      Instant finishedAt,
      Instant scheduledAt,
      Instant dependencyReadyAt,
      boolean dryRun) {
    String jobCode = jdbcTemplate.queryForObject(
        "select job_code from batch.job_definition where id = ?", String.class, definitionId);
    String scheduledAtEntry = scheduledAt == null ? "" : "\"scheduledAt\":\"" + scheduledAt + "\"";
    String readyAtEntry = dependencyReadyAt == null
        ? ""
        : (scheduledAtEntry.isEmpty() ? "" : ",") + "\"dependencyReadyAt\":\"" + dependencyReadyAt
            + "\"";
    String snapshot = scheduledAtEntry.isEmpty() && readyAtEntry.isEmpty()
        ? "{}"
        : "{\"effectiveParams\":{" + scheduledAtEntry + readyAtEntry + "}}";
    Long id = jdbcTemplate.queryForObject(
        """
        insert into batch.job_instance (
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, dedup_key, params_snapshot, started_at, finished_at,
          failed_partition_count, dry_run
        ) values (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?::jsonb, ?, ?, 0, ?)
        returning id
        """,
        Long.class,
        tenant,
        definitionId,
        jobCode,
        "instance-" + UUID.randomUUID(),
        LocalDate.now(),
        status,
        "dedup-" + UUID.randomUUID(),
        snapshot,
        toTimestamp(startedAt),
        toTimestamp(finishedAt),
        dryRun);
    assertThat(id).isNotNull();
    return id;
  }

  private static Timestamp toTimestamp(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }

  private static void assertContains(
      List<JobMonitoringAlertCandidate> candidates, long instanceId) {
    assertThat(candidates)
        .extracting(JobMonitoringAlertCandidate::jobInstanceId)
        .contains(instanceId);
  }
}
