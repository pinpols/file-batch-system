package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.query.WorkflowMonitoringQuery;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowMonitoringAlertCandidate.Violation;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowMonitoringMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

@DisplayName("Workflow 三类时限：真实 PostgreSQL 机会、终态补报与隔离")
class WorkflowMonitoringMapperIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();
  private static final Instant PLANNED_AT = Instant.parse("2026-01-03T00:00:00Z");
  private static final Instant LATE_START = Instant.parse("2026-01-03T01:00:00Z");
  private static final Instant LATE_FINISH = Instant.parse("2026-01-05T02:00:00Z");
  private static JdbcTemplate jdbcTemplate;
  private static SqlSessionFactory sessionFactory;

  @BeforeAll
  static void initialize() throws Exception {
    POSTGRES.start();
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setURL(POSTGRES.getJdbcUrl());
    dataSource.setUser(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    String createTablesSql = """
        CREATE SCHEMA batch;
        CREATE TABLE batch.workflow_definition (
            id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
            workflow_code VARCHAR(128) NOT NULL, timezone VARCHAR(64) NOT NULL,
            UNIQUE (tenant_id, id));
        CREATE TABLE batch.trigger_request (
            id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(64), request_id VARCHAR(128),
            workflow_definition_id BIGINT, parent_workflow_run_id BIGINT,
            trace_id VARCHAR(128), dry_run BOOLEAN NOT NULL DEFAULT false);
        CREATE TABLE batch.workflow_run (
            id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(64), workflow_definition_id BIGINT,
            trigger_request_id BIGINT, biz_date DATE DEFAULT '2026-01-03', trace_id VARCHAR(128),
            started_at TIMESTAMPTZ, finished_at TIMESTAMPTZ, dry_run BOOLEAN NOT NULL DEFAULT false);
        CREATE SCHEMA archive;
        CREATE TABLE archive.workflow_run_archive (LIKE batch.workflow_run INCLUDING DEFAULTS);
        CREATE TABLE batch.alert_event (tenant_id VARCHAR(64), alert_type VARCHAR(128), detail_json JSONB)
        """;
    jdbcTemplate.execute(createTablesSql);
    for (String name : List.of(
        "V353__workflow_monitoring_policy.sql",
        "V261__scheduled_occurrence_ledger.sql",
        "V263__expected_work_occurrence.sql")) {
      ClassPathResource migration = new ClassPathResource("db/migration/" + name);
      ResourceDatabasePopulator populator = new ResourceDatabasePopulator(migration);
      populator.execute(dataSource);
    }
    JdbcTransactionFactory transactionFactory = new JdbcTransactionFactory();
    Environment environment =
        new Environment("workflow-monitoring", transactionFactory, dataSource);
    Configuration configuration = new Configuration(environment);
    configuration.addMapper(WorkflowMonitoringMapper.class);
    String mapperResource = "mapper/WorkflowMonitoringMapper.xml";
    try (InputStream input = Resources.getResourceAsStream(mapperResource)) {
      XMLMapperBuilder builder = new XMLMapperBuilder(
          input, configuration, mapperResource, configuration.getSqlFragments());
      builder.parse();
    }
    sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  @DisplayName("计划机会已经过期但尚无请求和根运行时，仍产生开始及完成过晚候选")
  void shouldDetectScheduledOpportunityWithoutRun() {
    Fixture fixture = createFixture("UTC");
    long occurrenceId = insertScheduled(fixture);

    List<WorkflowMonitoringAlertCandidate> candidates = select(fixture);

    assertThat(candidates)
        .extracting(WorkflowMonitoringAlertCandidate::violation)
        .containsExactlyInAnyOrder(Violation.NOT_STARTED, Violation.NOT_COMPLETED_BY_DEADLINE);
    assertThat(candidates).allSatisfy(candidate -> {
      assertThat(candidate.workflowRunId()).isNull();
      assertThat(candidate.resourceKey()).isEqualTo("scheduled_occurrence:" + occurrenceId);
    });
  }

  @Test
  @DisplayName("两次扫描之间已经结束的迟启动、迟完成及超长运行仍全部补报")
  void shouldBackfillAllThreeViolationsAfterFinish() {
    Fixture fixture = createFixture("UTC");
    insertScheduled(fixture);
    long runId = insertRun(fixture, LATE_START, LATE_FINISH, false);

    List<WorkflowMonitoringAlertCandidate> candidates = select(fixture);

    assertThat(candidates)
        .extracting(WorkflowMonitoringAlertCandidate::violation)
        .containsExactlyInAnyOrder(
            Violation.NOT_STARTED, Violation.NOT_COMPLETED_BY_DEADLINE, Violation.RUNNING_TOO_LONG);
    assertThat(candidates)
        .allSatisfy(candidate -> assertThat(candidate.workflowRunId()).isEqualTo(runId));
  }

  @Test
  @DisplayName("按时开始、结束且耗时未超限的根运行不产生候选")
  void shouldNotAlertForOnTimeRun() {
    Fixture fixture = createFixture("UTC");
    insertScheduled(fixture);
    Instant onTimeStart = PLANNED_AT.plusSeconds(10);
    Instant onTimeFinish = PLANNED_AT.plusSeconds(30);
    insertRun(fixture, onTimeStart, onTimeFinish, false);

    assertThat(select(fixture)).isEmpty();
  }

  @Test
  @DisplayName("尚无请求的输入预期也保留独立机会身份，不依赖 JobInstance")
  void shouldDetectExpectedInputWithoutRun() {
    Fixture fixture = createFixture("UTC");
    String insertExpectedSql = """
        INSERT INTO batch.expected_work_occurrence (
            tenant_id, calendar_code, biz_date, workflow_definition_id, source_type,
            source_key, expected_at, due_at, occurrence_status)
        VALUES (?, 'calendar', DATE '2026-01-03', ?, 'EXTERNAL_EVENT', 'source', ?, ?, 'EXPECTED') RETURNING id
        """;
    Timestamp expectedAt = Timestamp.from(PLANNED_AT);
    Long occurrenceId = jdbcTemplate.queryForObject(
        insertExpectedSql,
        Long.class,
        fixture.tenantId(),
        fixture.definitionId(),
        expectedAt,
        expectedAt);

    List<WorkflowMonitoringAlertCandidate> candidates = select(fixture);

    assertThat(candidates)
        .hasSize(2)
        .allSatisfy(candidate -> assertThat(candidate.resourceKey())
            .isEqualTo("expected_work_occurrence:" + occurrenceId));
  }

  @Test
  @DisplayName("完成截止使用 Workflow 时区和次日偏移，不使用 JVM 时区")
  void shouldUseWorkflowTimezoneAndDayOffset() {
    Fixture fixture = createFixture("Asia/Shanghai");
    insertScheduled(fixture);
    String updateOffsetSql = """
        UPDATE batch.workflow_monitoring_policy SET completion_deadline_day_offset = 1
        WHERE tenant_id = ? AND workflow_definition_id = ?
        """;
    jdbcTemplate.update(updateOffsetSql, fixture.tenantId(), fixture.definitionId());

    List<WorkflowMonitoringAlertCandidate> candidates = select(fixture);

    assertThat(candidates)
        .filteredOn(candidate -> candidate.violation() == Violation.NOT_COMPLETED_BY_DEADLINE)
        .singleElement()
        .satisfies(candidate -> assertThat(candidate.monitoringDeadlineAt())
            .isEqualTo(Instant.parse("2026-01-03T20:30:00Z")));
  }

  @Test
  @DisplayName("演练根运行及其关联计划不会产生正式时限告警")
  void shouldExcludeDryRunFromAllThreeRules() {
    Fixture fixture = createFixture("UTC");
    insertScheduled(fixture);
    insertRun(fixture, LATE_START, LATE_FINISH, true);

    assertThat(select(fixture)).isEmpty();
  }

  @Test
  @DisplayName("启用策略前已完成的历史运行不会被回溯告警")
  void shouldExcludeRunsFinishedBeforePolicyBecameEffective() {
    Fixture fixture = createFixture("UTC");
    insertScheduled(fixture);
    insertRun(fixture, LATE_START, LATE_FINISH, false);
    String updateEffectiveSql = """
        UPDATE batch.workflow_monitoring_policy SET policy_effective_at = TIMESTAMPTZ '2026-01-10T00:00:00Z'
        WHERE tenant_id = ?
        """;
    jdbcTemplate.update(updateEffectiveSql, fixture.tenantId());

    assertThat(select(fixture)).isEmpty();
  }

  @Test
  @DisplayName("已有某一类告警只抑制同资源同类型，不抑制另外两类")
  void shouldDeduplicateByResourceAndViolation() {
    Fixture fixture = createFixture("UTC");
    long occurrenceId = insertScheduled(fixture);
    insertRun(fixture, LATE_START, LATE_FINISH, false);
    String insertAlertSql = """
        INSERT INTO batch.alert_event (tenant_id, alert_type, detail_json)
        VALUES (?, 'WORKFLOW_NOT_STARTED_BY_DEADLINE', jsonb_build_object('monitoringResourceKey', ?::text))
        """;
    String resourceKey = "scheduled_occurrence:" + occurrenceId;
    jdbcTemplate.update(insertAlertSql, fixture.tenantId(), resourceKey);

    List<WorkflowMonitoringAlertCandidate> candidates = select(fixture);

    assertThat(candidates)
        .extracting(WorkflowMonitoringAlertCandidate::violation)
        .containsExactlyInAnyOrder(Violation.NOT_COMPLETED_BY_DEADLINE, Violation.RUNNING_TOO_LONG);
  }

  @Test
  @DisplayName("机会显式终结后仍能补报此前违约，终结时间不伪装成实际完成")
  void shouldBackfillClosedOpportunityWithoutInventingRunFinish() {
    Fixture fixture = createFixture("UTC");
    insertScheduled(fixture);
    String closeOpportunitySql = """
        UPDATE batch.scheduled_occurrence
        SET occurrence_status = 'SUPERSEDED', updated_at = TIMESTAMPTZ '2026-01-05T02:00:00Z'
        WHERE tenant_id = ?
        """;
    jdbcTemplate.update(closeOpportunitySql, fixture.tenantId());

    assertThat(select(fixture)).hasSize(2).allSatisfy(candidate -> {
      assertThat(candidate.finishedAt()).isNull();
      assertThat(candidate.opportunityClosedAt()).isEqualTo(LATE_FINISH);
    });
  }

  @Test
  @DisplayName("按时完成的根运行迁入冷表后不产生开始或完成过晚告警")
  void shouldPreserveOnTimeFacts_whenRunArchived() {
    Fixture fixture = createFixture("UTC");
    insertScheduled(fixture);
    long runId = insertRun(fixture, PLANNED_AT.plusSeconds(10), PLANNED_AT.plusSeconds(30), false);
    String archiveRunSql = """
        INSERT INTO archive.workflow_run_archive SELECT * FROM batch.workflow_run WHERE id = ?
        """;
    String deleteHotRunSql = """
        DELETE FROM batch.workflow_run WHERE id = ?
        """;
    jdbcTemplate.update(archiveRunSql, runId);
    jdbcTemplate.update(deleteHotRunSql, runId);

    List<WorkflowMonitoringAlertCandidate> candidates = select(fixture);

    assertThat(candidates).isEmpty();
  }

  @Test
  @DisplayName("归档运行保留迟开始、迟完成事实, 干运行仍排除")
  void shouldPreserveArchivedViolationsAndDryRunExclusion() {
    Fixture late = createFixture("UTC");
    insertScheduled(late);
    long lateRun = insertRun(late, LATE_START, LATE_FINISH, false);
    moveRunToArchive(lateRun);
    Fixture dry = createFixture("UTC");
    insertScheduled(dry);
    long dryRun = insertRun(dry, LATE_START, LATE_FINISH, true);
    moveRunToArchive(dryRun);

    List<WorkflowMonitoringAlertCandidate> lateCandidates = select(late);
    List<WorkflowMonitoringAlertCandidate> dryCandidates = select(dry);

    assertThat(lateCandidates)
        .extracting(WorkflowMonitoringAlertCandidate::violation)
        .containsExactlyInAnyOrder(Violation.NOT_STARTED, Violation.NOT_COMPLETED_BY_DEADLINE);
    assertThat(dryCandidates).isEmpty();
  }

  private void moveRunToArchive(long runId) {
    String copyRunSql = """
        INSERT INTO archive.workflow_run_archive SELECT * FROM batch.workflow_run WHERE id = ?
        """;
    String deleteHotRunSql = """
        DELETE FROM batch.workflow_run WHERE id = ?
        """;
    jdbcTemplate.update(copyRunSql, runId);
    jdbcTemplate.update(deleteHotRunSql, runId);
  }

  private Fixture createFixture(String timezone) {
    String tenant = "wf-sla-" + UUID.randomUUID().toString().replace("-", "");
    String insertDefinitionSql = """
        INSERT INTO batch.workflow_definition (tenant_id, workflow_code, timezone) VALUES (?, 'WORKFLOW', ?) RETURNING id
        """;
    Long definitionId =
        jdbcTemplate.queryForObject(insertDefinitionSql, Long.class, tenant, timezone);
    String insertPolicySql = """
        INSERT INTO batch.workflow_monitoring_policy (
            tenant_id, workflow_definition_id, soft_runtime_seconds, start_grace_seconds,
            completion_deadline_local_time, policy_effective_at)
        VALUES (?, ?, 60, 60, TIME '04:30', TIMESTAMPTZ '2026-01-01T00:00:00Z')
        """;
    jdbcTemplate.update(insertPolicySql, tenant, definitionId);
    return new Fixture(tenant, definitionId, "request-" + tenant);
  }

  private long insertScheduled(Fixture fixture) {
    String insertScheduledSql = """
        INSERT INTO batch.scheduled_occurrence (
            tenant_id, workflow_definition_id, workflow_code, schedule_version,
            scheduled_fire_time, biz_date, occurrence_status, request_id)
        VALUES (?, ?, 'WORKFLOW', 1, ?, DATE '2026-01-03', 'REQUESTED', ?) RETURNING id
        """;
    Timestamp plannedAt = Timestamp.from(PLANNED_AT);
    return jdbcTemplate.queryForObject(
        insertScheduledSql,
        Long.class,
        fixture.tenantId(),
        fixture.definitionId(),
        plannedAt,
        fixture.requestId());
  }

  private long insertRun(Fixture fixture, Instant startedAt, Instant finishedAt, boolean dryRun) {
    String insertRequestSql = """
        INSERT INTO batch.trigger_request (tenant_id, request_id, workflow_definition_id, trace_id, dry_run)
        VALUES (?, ?, ?, 'trace', ?) RETURNING id
        """;
    Long requestId = jdbcTemplate.queryForObject(
        insertRequestSql,
        Long.class,
        fixture.tenantId(),
        fixture.requestId(),
        fixture.definitionId(),
        dryRun);
    String insertRunSql = """
        INSERT INTO batch.workflow_run (
            tenant_id, workflow_definition_id, trigger_request_id, started_at, finished_at, dry_run)
        VALUES (?, ?, ?, ?, ?, ?) RETURNING id
        """;
    Timestamp start = Timestamp.from(startedAt);
    Timestamp finish = Timestamp.from(finishedAt);
    return jdbcTemplate.queryForObject(
        insertRunSql,
        Long.class,
        fixture.tenantId(),
        fixture.definitionId(),
        requestId,
        start,
        finish,
        dryRun);
  }

  private List<WorkflowMonitoringAlertCandidate> select(Fixture fixture) {
    WorkflowMonitoringQuery query = WorkflowMonitoringQuery.builder()
        .tenantId(fixture.tenantId())
        .limit(100)
        .build();
    try (SqlSession session = sessionFactory.openSession(false)) {
      WorkflowMonitoringMapper mapper = session.getMapper(WorkflowMonitoringMapper.class);
      mapper.setTenantContext(fixture.tenantId());
      return mapper.selectCandidates(query);
    }
  }

  private record Fixture(String tenantId, Long definitionId, String requestId) {}
}
