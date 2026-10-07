package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.notification.entity.ConsolePushJobNotificationEntity;
import io.github.pinpols.batch.console.domain.notification.mapper.ConsolePushJobNotificationMapper;
import io.github.pinpols.batch.console.domain.notification.support.PendingJobNotification;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** ConsolePushJobNotificationMapper IT:验证 SQL 过滤 + ON CONFLICT 幂等。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("作业通知待发查询映射: 终态筛选,执行人与时窗过滤及幂等写入")
class ConsolePushJobNotificationMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ConsolePushJobNotificationMapper mapper;

  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("筛选待通知作业实例: 成功的终态实例被选中,执行人,状态与作业编码带出")
  void shouldReturnTerminalInstances_whenInstanceEligible() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_OK");
    long instanceId = insertJobInstance(tenant, defId, "JOB_OK", "SUCCESS", "alice", "0 minute");

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending).extracting(PendingJobNotification::getJobInstanceId).contains(instanceId);
    PendingJobNotification mine = pending.stream()
        .filter(p -> p.getJobInstanceId().equals(instanceId))
        .findFirst()
        .get();
    assertThat(mine.getOperatorId()).isEqualTo("alice");
    assertThat(mine.getInstanceStatus()).isEqualTo("SUCCESS");
    assertThat(mine.getJobCode()).isEqualTo("JOB_OK");
  }

  @Test
  @DisplayName("缺少执行人的实例: 不出现在待通知列表中")
  void shouldExcludeInstances_whenOperatorMissing() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_SCHED");
    long instanceId = insertJobInstance(tenant, defId, "JOB_SCHED", "SUCCESS", null, "0 minute");
    long controlId = insertEligibleJobInstance(tenant);

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingJobNotification::getJobInstanceId)
        .contains(controlId)
        .doesNotContain(instanceId);
  }

  @Test
  @DisplayName("非终态实例: 不出现在待通知列表中")
  void shouldExcludeInstances_whenStatusNotTerminal() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_RUN");
    long instanceId = insertJobInstance(tenant, defId, "JOB_RUN", "RUNNING", "alice", "0 minute");
    long controlId = insertEligibleJobInstance(tenant);
    // RUNNING 的 finished_at 强制设 null 也合理,这里测状态过滤
    jdbc.update("update batch.job_instance set finished_at = null where id = ?", instanceId);

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingJobNotification::getJobInstanceId)
        .contains(controlId)
        .doesNotContain(instanceId);
  }

  @Test
  @DisplayName("完成时间超出回溯窗口的实例: 不出现在待通知列表中")
  void shouldExcludeInstances_whenOutsideLookbackWindow() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_OLD");
    long instanceId = insertJobInstance(tenant, defId, "JOB_OLD", "SUCCESS", "alice", "30 minute");
    long controlId = insertEligibleJobInstance(tenant);

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingJobNotification::getJobInstanceId)
        .contains(controlId)
        .doesNotContain(instanceId);
  }

  @Test
  @DisplayName("已登记通知的实例: 不出现在待通知列表中")
  void shouldExcludeInstances_whenAlreadyNotified() {
    String tenant = "t-push-" + BatchDateTimeSupport.utcEpochMillis();
    long defId = ensureJobDefinition(tenant, "JOB_DONE");
    long instanceId = insertJobInstance(tenant, defId, "JOB_DONE", "SUCCESS", "alice", "0 minute");
    ConsolePushJobNotificationEntity n = new ConsolePushJobNotificationEntity();
    n.setTenantId(tenant);
    n.setJobInstanceId(instanceId);
    mapper.insertIgnore(n);
    long controlId = insertEligibleJobInstance(tenant);

    List<PendingJobNotification> pending = mapper.findPending(10, 50);

    assertThat(pending)
        .extracting(PendingJobNotification::getJobInstanceId)
        .contains(controlId)
        .doesNotContain(instanceId);
  }

  @Test
  @DisplayName("同一实例重复登记: 首次写入一行,第二次忽略且不影响行数")
  void shouldInsertOnceAndIgnoreConflict_whenSameInstanceNotifiedTwice() {
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
    Long existing = jdbc.query(
        "select id from batch.job_definition where tenant_id = ? and job_code = ? limit 1",
        rs -> rs.next() ? rs.getLong(1) : null,
        tenantId,
        jobCode);
    if (existing != null) {
      return existing;
    }
    return jdbc.queryForObject("""
        INSERT INTO batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, created_at, updated_at)
        VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'Asia/Shanghai', now(), now())
        RETURNING id
        """, Long.class, tenantId, jobCode, jobCode + "-name");
  }

  private long insertEligibleJobInstance(String tenantId) {
    String jobCode = "JOB_ELIGIBLE_" + System.nanoTime();
    long defId = ensureJobDefinition(tenantId, jobCode);
    return insertJobInstance(tenantId, defId, jobCode, "SUCCESS", "control", "0 minute");
  }

  private long insertJobInstance(
      String tenantId,
      long jobDefinitionId,
      String jobCode,
      String status,
      String operatorId,
      String finishedAgo) {
    String instanceNo = jobCode + "-" + System.nanoTime();
    return jdbc.queryForObject(
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
  }
}
