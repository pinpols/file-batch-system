package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.job.entity.JobInstanceEntity;
import io.github.pinpols.batch.console.domain.job.mapper.JobInstanceMapper;
import io.github.pinpols.batch.console.domain.job.query.JobInstanceQuery;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 集成测试：控制台 JobInstanceMapper 对真实数据库的查询验证。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("作业实例查询映射: 按状态,作业编码,链路标识过滤与分页上限")
class JobInstanceQueryIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("无数据租户: 查询返回空列表")
  void shouldReturnEmptyWhenNoJobInstancesExist() {
    JobInstanceQuery query = JobInstanceQuery.builder()
        .tenantId("no-such-tenant-" + BatchDateTimeSupport.utcEpochMillis())
        .build();
    List<JobInstanceEntity> results = jobInstanceMapper.selectByQuery(query);

    assertThat(results).isEmpty();
  }

  @Test
  @DisplayName("按状态过滤: 只返回运行中的那一条实例")
  void shouldQueryJobInstancesByStatus() {
    String tenantId = "t-job-query-" + BatchDateTimeSupport.utcEpochMillis();
    String jobCode = "TEST_JOB_" + BatchDateTimeSupport.utcEpochMillis();

    insertJobInstance(
        tenantId,
        jobCode,
        "RUNNING",
        "INST-001-" + BatchDateTimeSupport.utcEpochMillis(),
        "trace-001");
    insertJobInstance(
        tenantId,
        jobCode,
        "SUCCESS",
        "INST-002-" + BatchDateTimeSupport.utcEpochMillis(),
        "trace-002");
    insertJobInstance(
        tenantId,
        jobCode,
        "FAILED",
        "INST-003-" + BatchDateTimeSupport.utcEpochMillis(),
        "trace-003");

    JobInstanceQuery query =
        JobInstanceQuery.builder().tenantId(tenantId).instanceStatus("RUNNING").build();
    List<JobInstanceEntity> runningJobs = jobInstanceMapper.selectByQuery(query);

    assertThat(runningJobs).hasSize(1);
    assertThat(runningJobs.get(0).getInstanceStatus()).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("按作业编码过滤: 只返回该作业的一条实例")
  void shouldQueryJobInstancesByJobCode() {
    String tenantId = "t-job-code-" + BatchDateTimeSupport.utcEpochMillis();
    String jobCodeA = "JOB_A_" + BatchDateTimeSupport.utcEpochMillis();
    String jobCodeB = "JOB_B_" + BatchDateTimeSupport.utcEpochMillis();

    insertJobInstance(
        tenantId,
        jobCodeA,
        "SUCCESS",
        "INST-A1-" + BatchDateTimeSupport.utcEpochMillis(),
        "trace-a1");
    insertJobInstance(
        tenantId,
        jobCodeB,
        "SUCCESS",
        "INST-B1-" + BatchDateTimeSupport.utcEpochMillis(),
        "trace-b1");

    JobInstanceQuery query =
        JobInstanceQuery.builder().tenantId(tenantId).jobCode(jobCodeA).build();
    List<JobInstanceEntity> jobAInstances = jobInstanceMapper.selectByQuery(query);

    assertThat(jobAInstances).hasSize(1);
    assertThat(jobAInstances.get(0).getJobCode()).isEqualTo(jobCodeA);
  }

  @Test
  @DisplayName("按链路标识过滤: 只命中唯一标识对应的一条实例")
  void shouldQueryJobInstancesByTraceId() {
    String tenantId = "t-trace-" + BatchDateTimeSupport.utcEpochMillis();
    String traceId = "trace-unique-" + BatchDateTimeSupport.utcEpochMillis();

    insertJobInstance(
        tenantId,
        "TEST_JOB",
        "RUNNING",
        "INST-T1-" + BatchDateTimeSupport.utcEpochMillis(),
        traceId);

    JobInstanceQuery query =
        JobInstanceQuery.builder().tenantId(tenantId).traceId(traceId).build();
    List<JobInstanceEntity> results = jobInstanceMapper.selectByQuery(query);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).getTraceId()).isEqualTo(traceId);
  }

  @Test
  @DisplayName("分页上限: 写入五条,首页只返回三条")
  void shouldRespectPageLimit() {
    String tenantId = "t-page-" + BatchDateTimeSupport.utcEpochMillis();

    for (int i = 0; i < 5; i++) {
      insertJobInstance(
          tenantId,
          "TEST_JOB",
          "SUCCESS",
          "INST-PAGE-" + i + "-" + BatchDateTimeSupport.utcEpochMillis(),
          "trace-page-" + i);
    }

    JobInstanceQuery query = JobInstanceQuery.builder()
        .tenantId(tenantId)
        .pageRequest(new PageRequest(0, 3))
        .build();
    List<JobInstanceEntity> page1 = jobInstanceMapper.selectByQuery(query);

    assertThat(page1).hasSize(3);
  }

  @Test
  @Transactional
  @DisplayName("SLA 超期筛选与运维计数按业务状态、截止时间和租户一致")
  void shouldKeepSlaFilterAndOpsCountConsistent() {
    String tenantId = "sla-query-" + UUID.randomUUID();
    String otherTenant = "sla-query-" + UUID.randomUUID();
    String jobCode = "SLA_JOB";
    List<String> eligible = List.of("CREATED", "WAITING", "READY", "RUNNING", "PARTIAL_FAILED");
    List<String> excluded = List.of(
        "PAUSED",
        "SUCCESS",
        "FAILED",
        "CANCELLED",
        "TERMINATED",
        "SUCCESS_DRY_RUN",
        "FAILED_DRY_RUN");
    for (String status : eligible) {
      insertSlaInstance(tenantId, jobCode, status, "expired-" + status, "past");
    }
    for (String status : excluded) {
      insertSlaInstance(tenantId, jobCode, status, "excluded-" + status, "past");
    }
    insertSlaInstance(tenantId, jobCode, "RUNNING", "future", "future");
    insertSlaInstance(tenantId, jobCode, "RUNNING", "no-deadline", "none");
    insertSlaInstance(otherTenant, jobCode, "RUNNING", "other-tenant", "past");

    JobInstanceQuery query = JobInstanceQuery.builder()
        .tenantId(tenantId)
        .slaBreached(true)
        .pageRequest(new PageRequest(0, 20))
        .build();
    assertThat(jobInstanceMapper.selectByQuery(query))
        .extracting(JobInstanceEntity::getInstanceStatus)
        .containsExactlyInAnyOrderElementsOf(eligible);
    assertThat(jobInstanceMapper.countByQuery(query)).isEqualTo(5);
    assertThat(jobInstanceMapper.countSlaBreaches(tenantId, eligible)).isEqualTo(5);
  }

  private void insertSlaInstance(
      String tenantId, String jobCode, String status, String instanceNo, String deadlineKind) {
    insertJobInstance(tenantId, jobCode, status, instanceNo, "trace-" + instanceNo);
    Timestamp deadline =
        switch (deadlineKind) {
          case "past" -> Timestamp.from(Instant.now().minusSeconds(3600));
          case "future" -> Timestamp.from(Instant.now().plusSeconds(3600));
          default -> null;
        };
    String updateDeadlineSql = """
        update batch.job_instance set deadline_at = ? where tenant_id = ? and instance_no = ?
        """;
    jdbcTemplate.update(updateDeadlineSql, deadline, tenantId, instanceNo);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void insertJobInstance(
      String tenantId, String jobCode, String status, String instanceNo, String traceId) {
    long jobDefinitionId = ensureJobDefinitionId(tenantId, jobCode);

    final String insertJobInstanceSql = """
    INSERT INTO batch.job_instance
    (tenant_id, job_definition_id, job_code, instance_no, biz_date,
    trigger_type, instance_status, priority, dedup_key, trace_id,
    created_at, updated_at)
    VALUES (?, ?, ?, ?, ?,
    'MANUAL', ?, 5, ?, ?,
    now(), now())
    """;
    jdbcTemplate.update(
        insertJobInstanceSql,
        tenantId,
        jobDefinitionId,
        jobCode,
        instanceNo,
        Date.valueOf(LocalDate.now()),
        status,
        tenantId + ":" + instanceNo,
        traceId);
  }

  private long ensureJobDefinitionId(String tenantId, String jobCode) {
    String findJobDefinitionIdSql = """
        select id from batch.job_definition where tenant_id = ? and job_code = ? limit 1
        """;
    Long existing = jdbcTemplate.query(
        findJobDefinitionIdSql, rs -> rs.next() ? rs.getLong(1) : null, tenantId, jobCode);
    if (existing != null) {
      return existing;
    }
    String insertJobDefinitionSql = """
        INSERT INTO batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, created_at, updated_at)
        VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'Asia/Shanghai', now(), now())
        RETURNING id
        """;
    return jdbcTemplate.queryForObject(
        insertJobDefinitionSql, Long.class, tenantId, jobCode, jobCode + "-name");
  }
}
