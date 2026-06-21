package com.example.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.BatchOrchestratorApplication;
import com.example.batch.orchestrator.domain.entity.OutboxEventEntity;
import com.example.batch.orchestrator.domain.param.FinishTaskParam;
import com.example.batch.orchestrator.domain.param.UpdateTaskStatusParam;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import com.example.batch.orchestrator.mapper.OutboxEventMapper;
import com.example.batch.testing.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Exactly-once 崩溃/重投不变量的真实 DB 验证。
 *
 * <p>系统的去重关键约束全在 orchestrator 的 DB CAS 上(worker 只是调用方),所以"整队 worker 崩 / Kafka 消息重投两遍 / GC-pause 旧
 * leader 复活"在 IT 里等价于"同一命令被投递两次,断言只有一次生效"。本测试不 fork worker fat-jar(避开 JDK25 嵌套 jar loader 长期停滞),全程
 * in-process + testcontainers PG,本机可跑。
 *
 * <p>覆盖三道关键约束:
 *
 * <ol>
 *   <li>REPORT 重投幂等:{@code finishTask} 的 {@code WHERE task_status = expectedStatus} CAS 让重复
 *       回报空转——不重复推进状态。
 *   <li>旧 leader 陈旧写被拒:乐观锁 {@code version = expectedVersion} 让 GC-pause 复活的旧 leader 拿着
 *       陈旧版本号回报时被拒,不覆盖已落定的终态(防 lost-update / 防陈旧复活)。
 *   <li>不重复出账:{@code outbox_event} 的 {@code (tenant_id, event_key)} 幂等让重投的同一事件第二次 insert
 *       空转——下游不会收到重复事件。
 * </ol>
 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExactlyOnceCrashRecoveryIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";

  @Autowired private JobTaskMapper jobTaskMapper;
  @Autowired private OutboxEventMapper outboxEventMapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("REPORT 重投只生效一次——重复回报被终态 CAS 空转,版本只 +1")
  void replayedReport_appliedExactlyOnce_whenFinishDeliveredTwice() {
    long taskId = insertRunningTask("replay-finish-" + System.nanoTime());
    try {
      // arrange:task RUNNING、version=0

      // act:同一份 REPORT(SUCCESS)投递两次,模拟 Kafka 重投
      int first = finishToSuccess(taskId, 0L);
      int second = finishToSuccess(taskId, 0L); // 重投:task 已非 RUNNING、version 已变

      // assert:恰好一次被接受;状态只前进一次,版本 0→1
      assertThat(first).as("首次 REPORT 应被接受").isEqualTo(1);
      assertThat(second).as("重投 REPORT 必须被终态 CAS 拒绝(幂等空转)").isEqualTo(0);
      assertThat(taskStatus(taskId)).isEqualTo(TaskStatus.SUCCESS.code());
      assertThat(taskVersion(taskId)).as("版本只前进一次,无重复推进").isEqualTo(1L);
    } finally {
      cleanupTask(taskId);
    }
  }

  @Test
  @DisplayName("GC-pause 旧 leader 的陈旧回报被拒——乐观锁不让覆盖已落定终态")
  void staleLeaderReport_rejected_whenVersionAlreadyAdvanced() {
    long taskId = insertRunningTask("stale-leader-" + System.nanoTime());
    try {
      // arrange:新 leader 已把任务 REPORT 成 SUCCESS,version 0→1
      assertThat(finishToSuccess(taskId, 0L)).isEqualTo(1);

      // act:GC-pause 复活的旧 leader 拿着陈旧 version=0 试图把它写成 FAILED
      int stale =
          jobTaskMapper.updateStatus(
              UpdateTaskStatusParam.withDefaultTerminals()
                  .tenantId(TENANT)
                  .id(taskId)
                  .taskStatus(TaskStatus.FAILED.code())
                  .expectedVersion(0L)
                  .build());

      // assert:陈旧写被乐观锁拒绝;终态仍是新 leader 落定的 SUCCESS
      assertThat(stale).as("陈旧版本号的写入必须被 version CAS 拒绝").isEqualTo(0);
      assertThat(taskStatus(taskId)).isEqualTo(TaskStatus.SUCCESS.code());
      assertThat(taskVersion(taskId)).isEqualTo(1L);
    } finally {
      cleanupTask(taskId);
    }
  }

  @Test
  @DisplayName("不重复出账——重投的同一 outbox 事件第二次 insert 被 (tenant_id,event_key) 幂等空转")
  void duplicateOutboxEvent_dedupedByEventKey_noDoubleEmit() {
    String eventKey = "EVT_" + System.nanoTime();
    try {
      // act:同一事件(同 event_key)投递两次,模拟重投/重放
      int first = outboxEventMapper.insert(newOutboxEvent(eventKey));
      int second = outboxEventMapper.insert(newOutboxEvent(eventKey));

      // assert:第二次空转;DB 中该 (tenant_id,event_key) 只有一行 → 下游不会重复出账
      assertThat(first).as("首次 outbox 写入应成功").isEqualTo(1);
      assertThat(second).as("重投的同 event_key 第二次 insert 必须空转").isEqualTo(0);
      Integer rows =
          jdbcTemplate.queryForObject(
              "SELECT count(*) FROM batch.outbox_event WHERE tenant_id = ? AND event_key = ?",
              Integer.class,
              TENANT,
              eventKey);
      assertThat(rows).as("同一事件键全局只一行").isEqualTo(1);
    } finally {
      jdbcTemplate.update(
          "DELETE FROM batch.outbox_event WHERE tenant_id = ? AND event_key = ?", TENANT, eventKey);
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────

  private int finishToSuccess(long taskId, long expectedVersion) {
    return jobTaskMapper.finishTask(
        FinishTaskParam.builder()
            .tenantId(TENANT)
            .id(taskId)
            .taskStatus(TaskStatus.SUCCESS.code())
            .expectedStatus(TaskStatus.RUNNING.code())
            .resultSummary(null)
            .errorCode(null)
            .errorMessage(null)
            .expectedVersion(expectedVersion)
            .build());
  }

  private OutboxEventEntity newOutboxEvent(String eventKey) {
    OutboxEventEntity e = new OutboxEventEntity();
    e.setTenantId(TENANT);
    e.setAggregateType("JOB_TASK");
    e.setAggregateId(1L);
    e.setEventType("TASK_COMPLETED");
    e.setEventKey(eventKey);
    e.setPayloadJson("{}");
    e.setPublishStatus("NEW");
    e.setPublishAttempt(0);
    e.setPriority(5);
    return e;
  }

  /** 用裸 JDBC 造一条 RUNNING、version=0 的 job_task(连带最小依赖行),返回 taskId。 */
  private long insertRunningTask(String suffix) {
    String jobCode = "JOB_" + suffix;
    Long jobDefinitionId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_definition (
                tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode
            ) VALUES (?, ?, ?, 'GENERAL', 'MANUAL', 'UTC', 'API')
            RETURNING id
            """,
            Long.class,
            TENANT,
            jobCode,
            jobCode);
    Long triggerRequestId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.trigger_request (
                tenant_id, request_id, trigger_type, job_code, dedup_key, request_status
            ) VALUES (?, ?, 'API', ?, ?, 'LAUNCHED')
            RETURNING id
            """,
            Long.class,
            TENANT,
            "REQ_" + suffix,
            jobCode,
            "TR_DEDUP_" + suffix);
    Long jobInstanceId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_instance (
                tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
                trigger_type, instance_status, dedup_key, biz_date
            ) VALUES (?, ?, ?, ?, ?, 'API', 'RUNNING', ?, CURRENT_DATE)
            RETURNING id
            """,
            Long.class,
            TENANT,
            jobDefinitionId,
            triggerRequestId,
            jobCode,
            "INST_" + suffix,
            "DEDUP_" + suffix);
    Long taskId =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO batch.job_task (
                tenant_id, job_instance_id, task_type, task_seq, task_status, version
            ) VALUES (?, ?, 'EXECUTION', 1, 'RUNNING', 0)
            RETURNING id
            """,
            Long.class,
            TENANT,
            jobInstanceId);
    return taskId;
  }

  private String taskStatus(long taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT task_status FROM batch.job_task WHERE id = ?", String.class, taskId);
  }

  private long taskVersion(long taskId) {
    return jdbcTemplate.queryForObject(
        "SELECT version FROM batch.job_task WHERE id = ?", Long.class, taskId);
  }

  private void cleanupTask(long taskId) {
    Long instanceId =
        jdbcTemplate.queryForObject(
            "SELECT job_instance_id FROM batch.job_task WHERE id = ?", Long.class, taskId);
    jdbcTemplate.update("DELETE FROM batch.job_task WHERE id = ?", taskId);
    Long defId =
        jdbcTemplate.queryForObject(
            "SELECT job_definition_id FROM batch.job_instance WHERE id = ?",
            Long.class,
            instanceId);
    Long trId =
        jdbcTemplate.queryForObject(
            "SELECT trigger_request_id FROM batch.job_instance WHERE id = ?",
            Long.class,
            instanceId);
    jdbcTemplate.update("DELETE FROM batch.job_instance WHERE id = ?", instanceId);
    jdbcTemplate.update("DELETE FROM batch.trigger_request WHERE id = ?", trId);
    jdbcTemplate.update("DELETE FROM batch.job_definition WHERE id = ?", defId);
  }
}
