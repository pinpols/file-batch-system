package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.context.TriggerLaunchPersistenceContext;
import io.github.pinpols.batch.orchestrator.domain.query.TriggerLaunchContinuityMetrics;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link TriggerRequestMapper} 的 nullable 参数和状态 CAS 真实 PostgreSQL 回归测试。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("触发器请求持久层:可空关联参数的状态写入与最新一次尝试投影查询在真实数据库上的行为")
class TriggerRequestMapperIntegrationTest extends AbstractIntegrationTest {

  private final String tenantId = "trigger-mapper-" + Long.toUnsignedString(System.nanoTime());
  private final String requestId = "request-" + Long.toUnsignedString(System.nanoTime());

  private final TriggerRequestMapper triggerRequestMapper;
  private final JdbcTemplate jdbcTemplate;

  @Autowired
  TriggerRequestMapperIntegrationTest(
      TriggerRequestMapper triggerRequestMapper, JdbcTemplate jdbcTemplate) {
    this.triggerRequestMapper = triggerRequestMapper;
    this.jdbcTemplate = jdbcTemplate;
  }

  @AfterEach
  void cleanUp() {
    jdbcTemplate.update("delete from batch.job_instance where tenant_id = ?", tenantId);
    jdbcTemplate.update(
        "delete from batch.trigger_request where tenant_id = ? and request_id = ?",
        tenantId,
        requestId);
    jdbcTemplate.update("delete from batch.job_definition where tenant_id = ?", tenantId);
  }

  @Test
  @DisplayName("关联作业实例标识为空时状态写入仍成功,请求状态被更新且关联实例为空,上下文无既有实例")
  void shouldAcceptNullRelatedJobInstanceId_whenUpdatingAcceptance() {
    jdbcTemplate.update("""
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, dedup_key, request_status, trace_id
        ) values (?, ?, 'MANUAL', 'mapper-null-regression', ?, 'ACCEPTED', 'trace-mapper-null')
        """, tenantId, requestId, "dedup-" + requestId);

    int updated = triggerRequestMapper.updateAcceptance(
        tenantId, requestId, TriggerRequestStatus.REJECTED.code(), null);

    TriggerRequestEntity request =
        triggerRequestMapper.selectByTenantAndRequestId(tenantId, requestId);
    TriggerLaunchPersistenceContext context =
        triggerRequestMapper.selectLaunchPersistenceContext(tenantId, requestId);
    assertThat(updated).isOne();
    assertThat(request.getRequestStatus()).isEqualTo(TriggerRequestStatus.REJECTED.code());
    assertThat(request.getRelatedJobInstanceId()).isNull();
    assertThat(context.getTriggerRequest().getId()).isEqualTo(request.getId());
    assertThat(context.getExistingInstance()).isNull();
  }

  @Test
  @DisplayName("空日结算查询仅统计同租户同日历同业务日的未派发请求")
  void shouldCountUnlaunchedRequestsForCalendarBizDate() {
    String jobCode = "empty-settle-" + requestId;
    jdbcTemplate.update("""
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone,
            calendar_code, version, enabled
        ) values (?, ?, 'empty settle job', 'GENERAL', 'MANUAL', 'UTC', 'CAL_A', 1, true)
        """, tenantId, jobCode);
    String otherCalendarJob = jobCode + "-other-calendar";
    jdbcTemplate.update("""
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone,
            calendar_code, version, enabled
        ) values (?, ?, 'other calendar job', 'GENERAL', 'MANUAL', 'UTC', 'CAL_B', 1, true)
        """, tenantId, otherCalendarJob);
    insertBatchDayTriggerRequest(jobCode, "pending", LocalDate.of(2026, 5, 4), "PENDING");
    insertBatchDayTriggerRequest(jobCode, "accepted", LocalDate.of(2026, 5, 4), "ACCEPTED");
    insertBatchDayTriggerRequest(jobCode, "launched", LocalDate.of(2026, 5, 4), "LAUNCHED");
    insertBatchDayTriggerRequest(jobCode, "other-date", LocalDate.of(2026, 5, 5), "PENDING");
    insertBatchDayTriggerRequest(
        otherCalendarJob, "other-calendar", LocalDate.of(2026, 5, 4), "PENDING");

    int count = triggerRequestMapper.countUnlaunchedForCalendarBizDate(
        tenantId,
        "CAL_A",
        LocalDate.of(2026, 5, 4),
        java.util.List.of("PENDING", "PROCESSING", "ACCEPTED", "WAITING"));

    assertThat(count).isEqualTo(2);
  }

