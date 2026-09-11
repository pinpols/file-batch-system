package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.TriggerLaunchPersistenceContext;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@link TriggerRequestMapper} 的 nullable 参数和状态 CAS 真实 PostgreSQL 回归测试。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
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
  void updateAcceptanceAcceptsNullRelatedJobInstanceId() {
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
  void selectLaunchPersistenceContextReturnsLatestAttemptProjection() {
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
