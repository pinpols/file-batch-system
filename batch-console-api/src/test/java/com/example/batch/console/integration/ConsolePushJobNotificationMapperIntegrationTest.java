package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.domain.entity.ConsolePushJobNotificationEntity;
import com.example.batch.console.mapper.ConsolePushJobNotificationMapper;
import com.example.batch.console.support.push.PendingJobNotification;
import com.example.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** ConsolePushJobNotificationMapper IT:验证 SQL 过滤 + ON CONFLICT 幂等。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ConsolePushJobNotificationMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ConsolePushJobNotificationMapper mapper;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void findPendingShouldReturnTerminalInstancesWithOperator() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_OK");
    long instanceId = insertJobInstance(tenant, defId, "JOB_OK", "SUCCESS", "alice", "0 minute");

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending).extracting(PendingJobNotification::getJobInstanceId).contains(instanceId);
    PendingJobNotification mine =
        pending.stream().filter(p -> p.getJobInstanceId().equals(instanceId)).findFirst().get();
    assertThat(mine.getOperatorId()).isEqualTo("alice");
    assertThat(mine.getInstanceStatus()).isEqualTo("SUCCESS");
    assertThat(mine.getJobCode()).isEqualTo("JOB_OK");
  }

  @Test
  void findPendingShouldExcludeNullOperator() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_SCHED");
    long instanceId = insertJobInstance(tenant, defId, "JOB_SCHED", "SUCCESS", null, "0 minute");

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingJobNotification::getJobInstanceId)
        .doesNotContain(instanceId);
  }

  @Test
  void findPendingShouldExcludeNonTerminalStatus() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_RUN");
    long instanceId = insertJobInstance(tenant, defId, "JOB_RUN", "RUNNING", "alice", "0 minute");
    // RUNNING 的 finished_at 强制设 null 也合理,这里测状态过滤
    jdbc.update("update batch.job_instance set finished_at = null where id = ?", instanceId);

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingJobNotification::getJobInstanceId)
        .doesNotContain(instanceId);
  }

  @Test
  void findPendingShouldExcludeOutsideLookbackWindow() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_OLD");
    long instanceId = insertJobInstance(tenant, defId, "JOB_OLD", "SUCCESS", "alice", "30 minute");

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingJobNotification::getJobInstanceId)
        .doesNotContain(instanceId);
  }

  @Test
  void findPendingShouldExcludeAlreadyNotified() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_DONE");
    long instanceId = insertJobInstance(tenant, defId, "JOB_DONE", "SUCCESS", "alice", "0 minute");
    ConsolePushJobNotificationEntity n = new ConsolePushJobNotificationEntity();
    n.setTenantId(tenant);
    n.setJobInstanceId(instanceId);
    mapper.insertIgnore(n);

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingJobNotification::getJobInstanceId)
        .doesNotContain(instanceId);
  }

  @Test
  void insertIgnoreShouldReturnOneFirstThenZeroOnConflict() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    ConsolePushJobNotificationEntity n = new ConsolePushJobNotificationEntity();
    n.setTenantId(tenant);
    n.setJobInstanceId(999_999L);

    int first = mapper.insertIgnore(n);
    int second = mapper.insertIgnore(n);

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private long ensureJobDefinition(String tenantId, String jobCode) {
    Long existing =
        jdbc.query(
            "select id from batch.job_definition where tenant_id = ? and job_code = ? limit 1",
            rs -> rs.next() ? rs.getLong(1) : null,
            tenantId,
            jobCode);
    if (existing != null) {
      return existing;
    }
    return jdbc.queryForObject(
        """
        INSERT INTO batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, created_at, updated_at)
        VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'Asia/Shanghai', now(), now())
        RETURNING id
        """,
        Long.class,
        tenantId,
        jobCode,
        jobCode + "-name");
  }

  private long insertJobInstance(
      String tenantId,
      long jobDefinitionId,
      String jobCode,
      String status,
      String operatorId,
      String finishedAgo) {
    String instanceNo = jobCode + "-" + System.nanoTime();
    Long id =
        jdbc.queryForObject(
            """
            INSERT INTO batch.job_instance
              (tenant_id, job_definition_id, job_code, instance_no, biz_date,
               trigger_type, instance_status, priority, dedup_key, trace_id,
               operator_id, finished_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?,
                    'MANUAL', ?, 5, ?, ?,
                    ?, now() - (?::interval), now(), now())
            RETURNING id
            """,
            Long.class,
            tenantId,
            jobDefinitionId,
            jobCode,
            instanceNo,
            Date.valueOf(LocalDate.now()),
            status,
            tenantId + ":" + instanceNo,
            "trace-" + instanceNo,
            operatorId,
            finishedAgo);
    return id;
  }
}