  private void insertBatchDayTriggerRequest(
      String jobCode, String suffix, LocalDate bizDate, String status) {
    String id = requestId + "-" + suffix;
    jdbcTemplate.update("""
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,
            request_status, trace_id
        ) values (?, ?, 'SCHEDULED', ?, ?, ?, ?, ?)
        """, tenantId, id, jobCode, bizDate, "dedup-" + id, status, "trace-" + id);
  }

  @Test
  @DisplayName("多次重试后查询启动上下文时返回最新一次尝试的实例,并带出所属请求与去重键")
  void shouldReturnLatestAttemptProjection_whenSelectingLaunchContext() {
    String dedupKey = "dedup-" + requestId;
    Long jobDefinitionId = jdbcTemplate.queryForObject("""
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone, version, enabled
        ) values (?, 'launch-context-job', 'launch context job', 'GENERAL', 'MANUAL', 'UTC', 1, true)
        returning id
        """, Long.class, tenantId);
    Long triggerRequestId =
        jdbcTemplate.queryForObject("""
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, dedup_key, request_status, trace_id
        ) values (?, ?, 'MANUAL', 'launch-context-job', ?, 'ACCEPTED', 'trigger-trace')
        returning id
        """, Long.class, tenantId, requestId, dedupKey);
    insertJobInstance(jobDefinitionId, triggerRequestId, dedupKey, 1, "instance-1", "trace-1");
    Long latestId =
        insertJobInstance(jobDefinitionId, triggerRequestId, dedupKey, 2, "instance-2", "trace-2");

    TriggerLaunchPersistenceContext context =
        triggerRequestMapper.selectLaunchPersistenceContext(tenantId, requestId);

    assertThat(context).isNotNull();
    assertThat(context.getTriggerRequest().getId()).isEqualTo(triggerRequestId);
    assertThat(context.getTriggerRequest().getDedupKey()).isEqualTo(dedupKey);
    assertThat(context.getExistingInstance().getId()).isEqualTo(latestId);
    assertThat(context.getExistingInstance().getTriggerRequestId()).isEqualTo(triggerRequestId);
    assertThat(context.getExistingInstance().getInstanceNo()).isEqualTo("instance-2");
    assertThat(context.getExistingInstance().getTraceId()).isEqualTo("trace-2");
  }

