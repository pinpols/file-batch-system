package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.service.task.JobInstanceTerminalStatusApplicationService;
import io.github.pinpols.batch.orchestrator.domain.command.JobInstanceTerminalStatusCommand;
import io.github.pinpols.batch.orchestrator.domain.view.JobSlaCandidate;
import io.github.pinpols.batch.orchestrator.infrastructure.sla.JobSlaViolationProcessor;
import io.github.pinpols.batch.orchestrator.mapper.JobMonitoringMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {"batch.sla.enabled=true", "batch.sla.poll-interval-millis=600000"})
@DisplayName("SLA 告警持久化失败后的事务回滚与重试恢复")
class JobSlaSchedulerRecoveryIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final JobSlaViolationProcessor violationProcessor;

  private final JobMonitoringMapper monitoring;

  private final JobInstanceTerminalStatusApplicationService terminals;

  private final PlatformTransactionManager transactions;

  @Autowired
  JobSlaSchedulerRecoveryIntegrationTest(
      JdbcTemplate jdbcTemplate,
      JobSlaViolationProcessor violationProcessor,
      JobMonitoringMapper monitoring,
      JobInstanceTerminalStatusApplicationService terminals,
      @Qualifier("transactionManager") PlatformTransactionManager transactions) {
    this.jdbcTemplate = jdbcTemplate;
    this.violationProcessor = violationProcessor;
    this.monitoring = monitoring;
    this.terminals = terminals;
    this.transactions = transactions;
  }

  @Test
  @DisplayName("告警事件写入失败时回滚 SLA 标记与日志，恢复后可重新处理")
  void shouldRollbackClaimAndRetryAfterAlertEventInsertFailure() {
    final String deleteAlertEventSql = """
        delete from batch.alert_event where tenant_id = ?
    """;
    final String deleteJobExecutionLogSql = """
        delete from batch.job_execution_log where tenant_id = ?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ?
    """;

    String tenantId = "sla-recovery-" + UUID.randomUUID().toString().replace("-", "");
    long definitionId = insertDefinition(tenantId);
    long instanceId = insertOverdueInstance(tenantId, definitionId);
    JobSlaCandidate candidate = new JobSlaCandidate();
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
      jdbcTemplate.update(deleteAlertEventSql, tenantId);
      jdbcTemplate.update(deleteJobExecutionLogSql, tenantId);
      jdbcTemplate.update(deleteJobInstanceSql, tenantId);
      jdbcTemplate.update(deleteJobDefinitionSql, tenantId);
    }
  }

  @Test
  @DisplayName("两个并发扫描器竞争同一实例时只提交一组 SLA 副作用")
  void shouldEmitOnlyOnceWhenTwoScannersRaceForTheSameInstance() throws Exception {
    final String deleteAlertEventSql = """
        delete from batch.alert_event where tenant_id = ?
    """;
    final String deleteJobExecutionLogSql = """
        delete from batch.job_execution_log where tenant_id = ?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ?
    """;

    String tenantId = "sla-race-" + UUID.randomUUID().toString().replace("-", "");
    long definitionId = insertDefinition(tenantId);
    long instanceId = insertOverdueInstance(tenantId, definitionId);
    JobSlaCandidate candidate = new JobSlaCandidate();
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
      jdbcTemplate.update(deleteAlertEventSql, tenantId);
      jdbcTemplate.update(deleteJobExecutionLogSql, tenantId);
      jdbcTemplate.update(deleteJobInstanceSql, tenantId);
      jdbcTemplate.update(deleteJobDefinitionSql, tenantId);
    }
  }

  @Test
  @DisplayName("扫描候选后真实 Job 超时完成,终态提交后仍补发旧 SLA 告警")
  void shouldBackfillLateSlaViolationAfterJobCompleted() {
    final String selectVersionSql = """
        select version from batch.job_instance where tenant_id=? and id=?
    """;

    String tenant = "sla-finished-" + UUID.randomUUID().toString().replace("-", "");
    long definition = insertDefinition(tenant);
    long id = insertOverdueInstance(tenant, definition);
    try {
      JobSlaCandidate candidate = monitoring.selectSlaViolationCandidates(100).stream()
          .filter(row -> tenant.equals(row.getTenantId()) && row.getId().equals(id))
          .findFirst()
          .orElseThrow();
      Long version = jdbcTemplate.queryForObject(selectVersionSql, Long.class, tenant, id);
      assertThat(terminals.updateTerminalStatusAndReconcileChildren(
              new JobInstanceTerminalStatusCommand(tenant, id, "SUCCESS", Instant.now(), version)))
          .isEqualTo(1);
      assertThat(violationProcessor.process(candidate, Instant.now())).isTrue();
      assertThat(slaAlertedAt(tenant, id)).isNotNull();
      assertThat(alertCount(tenant)).isEqualTo(1);
      assertThat(executionLogCount(tenant, id)).isEqualTo(2);
    } finally {
      cleanTenant(tenant);
    }
  }

  @Test
  @DisplayName("dry-run 不进入正式 SLA 首次/升级扫描,旧候选也不能补写告警")
  void shouldExcludeDryRunFromSlaScanAndFinalClaim() {
    final String updateJobInstanceSql = """
      update batch.job_instance set dry_run=true where tenant_id=? and id=?
  """;
    final String updateJobInstanceSql2 = """
      update batch.job_instance set sla_alerted_at=current_timestamp-interval '1 hour' where tenant_id=?\s\
      and id=?
  """;

    String tenant = "sla-dryrun-" + UUID.randomUUID().toString().replace("-", "");
    long definition = insertDefinition(tenant);
    long id = insertOverdueInstance(tenant, definition);
    try {
      JobSlaCandidate original = monitoring.selectSlaViolationCandidates(100).stream()
          .filter(row -> tenant.equals(row.getTenantId()) && row.getId().equals(id))
          .findFirst()
          .orElseThrow();
      jdbcTemplate.update(updateJobInstanceSql, tenant, id);
      assertThat(monitoring.selectSlaViolationCandidates(100))
          .extracting(JobSlaCandidate::getId)
          .doesNotContain(id);
      assertThat(monitoring.countSlaViolationCandidates()).isZero();
      assertThat(violationProcessor.process(original, Instant.now())).isFalse();
      assertThat(slaAlertedAt(tenant, id)).isNull();
      assertThat(alertCount(tenant)).isZero();
      assertThat(executionLogCount(tenant, id)).isZero();
      // 写入持久化的根 Workflow 请求和子 Job 请求夹具，但不派发 Job。
      jdbcTemplate.update(updateJobInstanceSql2, tenant, id);
      assertThat(monitoring.selectSlaEscalationCandidates(Instant.now(), 100))
          .extracting(JobSlaCandidate::getId)
          .doesNotContain(id);
      assertThat(monitoring.countSlaEscalationCandidates(Instant.now())).isZero();
    } finally {
      cleanTenant(tenant);
    }
  }

  @ParameterizedTest
  @EnumSource(JobInstanceStatus.class)
  @DisplayName("SLA 最终准入保持自身 WAITING/READY/RUNNING 策略,不等同于活跃生命周期集合")
  void shouldRecheckSlaSpecificPolicyForEveryJobState(JobInstanceStatus state) {
    final String updateJobInstanceSql = """
        update batch.job_instance set instance_status=? where tenant_id=? and id=?
    """;

    String tenant = "sla-policy-" + UUID.randomUUID().toString().replace("-", "");
    long definition = insertDefinition(tenant);
    long id = insertOverdueInstance(tenant, definition);
    try {
      JobSlaCandidate observed = monitoring.selectSlaViolationCandidates(100).stream()
          .filter(row -> tenant.equals(row.getTenantId()))
          .findFirst()
          .orElseThrow();
      // 创建 Job、受管单作业 Workflow 和明确目标为 Workflow 的 Trigger 记录。
      jdbcTemplate.update(updateJobInstanceSql, state.code(), tenant, id);
      boolean eligible = Set.of("WAITING", "READY", "RUNNING").contains(state.code());
      assertThat(violationProcessor.process(observed, Instant.now())).isEqualTo(eligible);
      assertThat(alertCount(tenant)).isEqualTo(eligible ? 1 : 0);
      assertThat(executionLogCount(tenant, id)).isEqualTo(eligible ? 2 : 0);
    } finally {
      cleanTenant(tenant);
    }
  }

  @Test
  @DisplayName("真实终态事务持行锁时 SLA 等待,提交超时 SUCCESS 后补发告警")
  void shouldBackfillLateSlaViolationAfterWaitingForTerminalCommit() throws Exception {
    final String selectVersionSql = """
        select version from batch.job_instance where tenant_id=? and id=?
    """;
    final String selectSetConfigSql = """
        select set_config('application_name',?,true)
    """;
    final String selectCountSql = """
        select count(*) from pg_stat_activity where application_name=? and wait_event_type='Lock'
    """;

    String tenant = "sla-commit-" + UUID.randomUUID().toString().replace("-", "");
    long definition = insertDefinition(tenant);
    long id = insertOverdueInstance(tenant, definition);
    CountDownLatch terminalWritten = new CountDownLatch(1);
    CountDownLatch releaseCommit = new CountDownLatch(1);
    String application = "sla_wait_" + Long.toUnsignedString(System.nanoTime());
    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      JobSlaCandidate observed = monitoring.selectSlaViolationCandidates(100).stream()
          .filter(row -> tenant.equals(row.getTenantId()))
          .findFirst()
          .orElseThrow();
      Long version = jdbcTemplate.queryForObject(selectVersionSql, Long.class, tenant, id);
      Future<?> terminal = executor.submit(() -> new TransactionTemplate(transactions)
          .executeWithoutResult(status -> {
            assertThat(terminals.updateTerminalStatusAndReconcileChildren(
                    new JobInstanceTerminalStatusCommand(
                        tenant, id, "SUCCESS", Instant.now(), version)))
                .isEqualTo(1);
            terminalWritten.countDown();
            try {
              assertThat(releaseCommit.await(15, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException failure) {
              Thread.currentThread().interrupt();
              throw new IllegalStateException(failure);
            }
          }));
      assertThat(terminalWritten.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Boolean> sla =
          executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
            jdbcTemplate.queryForObject(selectSetConfigSql, String.class, application);
            return violationProcessor.process(observed, Instant.now());
          }));
      try {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
          Integer selectedPgStatActivityCount =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, application);
          assertThat(selectedPgStatActivityCount).isEqualTo(1);
        });
      } finally {
        releaseCommit.countDown();
      }
      terminal.get(15, TimeUnit.SECONDS);
      assertThat(sla.get(15, TimeUnit.SECONDS)).isTrue();
      assertThat(slaAlertedAt(tenant, id)).isNotNull();
      assertThat(alertCount(tenant)).isEqualTo(1);
      assertThat(executionLogCount(tenant, id)).isEqualTo(2);
    } finally {
      releaseCommit.countDown();
      cleanTenant(tenant);
    }
  }

  @Test
  @DisplayName("SLA 升级用当前实体:正常运行可发,已完成或 dry-run 的旧候选不可发")
  void shouldRecheckCurrentJobBeforeEscalation() {
    final String updateJobInstanceSql = """
      update batch.job_instance set sla_alerted_at=current_timestamp-interval '1 hour' where tenant_id=?\s\
      and id=?
  """;
    final String deleteAlertEventSql = """
      delete from batch.alert_event where tenant_id=?
  """;
    final String updateJobInstanceSql2 = """
      update batch.job_instance set dry_run=true where tenant_id=? and id=?
  """;
    final String updateJobInstanceSql3 = """
      update batch.job_instance set dry_run=false where tenant_id=? and id=?
  """;
    final String selectVersionSql = """
        select version from batch.job_instance where tenant_id=? and id=?
    """;

    String tenant = "sla-escalate-" + UUID.randomUUID().toString().replace("-", "");
    long definition = insertDefinition(tenant);
    long id = insertOverdueInstance(tenant, definition);
    try {
      jdbcTemplate.update(updateJobInstanceSql, tenant, id);
      JobSlaCandidate observed =
          monitoring.selectSlaEscalationCandidates(Instant.now(), 100).stream()
              .filter(row -> tenant.equals(row.getTenantId()))
              .findFirst()
              .orElseThrow();
      assertThat(violationProcessor.processEscalation(observed, Instant.now(), 300, "ERROR"))
          .isTrue();
      assertThat(escalationCount(tenant)).isEqualTo(1);
      jdbcTemplate.update(deleteAlertEventSql, tenant);
      jdbcTemplate.update(updateJobInstanceSql2, tenant, id);
      assertThat(violationProcessor.processEscalation(observed, Instant.now(), 300, "ERROR"))
          .isFalse();
      jdbcTemplate.update(updateJobInstanceSql3, tenant, id);
      Long version = jdbcTemplate.queryForObject(selectVersionSql, Long.class, tenant, id);
      assertThat(terminals.updateTerminalStatusAndReconcileChildren(
              new JobInstanceTerminalStatusCommand(tenant, id, "SUCCESS", Instant.now(), version)))
          .isEqualTo(1);
      assertThat(violationProcessor.processEscalation(observed, Instant.now(), 300, "ERROR"))
          .isFalse();
      assertThat(escalationCount(tenant)).isZero();
    } finally {
      cleanTenant(tenant);
    }
  }

  private void cleanTenant(String tenant) {
    final String deleteAlertEventSql = """
        delete from batch.alert_event where tenant_id=?
    """;
    final String deleteJobExecutionLogSql = """
        delete from batch.job_execution_log where tenant_id=?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id=?
    """;

    jdbcTemplate.update(deleteAlertEventSql, tenant);
    jdbcTemplate.update(deleteJobExecutionLogSql, tenant);
    jdbcTemplate.update(deleteJobInstanceSql, tenant);
    jdbcTemplate.update(deleteJobDefinitionSql, tenant);
  }

  private boolean processAfter(CountDownLatch start, JobSlaCandidate candidate) throws Exception {
    start.await();
    return violationProcessor.process(candidate, Instant.now());
  }

  private long insertDefinition(String tenantId) {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone
          ) values (?, ?, 'SLA recovery test', 'GENERAL', 'MANUAL', 'UTC')
          returning id
          """;

    return jdbcTemplate.queryForObject(
        insertJobDefinitionSql, Long.class, tenantId, "SLA_RECOVERY_" + tenantId);
  }

  private long insertOverdueInstance(String tenantId, long definitionId) {
    final String insertJobInstanceSql = """
          insert into batch.job_instance (
            tenant_id, job_definition_id, job_code, instance_no, biz_date, dedup_key,
            instance_status, trigger_type, started_at, deadline_at, expected_duration_seconds
          ) values (?, ?, ?, ?, ?, ?, 'RUNNING', 'MANUAL',
                    current_timestamp - interval '1 hour', current_timestamp - interval '30 minutes', 0)
          returning id
          """;

    return jdbcTemplate.queryForObject(
        insertJobInstanceSql,
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
    final String createFunctionSql = """
    create function batch.%s() returns trigger language plpgsql as $$
    begin
    if new.tenant_id = '%s' and new.alert_type = 'JOB_SLA_VIOLATION' then
    raise exception 'injected SLA alert persistence failure';
    end if;
    return new;
    end
    $$
    """.formatted(functionName, tenantId);
    jdbcTemplate.execute(createFunctionSql);
    final String createTriggerSql = """
    create trigger %s before insert or update on batch.alert_event
    for each row execute function batch.%s()
    """.formatted(triggerName, functionName);
    jdbcTemplate.execute(createTriggerSql);
  }

  private void dropAlertInsertFailureTrigger(String triggerName, String functionName) {
    final String dropTriggerSql = """
        drop trigger if exists %s on batch.alert_event\
        """.formatted(triggerName);
    jdbcTemplate.execute(dropTriggerSql);
    final String dropFunctionSql = """
        drop function if exists batch.%s()\
        """.formatted(functionName);
    jdbcTemplate.execute(dropFunctionSql);
  }

  private void createMarkerDelayTrigger(String tenantId, String triggerName, String functionName) {
    final String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $$
        begin
          if new.tenant_id = '%s' and old.sla_alerted_at is null and new.sla_alerted_at is not null then
            perform pg_sleep(0.5);
          end if;
          return new;
        end
        $$
        """.formatted(functionName, tenantId);
    jdbcTemplate.execute(createFunctionSql);
    final String createTriggerSql = """
        create trigger %s after update on batch.job_instance
        for each row execute function batch.%s()
        """.formatted(triggerName, functionName);
    jdbcTemplate.execute(createTriggerSql);
  }

  private void dropMarkerDelayTrigger(String triggerName, String functionName) {
    final String dropTriggerSql = """
        drop trigger if exists %s on batch.job_instance\
        """.formatted(triggerName);
    jdbcTemplate.execute(dropTriggerSql);
    final String dropFunctionSql = """
        drop function if exists batch.%s()\
        """.formatted(functionName);
    jdbcTemplate.execute(dropFunctionSql);
  }

  private Timestamp slaAlertedAt(String tenantId, long instanceId) {
    final String selectSlaAlertedAtSql = """
        select sla_alerted_at from batch.job_instance where tenant_id = ? and id = ?
    """;

    return jdbcTemplate.queryForObject(
        selectSlaAlertedAtSql, Timestamp.class, tenantId, instanceId);
  }

  private int executionLogCount(String tenantId, long instanceId) {
    final String selectCountSql = """
        select count(*) from batch.job_execution_log where tenant_id = ? and job_instance_id = ?
    """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId, instanceId);
  }

  private int escalationCount(String tenant) {
    final String selectCountSql = """
        select count(*) from batch.alert_event where tenant_id=? and alert_type='JOB_SLA_VIOLATION_ESCALATED'
    """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenant);
  }

  private int alertCount(String tenantId) {
    final String selectCountSql = """
        select count(*) from batch.alert_event where tenant_id = ? and alert_type = 'JOB_SLA_VIOLATION'
    """;

    return jdbcTemplate.queryForObject(selectCountSql, Integer.class, tenantId);
  }
}
