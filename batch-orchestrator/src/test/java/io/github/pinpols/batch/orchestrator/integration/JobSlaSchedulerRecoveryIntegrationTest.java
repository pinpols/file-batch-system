package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.sla.JobSlaViolationProcessor;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"batch.sla.enabled=true", "batch.sla.poll-interval-millis=600000"})
@DisplayName("SLA 告警持久化失败后的事务回滚与重试恢复")
class JobSlaSchedulerRecoveryIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final JobSlaViolationProcessor violationProcessor;

  @Autowired
  JobSlaSchedulerRecoveryIntegrationTest(
      JdbcTemplate jdbcTemplate, JobSlaViolationProcessor violationProcessor) {
    this.jdbcTemplate = jdbcTemplate;
    this.violationProcessor = violationProcessor;
  }

  @Test
  @DisplayName("告警事件写入失败时回滚 SLA 标记与日志，恢复后可重新处理")
  void shouldRollbackClaimAndRetryAfterAlertEventInsertFailure() {
    String tenantId = "sla-recovery-" + UUID.randomUUID().toString().replace("-", "");
    long definitionId = insertDefinition(tenantId);
    long instanceId = insertOverdueInstance(tenantId, definitionId);
    JobInstanceEntity candidate = new JobInstanceEntity();
    candidate.setId(instanceId);
    candidate.setTenantId(tenantId);
    candidate.setInstanceNo("SLA_RECOVERY_INSTANCE_" + tenantId);
    candidate.setJobCode("SLA_RECOVERY_" + tenantId);
    candidate.setInstanceStatus("RUNNING");
    candidate.setDeadlineAt(Instant.now().minusSeconds(30 * 60));
    String triggerName = "fail_sla_alert_" + UUID.randomUUID().toString().replace("-", "");
    String functionName = triggerName + "_fn";
    createAlertInsertFailureTrigger(tenantId, triggerName, functionName);
    Instant now = Instant.now();

    try {
      assertThatThrownBy(() -> violationProcessor.process(candidate, now))
          .isInstanceOf(DataAccessException.class);
      assertThat(slaAlertedAt(tenantId, instanceId)).isNull();
      assertThat(executionLogCount(tenantId, instanceId)).isZero();
      assertThat(alertCount(tenantId)).isZero();

      dropAlertInsertFailureTrigger(triggerName, functionName);
      assertThat(violationProcessor.process(candidate, now)).isTrue();
      assertThat(violationProcessor.process(candidate, now)).isFalse();
      assertThat(slaAlertedAt(tenantId, instanceId)).isNotNull();
      assertThat(executionLogCount(tenantId, instanceId)).isEqualTo(2);
      assertThat(alertCount(tenantId)).isEqualTo(1);
    } finally {
      dropAlertInsertFailureTrigger(triggerName, functionName);
      jdbcTemplate.update("delete from batch.alert_event where tenant_id = ?", tenantId);
      jdbcTemplate.update("delete from batch.job_execution_log where tenant_id = ?", tenantId);
      jdbcTemplate.update("delete from batch.job_instance where tenant_id = ?", tenantId);
      jdbcTemplate.update("delete from batch.job_definition where tenant_id = ?", tenantId);
    }
  }

  @Test
  @DisplayName("两个并发扫描器竞争同一实例时只提交一组 SLA 副作用")
  void shouldEmitOnlyOnceWhenTwoScannersRaceForTheSameInstance() throws Exception {
    String tenantId = "sla-race-" + UUID.randomUUID().toString().replace("-", "");
    long definitionId = insertDefinition(tenantId);
    long instanceId = insertOverdueInstance(tenantId, definitionId);
    JobInstanceEntity candidate = new JobInstanceEntity();
    candidate.setId(instanceId);
    candidate.setTenantId(tenantId);
    candidate.setInstanceNo("SLA_RACE_INSTANCE_" + tenantId);
    candidate.setJobCode("SLA_RACE_" + tenantId);
    candidate.setInstanceStatus("RUNNING");
    candidate.setDeadlineAt(Instant.now().minusSeconds(30 * 60));
    String triggerName = "delay_sla_marker_" + UUID.randomUUID().toString().replace("-", "");
    String functionName = triggerName + "_fn";
    createMarkerDelayTrigger(tenantId, triggerName, functionName);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      Future<Boolean> first = executor.submit(() -> processAfter(start, candidate));
      Future<Boolean> second = executor.submit(() -> processAfter(start, candidate));
      start.countDown();

      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
      assertThat(slaAlertedAt(tenantId, instanceId)).isNotNull();
      assertThat(executionLogCount(tenantId, instanceId)).isEqualTo(2);
      assertThat(alertCount(tenantId)).isEqualTo(1);
    } finally {
      dropMarkerDelayTrigger(triggerName, functionName);
      jdbcTemplate.update("delete from batch.alert_event where tenant_id = ?", tenantId);
      jdbcTemplate.update("delete from batch.job_execution_log where tenant_id = ?", tenantId);
      jdbcTemplate.update("delete from batch.job_instance where tenant_id = ?", tenantId);
      jdbcTemplate.update("delete from batch.job_definition where tenant_id = ?", tenantId);
    }
  }

  private boolean processAfter(CountDownLatch start, JobInstanceEntity candidate) throws Exception {
    start.await();
    return violationProcessor.process(candidate, Instant.now());
  }

  private long insertDefinition(String tenantId) {
    return jdbcTemplate.queryForObject("""
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type, schedule_type, timezone
        ) values (?, ?, 'SLA recovery test', 'GENERAL', 'MANUAL', 'UTC')
        returning id
        """, Long.class, tenantId, "SLA_RECOVERY_" + tenantId);
  }

  private long insertOverdueInstance(String tenantId, long definitionId) {
    return jdbcTemplate.queryForObject(
        """
        insert into batch.job_instance (
          tenant_id, job_definition_id, job_code, instance_no, biz_date, dedup_key,
          instance_status, trigger_type, started_at, deadline_at, expected_duration_seconds
        ) values (?, ?, ?, ?, ?, ?, 'RUNNING', 'MANUAL',
                  current_timestamp - interval '1 hour', current_timestamp - interval '30 minutes', 0)
        returning id
        """,
        Long.class,
        tenantId,
        definitionId,
        "SLA_RECOVERY_" + tenantId,
        "SLA_RECOVERY_INSTANCE_" + tenantId,
        LocalDate.now(),
        "SLA_RECOVERY_DEDUP_" + tenantId);
  }

  private void createAlertInsertFailureTrigger(
      String tenantId, String triggerName, String functionName) {
    jdbcTemplate.execute("""
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          if new.tenant_id = '%s' and new.alert_type = 'JOB_SLA_VIOLATION' then
            raise exception 'injected SLA alert persistence failure';
          end if;
          return new;
        end
        $$
        """.formatted(functionName, tenantId));
    jdbcTemplate.execute("""
        create trigger %s before insert or update on batch.alert_event
        for each row execute function batch.%s()
        """.formatted(triggerName, functionName));
  }

  private void dropAlertInsertFailureTrigger(String triggerName, String functionName) {
    jdbcTemplate.execute("drop trigger if exists " + triggerName + " on batch.alert_event");
    jdbcTemplate.execute("drop function if exists batch." + functionName + "()");
  }

  private void createMarkerDelayTrigger(String tenantId, String triggerName, String functionName) {
    jdbcTemplate.execute("""
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          if new.tenant_id = '%s' and old.sla_alerted_at is null and new.sla_alerted_at is not null then
            perform pg_sleep(0.5);
          end if;
          return new;
        end
        $$
        """.formatted(functionName, tenantId));
    jdbcTemplate.execute("""
        create trigger %s after update on batch.job_instance
        for each row execute function batch.%s()
        """.formatted(triggerName, functionName));
  }

  private void dropMarkerDelayTrigger(String triggerName, String functionName) {
    jdbcTemplate.execute("drop trigger if exists " + triggerName + " on batch.job_instance");
    jdbcTemplate.execute("drop function if exists batch." + functionName + "()");
  }

  private java.sql.Timestamp slaAlertedAt(String tenantId, long instanceId) {
    return jdbcTemplate.queryForObject(
        "select sla_alerted_at from batch.job_instance where tenant_id = ? and id = ?",
        java.sql.Timestamp.class,
        tenantId,
        instanceId);
  }

  private int executionLogCount(String tenantId, long instanceId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from batch.job_execution_log where tenant_id = ? and job_instance_id = ?",
        Integer.class,
        tenantId,
        instanceId);
  }

  private int alertCount(String tenantId) {
    return jdbcTemplate.queryForObject(
        "select count(*) from batch.alert_event where tenant_id = ? and alert_type = 'JOB_SLA_VIOLATION'",
        Integer.class,
        tenantId);
  }
}
