package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.governance.JobMonitoringAlertEmissionService.Violation;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobMonitoringRecheckQuery;
import io.github.pinpols.batch.orchestrator.domain.view.JobMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
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
  private static final String INSERT_WORKFLOW_CHILD_TRIGGER_REQUEST_SQL = """
      insert into batch.trigger_request (tenant_id, parent_workflow_run_id, parent_node_code)
      values (?, ?, ?)
      returning id
      """;
  private static final String LINK_JOB_INSTANCE_TO_TRIGGER_REQUEST_SQL = """
      update batch.job_instance set trigger_request_id = ? where tenant_id = ? and id = ?
      """;

  private static DataSource dataSource;
  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sqlSessionFactory;

  @BeforeAll
  static void initializePostgresOnlyFixture() throws Exception {
    final String createSchemaSql = """
        create schema batch
    """;
    final String createJobDefinitionSql = """
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
          """;
    final String createJobInstanceSql = """
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
            trigger_request_id bigint,
            created_at timestamptz not null default current_timestamp,
            started_at timestamptz,
            execution_started_at timestamptz,
            finished_at timestamptz,
            deadline_at timestamptz,
            sla_alerted_at timestamptz,
            params_snapshot jsonb,
            expected_duration_seconds integer not null default 0,
            failed_partition_count integer not null default 0,
            dry_run boolean not null default false
          )
          """;
    final String createTriggerRequestSql = """
          create table batch.trigger_request (
            id bigserial primary key,
            tenant_id varchar(64) not null,
            request_id varchar(128),
            job_code varchar(128),
            biz_date date,
            request_status varchar(32),
            related_job_instance_id bigint,
            trigger_type varchar(32),
            trace_id varchar(128),
            dry_run boolean not null default false,
            parent_workflow_run_id bigint,
            parent_node_code varchar(128),
            created_at timestamptz not null default current_timestamp,
            updated_at timestamptz not null default current_timestamp
          )
          """;
    final String createWorkflowRunSql = """
          create table batch.workflow_run (
            id bigserial primary key,
            tenant_id varchar(64) not null,
            run_status varchar(32) not null,
            dry_run boolean not null default false
          )
          """;
    final String createBatchDayWaitingLaunchSql = """
          create table batch.batch_day_waiting_launch (
            tenant_id varchar(64) not null,
            request_id varchar(128) not null,
            wait_status varchar(32) not null,
            released_at timestamptz,
            updated_at timestamptz not null default current_timestamp
          )
          """;
    final String createDependencyWaitingLaunchSql = """
          create table batch.job_dependency_waiting_launch (
            tenant_id varchar(64) not null,
            request_id varchar(128) not null,
            wait_status varchar(24) not null,
            released_at timestamptz,
            updated_at timestamptz not null default current_timestamp
          )
          """;
    final String createAlertEventSql = """
          create table batch.alert_event (
            tenant_id varchar(64) not null,
            alert_type varchar(128) not null,
            detail_json jsonb
          )
          """;
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, biz_type, schedule_type, timezone,
            priority, trigger_mode, dag_enabled, shard_strategy, retry_policy,
            retry_max_count, timeout_seconds, enabled, version
          ) values (?, 'LEGACY_FIXED_RATE', 'legacy fixed rate', 'GENERAL', 'IT', 'FIXED_RATE',
                    'UTC', 5, 'API', false, 'NONE', 'NONE', 0, 0, true, 1)
          returning id
          """;
    final String insertJobMonitoringPolicySql = """
          insert into batch.job_monitoring_policy (
            tenant_id, job_definition_id, soft_runtime_seconds, start_grace_seconds,
            completion_deadline_seconds, completion_deadline_local_time, completion_deadline_day_offset
          ) values (?, ?, 0, 0, 0, time '04:00', 1)
          """;

    POSTGRES.start();
    PGSimpleDataSource postgresDataSource = new PGSimpleDataSource();
    postgresDataSource.setURL(POSTGRES.getJdbcUrl());
    postgresDataSource.setUser(POSTGRES.getUsername());
    postgresDataSource.setPassword(POSTGRES.getPassword());
    dataSource = postgresDataSource;
    jdbcTemplate = new JdbcTemplate(dataSource);
    jdbcTemplate.execute(createSchemaSql);
    jdbcTemplate.execute(createJobDefinitionSql);
    jdbcTemplate.execute(createJobInstanceSql);
    jdbcTemplate.execute(createTriggerRequestSql);
    jdbcTemplate.execute(createWorkflowRunSql);
    jdbcTemplate.execute(createBatchDayWaitingLaunchSql);
    jdbcTemplate.execute(createDependencyWaitingLaunchSql);
    jdbcTemplate.execute(createAlertEventSql);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V224__job_monitoring_policy_and_alert_claim.sql"))
        .execute(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V225__job_monitoring_failed_partition_alert.sql"))
        .execute(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V226__job_monitoring_policy_severity.sql"))
        .execute(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V227__scheduled_job_completion_deadline_time.sql"))
        .execute(dataSource);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V228__job_not_completed_deadline_alert.sql"))
        .execute(dataSource);
    Long legacyDefinitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, LEGACY_FIXED_RATE_TENANT);
    jdbcTemplate.update(insertJobMonitoringPolicySql, LEGACY_FIXED_RATE_TENANT, legacyDefinitionId);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V229__fixed_rate_completion_grace.sql"))
        .execute(dataSource);
    String backdateExistingPolicySql = """
        UPDATE batch.job_monitoring_policy SET updated_at = CURRENT_TIMESTAMP - INTERVAL '2 hours'
        WHERE tenant_id = ?
        """;
    jdbcTemplate.update(backdateExistingPolicySql, LEGACY_FIXED_RATE_TENANT);
    new ResourceDatabasePopulator(
            new ClassPathResource("db/migration/V355__job_monitoring_rule_effective_times.sql"))
        .execute(dataSource);

    Configuration configuration = new Configuration(
        new Environment("job-monitoring-it", new JdbcTransactionFactory(), dataSource));
    String jobInstanceMapperPath = "mapper/JobInstanceMapper.xml";
    try (var mapperXml = Resources.getResourceAsStream(jobInstanceMapperPath)) {
      new XMLMapperBuilder(
              mapperXml, configuration, jobInstanceMapperPath, configuration.getSqlFragments())
          .parse();
    }
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

  @Test
  @DisplayName("Workflow 子作业在实例创建前按节点激活与门禁释放时间监控")
  void shouldMonitorWorkflowChildJobRequestsBeforeInstanceCreation() {
    String tenant = "uncreated-workflow-job-" + UUID.randomUUID();
    long definitionId = createJobDefinition(tenant, "CHILD_REQUEST", "MANUAL");
    savePolicy(tenant, definitionId, 0, 60, null, 0, 120);
    long activeWorkflowRunId = insertWorkflowRun(tenant, "RUNNING", false);
    long waitingWorkflowRunId = insertWorkflowRun(tenant, "RUNNING", false);
    long dryRunWorkflowRunId = insertWorkflowRun(tenant, "RUNNING", true);
    long closedWorkflowRunId = insertWorkflowRun(tenant, "SUCCESS", false);
    Instant oldActivation = Instant.now().minusSeconds(600);
    long releasedRequestId = insertWorkflowChildRequest(
        tenant,
        definitionId,
        activeWorkflowRunId,
        "RELEASED_NODE",
        "ACCEPTED",
        oldActivation,
        false);
    long waitingRequestId = insertWorkflowChildRequest(
        tenant,
        definitionId,
        waitingWorkflowRunId,
        "BATCH_WAIT_NODE",
        "WAITING",
        oldActivation,
        false);
    long releaseGraceRequestId = insertWorkflowChildRequest(
        tenant,
        definitionId,
        activeWorkflowRunId,
        "RELEASE_GRACE_NODE",
        "ACCEPTED",
        oldActivation,
        false);
    long dryRunRequestId = insertWorkflowChildRequest(
        tenant, definitionId, dryRunWorkflowRunId, "DRY_RUN_NODE", "ACCEPTED", oldActivation, true);
    long closedRequestId = insertWorkflowChildRequest(
        tenant, definitionId, closedWorkflowRunId, "CLOSED_NODE", "ACCEPTED", oldActivation, false);

    String insertReleasedDependencySql = """
        insert into batch.job_dependency_waiting_launch
            (tenant_id, request_id, wait_status, released_at)
        values (?, ?, 'RELEASED', ?)
        """;
    jdbcTemplate.update(
        insertReleasedDependencySql,
        tenant,
        requestId(releasedRequestId),
        Timestamp.from(oldActivation.plusSeconds(30)));
    String insertWaitingBatchDaySql = """
        insert into batch.batch_day_waiting_launch
            (tenant_id, request_id, wait_status, released_at)
        values (?, ?, 'WAITING', null)
        """;
    jdbcTemplate.update(insertWaitingBatchDaySql, tenant, requestId(waitingRequestId));
    jdbcTemplate.update(
        insertReleasedDependencySql,
        tenant,
        requestId(releaseGraceRequestId),
        Timestamp.from(Instant.now().minusSeconds(30)));

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> startCandidates = mapper.selectNotStarted(100);
      JobMonitoringAlertCandidate released = startCandidates.stream()
          .filter(row -> Long.valueOf(releasedRequestId).equals(row.triggerRequestId()))
          .findFirst()
          .orElseThrow();
      assertThat(released.jobInstanceId()).isNull();
      assertThat(released.workflowRunId()).isEqualTo(activeWorkflowRunId);
      assertThat(released.nodeCode()).isEqualTo("RELEASED_NODE");
      assertThat(released.monitoringDeadlineAt())
          .isBetween(oldActivation.plusSeconds(89), oldActivation.plusSeconds(91));
      assertThat(startCandidates)
          .noneMatch(row -> Long.valueOf(waitingRequestId).equals(row.triggerRequestId())
              || Long.valueOf(releaseGraceRequestId).equals(row.triggerRequestId())
              || Long.valueOf(dryRunRequestId).equals(row.triggerRequestId())
              || Long.valueOf(closedRequestId).equals(row.triggerRequestId()));

      List<JobMonitoringAlertCandidate> completionCandidates =
          mapper.selectNotCompletedByDeadline(100);
      assertThat(completionCandidates)
          .extracting(JobMonitoringAlertCandidate::triggerRequestId)
          .contains(releasedRequestId)
          .doesNotContain(
              waitingRequestId, releaseGraceRequestId, dryRunRequestId, closedRequestId);

      long instanceId = insertInstance(tenant, definitionId, "READY", null, null, null, false);
      String updateInstanceSql =
          "update batch.job_instance set trigger_request_id = ? where tenant_id = ? and id = ?";
      jdbcTemplate.update(updateInstanceSql, releasedRequestId, tenant, instanceId);
      String updateRequestSql =
          "update batch.trigger_request set request_status = 'LAUNCHED', related_job_instance_id = ? where tenant_id = ? and id = ?";
      jdbcTemplate.update(updateRequestSql, instanceId, tenant, releasedRequestId);
      session.clearCache();
      assertThat(mapper.selectNotStarted(100))
          .noneMatch(row -> Long.valueOf(releasedRequestId).equals(row.triggerRequestId()));
      assertThat(mapper.selectNotCompletedByDeadline(100))
          .noneMatch(row -> Long.valueOf(releasedRequestId).equals(row.triggerRequestId()));
    }
  }

  @Test
  @DisplayName("升级继承旧策略可证实的最近保存时刻，不以迁移时间截断重启补报窗口")
  void shouldRetainExistingPolicyEvidenceBoundaryDuringMigration() {
    String selectSql = """
        SELECT soft_runtime_updated_at = updated_at AND start_grace_updated_at = updated_at
               AND updated_at < CURRENT_TIMESTAMP - INTERVAL '1 hour'
        FROM batch.job_monitoring_policy WHERE tenant_id = ?
        """;
    Boolean retained =
        jdbcTemplate.queryForObject(selectSql, Boolean.class, LEGACY_FIXED_RATE_TENANT);
    assertThat(retained).isTrue();
  }

  @Test
  @DisplayName("运行耗时使用真实入口，补报扫描间隙内已结束的违约，短执行不因排队而告警")
  void shouldUseExecutionTimeAndBackfillCompletedRuntime() {
    String tenant = "runtime-clock-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "runtime-clock", "MANUAL");
    savePolicy(tenant, definition, 60, 0, null, 0, 0);
    Instant now = Instant.now();
    Instant dispatch = now.minusSeconds(600);
    long queued = insertInstance(tenant, definition, "RUNNING", dispatch, null, null, false);
    setExecutionStart(tenant, queued, now.minusSeconds(10));
    long completedLate =
        insertInstance(tenant, definition, "SUCCESS", dispatch, now.minusSeconds(20), null, false);
    setExecutionStart(tenant, completedLate, now.minusSeconds(120));
    long completedQuickly =
        insertInstance(tenant, definition, "SUCCESS", dispatch, now.minusSeconds(20), null, false);
    setExecutionStart(tenant, completedQuickly, now.minusSeconds(40));
    long missingEntry = insertInstance(tenant, definition, "RUNNING", dispatch, null, null, false);
    setExecutionStart(tenant, missingEntry, null);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> candidates = mapper.selectRunningTooLong(100);
      assertContains(candidates, completedLate);
      assertThat(candidates)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(queued, completedQuickly, missingEntry);
    }
  }

  @Test
  @DisplayName("迟启动既覆盖派发后仍排队，也补报扫描间隙内已迟启动的终态作业")
  void shouldBackfillLateActualStartWithoutTreatingDispatchAsStart() {
    String tenant = "start-clock-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "start-clock", "CRON");
    savePolicy(tenant, definition, 0, 60, null, 0, 0);
    Instant now = Instant.now();
    Instant planned = now.minusSeconds(600);
    long queued =
        insertInstance(tenant, definition, "RUNNING", planned.plusSeconds(5), null, planned, false);
    setExecutionStart(tenant, queued, null);
    long startedLate = insertInstance(
        tenant,
        definition,
        "SUCCESS",
        planned.plusSeconds(5),
        now.minusSeconds(10),
        planned,
        false);
    setExecutionStart(tenant, startedLate, now.minusSeconds(30));
    long onTime = insertInstance(
        tenant,
        definition,
        "SUCCESS",
        planned.plusSeconds(5),
        now.minusSeconds(10),
        planned,
        false);
    setExecutionStart(tenant, onTime, planned.plusSeconds(20));
    long unknownHistory = insertInstance(
        tenant,
        definition,
        "SUCCESS",
        planned.plusSeconds(5),
        now.minusSeconds(10),
        planned,
        false);
    setExecutionStart(tenant, unknownHistory, null);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> candidates = mapper.selectNotStarted(100);
      assertContains(candidates, queued);
      assertContains(candidates, startedLate);
      assertThat(candidates)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(onTime, unknownHistory);
    }
  }

  @Test
  @DisplayName("阈值启用前已经结束的启动与执行违约不被追溯补报")
  void shouldNotBackfillEventsThatClosedBeforeRuleActivation() {
    String tenant = "policy-cutoff-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "policy-cutoff", "CRON");
    savePolicy(tenant, definition, 60, 60, null, 0, 0);
    Instant now = Instant.now();
    long historical = insertInstance(
        tenant,
        definition,
        "SUCCESS",
        now.minusSeconds(600),
        now.minusSeconds(60),
        now.minusSeconds(700),
        false);
    setExecutionStart(tenant, historical, now.minusSeconds(500));
    String activateSql = """
        UPDATE batch.job_monitoring_policy
        SET soft_runtime_updated_at = CURRENT_TIMESTAMP, start_grace_updated_at = CURRENT_TIMESTAMP
        WHERE tenant_id = ? AND job_definition_id = ?
        """;
    jdbcTemplate.update(activateSql, tenant, definition);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> runtime = mapper.selectRunningTooLong(100);
      List<JobMonitoringAlertCandidate> starts = mapper.selectNotStarted(100);
      assertThat(runtime)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(historical);
      assertThat(starts)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(historical);
    }
  }

  private void setExecutionStart(String tenant, long instanceId, Instant startedAt) {
    String updateSql = """
        UPDATE batch.job_instance SET execution_started_at = ? WHERE tenant_id = ? AND id = ?
        """;
    Timestamp start = toTimestamp(startedAt);
    jdbcTemplate.update(updateSql, start, tenant, instanceId);
  }

  @Test
  @DisplayName("新 Workflow 子作业不依赖旧 CRON 配置，从节点激活与依赖生效的较晚时刻计迟启动")
  void shouldUseCurrentWorkflowChildEligibilityInsteadOfInheritedRootSchedule() {
    String tenant = "workflow-child-clock-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "workflow-child-clock", "MANUAL");
    savePolicy(tenant, definition, 0, 60, null, 0, 0);
    Instant now = Instant.now();
    long oldActivation = insertInstance(tenant, definition, "READY", null, null, null, false);
    attachCurrentWorkflowChild(tenant, oldActivation, now.minusSeconds(300), null);
    long recentlyActivated = insertInstance(tenant, definition, "READY", null, null, null, false);
    attachCurrentWorkflowChild(tenant, recentlyActivated, now.minusSeconds(10), null);
    long dependencyRecentlyReady =
        insertInstance(tenant, definition, "READY", null, null, null, false);
    attachCurrentWorkflowChild(
        tenant, dependencyRecentlyReady, now.minusSeconds(300), now.minusSeconds(10));
    long dependencyLongReady = insertInstance(tenant, definition, "READY", null, null, null, false);
    attachCurrentWorkflowChild(
        tenant, dependencyLongReady, now.minusSeconds(300), now.minusSeconds(120));
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> candidates = mapper.selectNotStarted(100);
      assertContains(candidates, oldActivation);
      assertContains(candidates, dependencyLongReady);
      assertThat(candidates)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(recentlyActivated, dependencyRecentlyReady);
    }
  }

  @Test
  @DisplayName("新 Workflow 子作业可按节点资格使用完成窗口，不能因作业定义为 MANUAL 而漏扫")
  void shouldApplyCompletionWindowToCurrentWorkflowChild() {
    String tenant = "workflow-child-end-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "workflow-child-end", "MANUAL");
    savePolicy(tenant, definition, 0, 0, null, 0, 60);
    Instant now = Instant.now();
    long late =
        insertInstance(tenant, definition, "RUNNING", now.minusSeconds(100), null, null, false);
    attachCurrentWorkflowChild(tenant, late, now.minusSeconds(300), now.minusSeconds(120));
    long stillWithinWindow =
        insertInstance(tenant, definition, "RUNNING", now.minusSeconds(5), null, null, false);
    attachCurrentWorkflowChild(
        tenant, stillWithinWindow, now.minusSeconds(300), now.minusSeconds(10));
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> candidates = mapper.selectNotCompletedByDeadline(100);
      assertContains(candidates, late);
      assertThat(candidates)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(stillWithinWindow);
    }
  }

  private void attachCurrentWorkflowChild(
      String tenant, long instanceId, Instant activatedAt, Instant dependencyReadyAt) {
    attachWorkflowContext(tenant, instanceId, 101L, "CHILD");
    String updateActivationSql = """
        UPDATE batch.trigger_request SET created_at = ?
        WHERE tenant_id = ? AND id = (
            SELECT trigger_request_id FROM batch.job_instance WHERE tenant_id = ? AND id = ?)
        """;
    Timestamp activation = toTimestamp(activatedAt);
    jdbcTemplate.update(updateActivationSql, activation, tenant, tenant, instanceId);
    Map<String, Object> effectiveParams = Map.of(
        "_parentNodeRunId", 1001L, "scheduledAt", activatedAt.minusSeconds(3600).toString());
    List<Map<String, Object>> references;
    if (dependencyReadyAt == null) references = List.of();
    else references = List.of(Map.of("effectiveAt", dependencyReadyAt.toString()));
    Map<String, Object> snapshot =
        Map.of("effectiveParams", effectiveParams, "dependencyResultSnapshot", references);
    String snapshotJson = JsonUtils.toJson(snapshot);
    String updateSnapshotSql = """
        UPDATE batch.job_instance SET params_snapshot = ?::jsonb WHERE tenant_id = ? AND id = ?
        """;
    jdbcTemplate.update(updateSnapshotSql, snapshotJson, tenant, instanceId);
  }

  @Test
  @DisplayName("子作业绝对完成钟点使用自身业务日期，不依赖旧定时类型")
  void shouldApplyAbsoluteCompletionClockToWorkflowChildBusinessDate() {
    String tenant = "workflow-child-absolute-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "workflow-child-absolute", "MANUAL");
    savePolicy(tenant, definition, 0, 0, LocalTime.MIDNIGHT, 1, 0);
    Instant now = Instant.now();
    long instance =
        insertInstance(tenant, definition, "RUNNING", now.minusSeconds(10), null, null, false);
    attachCurrentWorkflowChild(tenant, instance, now.minusSeconds(20), null);
    String businessDateSql = """
        UPDATE batch.job_instance SET biz_date = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date - 2
        WHERE tenant_id = ? AND id = ?
        """;
    jdbcTemplate.update(businessDateSql, tenant, instance);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> candidates = mapper.selectNotCompletedByDeadline(100);
      assertContains(candidates, instance);
    }
  }

  @Test
  @DisplayName("损坏的依赖生效时间不能伪装成无依赖，非法旧日期不能中断整个扫描")
  void shouldKeepMalformedClockEvidenceIneligibleWithoutAbortingScan() {
    String tenant = "invalid-monitor-clock-" + UUID.randomUUID();
    long manualDefinition = createJobDefinition(tenant, "invalid-workflow-clock", "MANUAL");
    long cronDefinition = createJobDefinition(tenant, "invalid-cron-clock", "CRON");
    savePolicy(tenant, manualDefinition, 0, 60, null, 0, 60);
    savePolicy(tenant, cronDefinition, 0, 60, null, 0, 0);
    long child = insertInstance(tenant, manualDefinition, "READY", null, null, null, false);
    attachCurrentWorkflowChild(tenant, child, Instant.now().minusSeconds(300), null);
    String corruptReferenceSql = """
        UPDATE batch.job_instance SET params_snapshot = jsonb_set(params_snapshot,
          '{dependencyResultSnapshot}', '[{"effectiveAt":"2026-99-99T00:00:00Z"}]')
        WHERE tenant_id = ? AND id = ?
        """;
    jdbcTemplate.update(corruptReferenceSql, tenant, child);
    long legacy = insertInstance(tenant, cronDefinition, "READY", null, null, null, false);
    String corruptLegacyClockSql = """
        UPDATE batch.job_instance
        SET params_snapshot = '{"effectiveParams":{"scheduledAt":"2026-99-99T00:00:00Z"}}'::jsonb
        WHERE tenant_id = ? AND id = ?
        """;
    jdbcTemplate.update(corruptLegacyClockSql, tenant, legacy);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> starts = mapper.selectNotStarted(100);
      List<JobMonitoringAlertCandidate> completions = mapper.selectNotCompletedByDeadline(100);
      assertThat(starts)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(child, legacy);
      assertThat(completions)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(child, legacy);
    }
  }

  @Test
  @DisplayName("两种完成期限均按真实资格应用，配置入口不再与旧调度类型绑定")
  void shouldApplyEitherCompletionModeWhenActualEligibilityExists() {
    String tenant = "completion-modes-" + UUID.randomUUID();
    Instant now = Instant.now();
    long cronDefinition = createJobDefinition(tenant, "cron-window", "CRON");
    savePolicy(tenant, cronDefinition, 0, 0, null, 0, 60);
    long cron = insertInstance(
        tenant,
        cronDefinition,
        "RUNNING",
        now.minusSeconds(10),
        null,
        now.minusSeconds(300),
        false);
    long dependentDefinition =
        createJobDefinition(tenant, "dependency-clock", "MANUAL", "UPSTREAM");
    savePolicy(tenant, dependentDefinition, 0, 0, LocalTime.MIDNIGHT, 1, 0);
    long dependent = insertInstance(
        tenant,
        dependentDefinition,
        "RUNNING",
        now.minusSeconds(10),
        null,
        null,
        now.minusSeconds(300),
        false);
    String setBusinessDateSql = """
        UPDATE batch.job_instance SET biz_date = (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')::date - 2
        WHERE tenant_id = ? AND id = ?
        """;
    jdbcTemplate.update(setBusinessDateSql, tenant, dependent);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> candidates = mapper.selectNotCompletedByDeadline(100);
      assertContains(candidates, cron);
      assertContains(candidates, dependent);
    }
  }

  @Test
  @DisplayName("旧 expected-duration SLA 同样使用实际执行入口，不把派发排队计为执行")
  void shouldUseActualEntryForLegacyExpectedDurationSla() {
    String tenant = "legacy-runtime-clock-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "legacy-runtime", "MANUAL");
    long shortExecution = insertSlaCandidate(tenant, definition);
    setExecutionStart(tenant, shortExecution, Instant.now().minusSeconds(10));
    long overdue = insertSlaCandidate(tenant, definition);
    long unknown = insertSlaCandidate(tenant, definition);
    setExecutionStart(tenant, unknown, null);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobInstanceEntity> candidates = mapper.selectSlaViolationCandidates(100);
      assertThat(candidates)
          .extracting(JobInstanceEntity::getId)
          .contains(overdue)
          .doesNotContain(shortExecution, unknown);
    }
  }

  @Test
  @DisplayName("旧实例级 SLA 在扫描间隔内已终态时按实际完成时刻补报")
  void shouldBackfillLegacySlaViolationsAfterLateCompletion() {
    String tenant = "legacy-terminal-sla-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "legacy-terminal", "MANUAL");
    Instant reference = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    long lateDeadline = insertSlaCandidate(tenant, definition);
    completeSlaCandidate(
        tenant,
        lateDeadline,
        "SUCCESS",
        reference.minusSeconds(240),
        reference.minusSeconds(30),
        reference.minusSeconds(60),
        false);

    long lateRuntime = insertSlaCandidate(tenant, definition);
    completeSlaCandidate(
        tenant,
        lateRuntime,
        "FAILED",
        reference.minusSeconds(180),
        reference.minusSeconds(90),
        null,
        false);

    long onTimeCompletion = insertSlaCandidate(tenant, definition);
    completeSlaCandidate(
        tenant,
        onTimeCompletion,
        "SUCCESS",
        reference.minusSeconds(100),
        reference.minusSeconds(40),
        reference.minusSeconds(10),
        false);

    long terminalWithoutFinish = insertSlaCandidate(tenant, definition);
    completeSlaCandidate(
        tenant, terminalWithoutFinish, "SUCCESS", reference.minusSeconds(180), null, null, false);

    long dryRun = insertSlaCandidate(tenant, definition);
    completeSlaCandidate(
        tenant,
        dryRun,
        "SUCCESS_DRY_RUN",
        reference.minusSeconds(180),
        reference.minusSeconds(90),
        null,
        true);

    long unknownExecutionStart = insertSlaCandidate(tenant, definition);
    completeSlaCandidate(
        tenant, unknownExecutionStart, "SUCCESS", null, reference.minusSeconds(30), null, false);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobInstanceEntity> candidates = mapper.selectSlaViolationCandidates(100);
      assertThat(candidates)
          .extracting(JobInstanceEntity::getId)
          .contains(lateDeadline, lateRuntime)
          .doesNotContain(onTimeCompletion, terminalWithoutFinish, dryRun, unknownExecutionStart);
    }
  }

  @Test
  @DisplayName("旧 SLA 终态纳入范围与 JobInstanceStatus 业务终态一致")
  void shouldMatchLegacySlaTerminalEligibilityToJobInstanceStatuses() {
    String tenant = "legacy-terminal-statuses-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "legacy-terminal-statuses", "MANUAL");
    Instant reference = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    Set<String> slaActiveStatuses = Set.of("WAITING", "READY", "RUNNING");
    Set<String> slaTerminalStatuses = JobInstanceStatus.terminalCodes().stream()
        .filter(code -> !JobInstanceStatus.fromCodeOrNull(code).isDryRunTerminal())
        .collect(Collectors.toSet());
    Set<Long> expectedCandidateIds = new HashSet<>();

    for (JobInstanceStatus status : JobInstanceStatus.values()) {
      boolean terminal = JobInstanceStatus.terminalCodes().contains(status.code());
      long instanceId = insertSlaCandidate(tenant, definition);
      completeSlaCandidate(
          tenant,
          instanceId,
          status.code(),
          reference.minusSeconds(180),
          terminal ? reference.minusSeconds(30) : null,
          reference.minusSeconds(60),
          false);
      if (slaActiveStatuses.contains(status.code())
          || slaTerminalStatuses.contains(status.code())) {
        expectedCandidateIds.add(instanceId);
      }
    }

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobInstanceEntity> tenantCandidates = mapper.selectSlaViolationCandidates(100).stream()
          .filter(candidate -> tenant.equals(candidate.getTenantId()))
          .toList();
      assertThat(tenantCandidates)
          .extracting(JobInstanceEntity::getId)
          .containsExactlyInAnyOrderElementsOf(expectedCandidateIds);
    }
  }

  @DisplayName("迁移清除无法换算的固定频率旧钟点，不伪造宽限时长")
  @Test
  void shouldClearLegacyFixedRateWallClockPolicyDuringMigration() {
    final String selectCompletionDeadlineLocalTimeSql = """
          select mp.completion_deadline_local_time, mp.completion_deadline_day_offset,
                 mp.dependency_completion_window_seconds
          from batch.job_monitoring_policy mp
          join batch.job_definition jd
            on jd.tenant_id = mp.tenant_id and jd.id = mp.job_definition_id
          where mp.tenant_id = ?
          """;

    Map<String, Object> policy =
        jdbcTemplate.queryForMap(selectCompletionDeadlineLocalTimeSql, LEGACY_FIXED_RATE_TENANT);

    assertThat(policy.get("completion_deadline_local_time")).isNull();
    assertThat(((Number) policy.get("completion_deadline_day_offset")).intValue())
        .isZero();
    assertThat(((Number) policy.get("dependency_completion_window_seconds")).intValue())
        .isZero();
  }

  @DisplayName("识别耗时过久、启动过晚和完成过晚，并排除不适用作业与 dry-run")
  @Test
  void shouldSelectConfiguredViolationsWithoutChangingRuntimeRows() {
    final String updateJobMonitoringPolicySql = """
        update batch.job_monitoring_policy\s\
        set completion_deadline_updated_at = current_timestamp - interval '1 hour' where tenant_id = ?\s\
        and job_definition_id = ?\
        """;
    final String selectInstanceStatusSql = """
        select instance_status, started_at, finished_at from batch.job_instance where id = ?
    """;

    String tenant = "monitor-it-" + UUID.randomUUID();
    long runningDefinitionId = createJobDefinition(tenant, "RUNNING", "MANUAL");
    long notStartedDefinitionId = createJobDefinition(tenant, "NOT_STARTED", "CRON");
    long deadlineDefinitionId = createJobDefinition(tenant, "NOT_COMPLETED", "CRON");
    long manualDeadlineDefinitionId = createJobDefinition(tenant, "MANUAL_DEADLINE", "MANUAL");
    long dryRunDefinitionId = createJobDefinition(tenant, "DRY_RUN", "MANUAL");

    savePolicy(tenant, runningDefinitionId, 30, 0, null, 0, 0);
    savePolicy(tenant, notStartedDefinitionId, 0, 60, null, 0, 0);
    savePolicy(tenant, deadlineDefinitionId, 0, 0, LocalTime.MIDNIGHT, 0, 0);
    savePolicy(tenant, manualDeadlineDefinitionId, 0, 0, LocalTime.MIDNIGHT, 0, 0);
    jdbcTemplate.update(updateJobMonitoringPolicySql, tenant, deadlineDefinitionId);
    savePolicy(tenant, dryRunDefinitionId, 30, 0, null, 0, 0);

    long runningId = insertInstance(
        tenant, runningDefinitionId, "RUNNING", Instant.now().minusSeconds(120), null, null, false);
    final String selectBizDateSql = "select biz_date from batch.job_instance where id = ?";
    LocalDate runningBizDate =
        jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, runningId);
    attachWorkflowContext(tenant, runningId, 84L, "RUNNING_NODE");
    long notStartedId = insertInstance(
        tenant,
        notStartedDefinitionId,
        "READY",
        null,
        null,
        Instant.now().minusSeconds(3600),
        false);
    attachWorkflowContext(tenant, notStartedId, 85L, "NOT_STARTED_NODE");
    long stillRunningAtDeadlineId = insertInstance(
        tenant,
        deadlineDefinitionId,
        "RUNNING",
        Instant.now().minusSeconds(3000),
        null,
        Instant.now().minusSeconds(3600),
        false);
    attachWorkflowContext(tenant, stillRunningAtDeadlineId, 86L, "DEADLINE_NODE");
    long finishedAfterDeadlineId = insertInstance(
        tenant,
        deadlineDefinitionId,
        "SUCCESS",
        Instant.now().minusSeconds(3000),
        Instant.now().minusSeconds(1200),
        Instant.now().minusSeconds(3600),
        false);
    attachWorkflowContext(tenant, finishedAfterDeadlineId, 87L, "COMPLETED_LATE_NODE");
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
      assertThat(mapper.selectRunningTooLong(100))
          .filteredOn(row -> row.jobInstanceId().equals(runningId))
          .singleElement()
          .extracting(JobMonitoringAlertCandidate::bizDate)
          .isEqualTo(runningBizDate);
      assertThat(mapper.selectRunningTooLong(100))
          .filteredOn(row -> row.jobInstanceId().equals(runningId))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.workflowRunId()).isEqualTo(84L);
            assertThat(row.nodeCode()).isEqualTo("RUNNING_NODE");
          });
      assertContains(mapper.selectNotStarted(100), notStartedId);
      assertWorkflowContext(mapper.selectNotStarted(100), notStartedId, 85L, "NOT_STARTED_NODE");
      assertThat(mapper.selectNotCompletedByDeadline(100))
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .contains(stillRunningAtDeadlineId, finishedAfterDeadlineId);
      assertWorkflowContext(
          mapper.selectNotCompletedByDeadline(100), stillRunningAtDeadlineId, 86L, "DEADLINE_NODE");
      assertWorkflowContext(
          mapper.selectNotCompletedByDeadline(100),
          finishedAfterDeadlineId,
          87L,
          "COMPLETED_LATE_NODE");
      assertThat(mapper.selectNotCompletedByDeadline(100))
          .noneMatch(row -> row.jobInstanceId().equals(historicalCompletionId));
      assertThat(mapper.selectNotCompletedByDeadline(100))
          .noneMatch(row -> row.jobInstanceId().equals(manualJobId));
    }

    Map<String, Object> selectedInstanceStatusRow =
        jdbcTemplate.queryForMap(selectInstanceStatusSql, runningId);
    assertThat(selectedInstanceStatusRow)
        .containsEntry("instance_status", "RUNNING")
        .containsEntry("finished_at", null);
  }

  @DisplayName("监控策略修改即时影响已运行实例")
  @Test
  void shouldApplyUpdatedSoftRuntimePolicyToExistingInstances() {
    final String updateJobMonitoringPolicySql = """
        update batch.job_monitoring_policy\s\
        set soft_runtime_seconds = 0, completion_deadline_updated_at = current_timestamp where tenant_id = ?\s\
        and job_definition_id = ?\
        """;
    final String updateSlaAlertedAtSql = """
        update batch.job_instance set sla_alerted_at = ? where tenant_id = ? and id = ?
        """;

    String tenant = "monitor-live-policy-" + UUID.randomUUID();
    long definitionId = createJobDefinition(tenant, "LIVE_POLICY", "MANUAL");
    savePolicy(tenant, definitionId, 300, 0, null, 0, 0);
    long instanceId = insertSlaCandidate(tenant, definitionId);
    attachWorkflowContext(tenant, instanceId, 89L, "SLA_NODE");
    String controlTenant = "monitor-live-policy-control-" + UUID.randomUUID();
    long controlDefinitionId = createJobDefinition(controlTenant, "CONTROL", "MANUAL");
    savePolicy(controlTenant, controlDefinitionId, 0, 0, null, 0, 0);
    long controlInstanceId = insertSlaCandidate(controlTenant, controlDefinitionId);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      assertThat(session.getMapper(JobMonitoringMapper.class).selectSlaViolationCandidates(100))
          .extracting(JobInstanceEntity::getId)
          .contains(controlInstanceId)
          .doesNotContain(instanceId);
    }
    jdbcTemplate.update(updateJobMonitoringPolicySql, tenant, definitionId);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      var mapper = session.getMapper(JobMonitoringMapper.class);
      assertThat(mapper.selectSlaViolationCandidates(100))
          .extracting(JobInstanceEntity::getId)
          .contains(instanceId);
      assertThat(mapper.selectSlaViolationCandidates(100))
          .filteredOn(row -> row.getId().equals(instanceId))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.getParentWorkflowRunId()).isEqualTo(89L);
            assertThat(row.getParentNodeCode()).isEqualTo("SLA_NODE");
          });
    }

    Instant slaAlertedAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(900);
    jdbcTemplate.update(updateSlaAlertedAtSql, Timestamp.from(slaAlertedAt), tenant, instanceId);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      var mapper = session.getMapper(JobMonitoringMapper.class);
      assertThat(mapper.selectSlaEscalationCandidates(slaAlertedAt.plusSeconds(300), 100))
          .filteredOn(row -> row.getId().equals(instanceId))
          .singleElement()
          .satisfies(row -> {
            assertThat(row.getParentWorkflowRunId()).isEqualTo(89L);
            assertThat(row.getParentNodeCode()).isEqualTo("SLA_NODE");
          });
      assertThat(mapper.selectSlaEscalationCandidateForUpdate(
              tenant, instanceId, slaAlertedAt.plusSeconds(300)))
          .satisfies(row -> {
            assertThat(row.getParentWorkflowRunId()).isEqualTo(89L);
            assertThat(row.getParentNodeCode()).isEqualTo("SLA_NODE");
          });
    }
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

    Instant readyAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(300);
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

    Instant upstreamReadyAt = Instant.now().truncatedTo(ChronoUnit.MICROS).minusSeconds(600);
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
    final String updateJobInstanceSql = """
      update batch.job_instance set failed_partition_count = 2 where id = ?
  """;
    final String updateJobInstanceSql2 = """
      update batch.job_instance set failed_partition_count = 3 where id in (?, ?)
  """;

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
    attachWorkflowContext(tenant, recentFailure, 88L, "FAILED_PARTITION_NODE");
    jdbcTemplate.update(updateJobInstanceSql, recentFailure);
    jdbcTemplate.update(updateJobInstanceSql2, dryRunFailure, historicalFailure);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      List<JobMonitoringAlertCandidate> candidates = mapper.selectFinalPartitionFailures(100, 3600);

      assertContains(candidates, recentFailure);
      assertThat(candidates).singleElement().satisfies(candidate -> {
        assertThat(candidate.failedPartitionCount()).isEqualTo(2);
        assertThat(candidate.workflowRunId()).isEqualTo(88L);
        assertThat(candidate.nodeCode()).isEqualTo("FAILED_PARTITION_NODE");
      });
      assertThat(candidates)
          .extracting(JobMonitoringAlertCandidate::jobInstanceId)
          .doesNotContain(zeroFailedPartitions, dryRunFailure, historicalFailure);
    }
  }

  @Test
  @DisplayName("发送前四类查询精确限定租户实例，并响应策略停用和 dry-run 变化")
  void shouldRecheckExactCandidateAgainstCurrentPolicyAndInstance() {
    String tenant = "recheck-" + UUID.randomUUID();
    long definition = createJobDefinition(tenant, "recheck", "CRON");
    savePolicy(tenant, definition, 60, 60, null, 0, 60);
    Instant now = Instant.now();
    insertInstance(
        tenant, definition, "RUNNING", now.minusSeconds(300), null, now.minusSeconds(400), false);
    long target = insertInstance(
        tenant,
        definition,
        "FAILED",
        now.minusSeconds(180),
        now.minusSeconds(20),
        now.minusSeconds(300),
        false);
    String prepareSql = """
        update batch.job_instance set failed_partition_count = 1 where tenant_id = ? and id = ?
        """;
    String backdatePolicySql = """
        update batch.job_monitoring_policy
        set completion_deadline_updated_at = current_timestamp - interval '1 day'
        where tenant_id = ? and job_definition_id = ?
        """;
    jdbcTemplate.update(prepareSql, tenant, target);
    jdbcTemplate.update(backdatePolicySql, tenant, definition);
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobMonitoringMapper mapper = session.getMapper(JobMonitoringMapper.class);
      for (Violation violation : Violation.values()) {
        JobMonitoringRecheckQuery query =
            new JobMonitoringRecheckQuery(tenant, target, violation.name(), 3600);
        JobMonitoringAlertCandidate actual = mapper.selectCandidateForRecheck(query);
        assertThat(actual).as("当前目标: %s", violation).isNotNull();
        assertThat(actual.jobInstanceId()).isEqualTo(target);
        JobMonitoringRecheckQuery foreignQuery =
            new JobMonitoringRecheckQuery("another-tenant", target, violation.name(), 3600);
        assertThat(mapper.selectCandidateForRecheck(foreignQuery)).isNull();
      }
      String disablePolicySql = """
          update batch.job_monitoring_policy
          set soft_runtime_seconds = 0, start_grace_seconds = 0,
              dependency_completion_window_seconds = 0
          where tenant_id = ? and job_definition_id = ?
          """;
      jdbcTemplate.update(disablePolicySql, tenant, definition);
      for (Violation violation : List.of(
          Violation.RUNNING_TOO_LONG, Violation.NOT_STARTED, Violation.NOT_COMPLETED_BY_DEADLINE)) {
        JobMonitoringRecheckQuery query =
            new JobMonitoringRecheckQuery(tenant, target, violation.name(), 3600);
        session.clearCache();
        assertThat(mapper.selectCandidateForRecheck(query))
            .as("停用策略: %s", violation)
            .isNull();
      }
      String enableDryRunSql = """
          update batch.job_instance set dry_run = true where tenant_id = ? and id = ?
          """;
      jdbcTemplate.update(enableDryRunSql, tenant, target);
      session.clearCache();
      JobMonitoringRecheckQuery failureQuery =
          new JobMonitoringRecheckQuery(tenant, target, Violation.FAILED_PARTITION.name(), 3600);
      assertThat(mapper.selectCandidateForRecheck(failureQuery)).isNull();
    }
  }

  private long insertWorkflowRun(String tenant, String status, boolean dryRun) {
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, run_status, dry_run)
        values (?, ?, ?) returning id
        """;
    return jdbcTemplate.queryForObject(insertWorkflowRunSql, Long.class, tenant, status, dryRun);
  }

  private long insertWorkflowChildRequest(
      String tenant,
      long definitionId,
      long workflowRunId,
      String nodeCode,
      String status,
      Instant createdAt,
      boolean dryRun) {
    final String selectJobCodeSql = """
        select job_code from batch.job_definition where tenant_id = ? and id = ?
        """;
    final String insertRequestSql = """
        insert into batch.trigger_request (
            tenant_id, request_id, job_code, biz_date, request_status, trigger_type, dry_run,
            parent_workflow_run_id, parent_node_code, created_at, updated_at
        )
        values (?, ?, ?, current_date, ?, 'EVENT', ?, ?, ?, ?, ?)
        returning id
        """;
    String requestId = "child-" + UUID.randomUUID();
    String jobCode =
        jdbcTemplate.queryForObject(selectJobCodeSql, String.class, tenant, definitionId);
    Long id = jdbcTemplate.queryForObject(
        insertRequestSql,
        Long.class,
        tenant,
        requestId,
        jobCode,
        status,
        dryRun,
        workflowRunId,
        nodeCode,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
    assertThat(id).isNotNull();
    return id;
  }

  private String requestId(long triggerRequestId) {
    final String selectRequestIdSql = """
        select request_id from batch.trigger_request where id = ?
        """;
    return jdbcTemplate.queryForObject(selectRequestIdSql, String.class, triggerRequestId);
  }

  private long createJobDefinition(String tenant, String suffix, String scheduleType) {
    return createJobDefinition(tenant, suffix, scheduleType, null);
  }

  private long createJobDefinition(
      String tenant, String suffix, String scheduleType, String dependsOnJobCode) {
    final String insertJobDefinitionSql = """
    insert into batch.job_definition (
    tenant_id, job_code, job_name, job_type, biz_type, schedule_type, depends_on_job_code, timezone,
    priority, trigger_mode, dag_enabled, shard_strategy, retry_policy,
    retry_max_count, timeout_seconds, enabled, version
    ) values (?, ?, ?, 'GENERAL', 'IT', ?, ?, 'UTC', 5, 'API', false,
    'NONE', 'NONE', 0, 0, true, 1)
    returning id
    """;

    Long id = jdbcTemplate.queryForObject(
        insertJobDefinitionSql,
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
      LocalTime completionTime,
      int completionDayOffset,
      int dependencyCompletionWindowSeconds) {
    final String insertJobMonitoringPolicySql = """
    insert into batch.job_monitoring_policy (
    tenant_id, job_definition_id, soft_runtime_seconds, start_grace_seconds,
    completion_deadline_seconds, completion_deadline_local_time,
    completion_deadline_day_offset, dependency_completion_window_seconds,
    soft_runtime_updated_at, start_grace_updated_at
    ) values (?, ?, ?, ?, 0, ?, ?, ?, current_timestamp - interval '1 day', current_timestamp - interval '1 day')
    """;

    jdbcTemplate.update(
        insertJobMonitoringPolicySql,
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

  private long insertSlaCandidate(String tenant, long definitionId) {
    final String selectJobCodeSql = """
        select job_code from batch.job_definition where id = ?
    """;
    final String insertJobInstanceSql = """
          insert into batch.job_instance (
            tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
            instance_status, dedup_key, params_snapshot, started_at, execution_started_at,
            expected_duration_seconds, failed_partition_count, dry_run
          ) values (?, ?, ?, ?, ?, 'MANUAL', 'RUNNING', ?, ?::jsonb, ?, ?, 60, 0, false)
          returning id
          """;

    String jobCode = jdbcTemplate.queryForObject(selectJobCodeSql, String.class, definitionId);
    Long id = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenant,
        definitionId,
        jobCode,
        "instance-" + UUID.randomUUID(),
        LocalDate.now(),
        "dedup-" + UUID.randomUUID(),
        "{}",
        Timestamp.from(Instant.now().minusSeconds(120)),
        Timestamp.from(Instant.now().minusSeconds(120)));
    assertThat(id).isNotNull();
    return id;
  }

  private void completeSlaCandidate(
      String tenant,
      long instanceId,
      String status,
      Instant executionStartedAt,
      Instant finishedAt,
      Instant deadlineAt,
      boolean dryRun) {
    final String completeSlaCandidateSql = """
        update batch.job_instance
        set instance_status = ?, execution_started_at = ?, finished_at = ?, deadline_at = ?,
            dry_run = ?
        where tenant_id = ? and id = ?
        """;

    assertThat(jdbcTemplate.update(
            completeSlaCandidateSql,
            status,
            toTimestamp(executionStartedAt),
            toTimestamp(finishedAt),
            toTimestamp(deadlineAt),
            dryRun,
            tenant,
            instanceId))
        .isEqualTo(1);
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
    final String selectJobCodeSql = """
        select job_code from batch.job_definition where id = ?
    """;
    final String insertJobInstanceSql = """
    insert into batch.job_instance (
    tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
    instance_status, dedup_key, params_snapshot, started_at, execution_started_at, finished_at,
    failed_partition_count, dry_run
    ) values (?, ?, ?, ?, ?, 'MANUAL', ?, ?, ?::jsonb, ?, ?, ?, 0, ?)
    returning id
    """;

    String jobCode = jdbcTemplate.queryForObject(selectJobCodeSql, String.class, definitionId);
    String scheduledAtEntry = scheduledAt == null ? "" : "\"scheduledAt\":\"" + scheduledAt + "\"";
    String readyAtEntry = dependencyReadyAt == null
        ? ""
        : (scheduledAtEntry.isEmpty() ? "" : ",") + "\"dependencyReadyAt\":\"" + dependencyReadyAt
            + "\"";
    String snapshot = scheduledAtEntry.isEmpty() && readyAtEntry.isEmpty()
        ? "{}"
        : "{\"effectiveParams\":{" + scheduledAtEntry + readyAtEntry + "}}";
    Long id = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
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

  private void attachWorkflowContext(
      String tenant, long jobInstanceId, long workflowRunId, String nodeCode) {
    Long triggerRequestId = jdbcTemplate.queryForObject(
        INSERT_WORKFLOW_CHILD_TRIGGER_REQUEST_SQL, Long.class, tenant, workflowRunId, nodeCode);
    assertThat(triggerRequestId).isNotNull();
    int affectedLINK_JOB_INSTANCE_TO_TRIGGER_REQUEST_Rows = jdbcTemplate.update(
        LINK_JOB_INSTANCE_TO_TRIGGER_REQUEST_SQL, triggerRequestId, tenant, jobInstanceId);
    assertThat(affectedLINK_JOB_INSTANCE_TO_TRIGGER_REQUEST_Rows).isEqualTo(1);
  }

  private static void assertWorkflowContext(
      List<JobMonitoringAlertCandidate> candidates,
      long jobInstanceId,
      long workflowRunId,
      String nodeCode) {
    assertThat(candidates)
        .filteredOn(row -> row.jobInstanceId().equals(jobInstanceId))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.workflowRunId()).isEqualTo(workflowRunId);
          assertThat(row.nodeCode()).isEqualTo(nodeCode);
        });
  }
}