  @Test
  @DisplayName("积压指标区分真正未建实例与CREATED恢复态,并计算最近请求到实例p95")
  void shouldMeasureStaleAcceptedRequestsAndRecentLaunchLatency() {
    String metricsTenant = tenantId + "-metrics";
    Long jobDefinitionId = jdbcTemplate.queryForObject("""
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone, version, enabled
        ) values (?, 'metrics-launch-job', 'metrics launch job', 'GENERAL', 'MANUAL', 'UTC', 1, true)
        returning id
        """, Long.class, metricsTenant);
    try {
      Long noInstanceRequest = insertAcceptedRequest(
          metricsTenant, "no-instance", "no-instance-key", Instant.now().minusSeconds(7_200));
      Long createdOnlyRequest = insertAcceptedRequest(
          metricsTenant, "created-only", "created-only-key", Instant.now().minusSeconds(3_600));
      Long completedButUnlinkedRequest = insertAcceptedRequest(
          metricsTenant,
          "completed-unlinked",
          "completed-unlinked-key",
          Instant.now().minusSeconds(1_800));
      Long launchedRequest = insertAcceptedRequest(
          metricsTenant, "launched", "launched-key", Instant.now().minusSeconds(60));

      insertMetricInstance(
          jobDefinitionId,
          metricsTenant,
          noInstanceRequest,
          "no-instance-key",
          "CREATED",
          Instant.now().minusSeconds(7_199));
      insertMetricInstance(
          jobDefinitionId,
          metricsTenant,
          createdOnlyRequest,
          "created-only-key",
          "CREATED",
          Instant.now().minusSeconds(3_599));
      insertMetricInstance(
          jobDefinitionId,
          metricsTenant,
          completedButUnlinkedRequest,
          "completed-unlinked-key",
          "SUCCESS",
          Instant.now().minusSeconds(1_799));
      Long launchedInstance = insertMetricInstance(
          jobDefinitionId,
          metricsTenant,
          launchedRequest,
          "launched-key",
          "SUCCESS",
          Instant.now().minusSeconds(56));
      jdbcTemplate.update(
          "update batch.trigger_request set request_status = 'LAUNCHED', related_job_instance_id = ?"
              + " where tenant_id = ? and id = ?",
          launchedInstance,
          metricsTenant,
          launchedRequest);

      TriggerLaunchContinuityMetrics metrics = triggerRequestMapper.selectLaunchContinuityMetrics(
          Instant.now().minusSeconds(300), Instant.now().minusSeconds(900));

      assertThat(metrics.staleAcceptedCount()).isEqualTo(2L);
      assertThat(metrics.oldestStaleAcceptedAgeSeconds()).isBetween(7_190L, 7_210L);
      assertThat(metrics.recentLaunchedCount()).isEqualTo(1L);
      assertThat(metrics.requestToInstanceP95Seconds()).isBetween(3.0, 6.0);
    } finally {
      jdbcTemplate.update("delete from batch.job_instance where tenant_id = ?", metricsTenant);
      jdbcTemplate.update("delete from batch.trigger_request where tenant_id = ?", metricsTenant);
      jdbcTemplate.update("delete from batch.job_definition where tenant_id = ?", metricsTenant);
    }
  }

  private Long insertAcceptedRequest(
      String metricsTenant, String key, String dedupKey, Instant createdAt) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.trigger_request (
            tenant_id, request_id, trigger_type, job_code, dedup_key, request_status,
            created_at, updated_at
        ) values (?, ?, 'MANUAL', 'metrics-launch-job', ?, 'ACCEPTED', ?, ?)
        returning id
        """,
        Long.class,
        metricsTenant,
        "metrics-" + key,
        dedupKey,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
  }

  private Long insertMetricInstance(
      Long jobDefinitionId,
      String metricsTenant,
      Long requestId,
      String dedupKey,
      String status,
      Instant createdAt) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_instance (
            tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
            biz_date, trigger_type, instance_status, worker_group, dedup_key,
            run_attempt, created_at, updated_at
        ) values (?, ?, ?, 'metrics-launch-job', ?, ?, 'MANUAL', ?, 'default', ?, 1, ?, ?)
        returning id
        """,
        Long.class,
        metricsTenant,
        jobDefinitionId,
        requestId,
        "metrics-instance-" + requestId,
        LocalDate.of(2026, 10, 14),
        status,
        dedupKey,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
  }

  private Long insertJobInstance(
      Long jobDefinitionId,
      Long triggerRequestId,
      String dedupKey,
      int runAttempt,
      String instanceNo,
      String traceId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_instance (
            tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
            biz_date, trigger_type, instance_status, worker_group, dedup_key,
            run_attempt, trace_id
        ) values (?, ?, ?, 'launch-context-job', ?, current_date, 'MANUAL',
                  'SUCCESS', 'default', ?, ?, ?)
        returning id
        """,
        Long.class,
        tenantId,
        jobDefinitionId,
        triggerRequestId,
        instanceNo,
        dedupKey,
        runAttempt,
        traceId);
  }
}
