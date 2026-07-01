package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.ops.mapper.ConsoleClusterDiagnosticMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 集成测试：卡死诊断 outbox 状态聚合 {@code ConsoleClusterDiagnosticMapper#outboxStatusCountsForInstance} 对真实库的
 * SQL 回归守护。
 *
 * <p>该统计原实现直接按 {@code aggregate_id = jobInstanceId} 匹配 outbox_event,而 {@code aggregate_id} 在不同
 * {@code aggregate_type} 之间数值复用——一条 WORKFLOW_RUN 事件的 {@code aggregate_id} 可能恰好等于某个 job_instance 的
 * id,从而被误计入该实例的 JOB_TASK 卡死诊断。修复方式:join {@code job_task} 且强制 {@code oe.aggregate_type='JOB_TASK'} +
 * {@code jt.job_instance_id = #{jobInstanceId}}。
 *
 * <p>此前该 mapper 全部覆盖都是 mock-mapper 单测,SQL 从未真跑,回退修复不会变红。本测试打真库:去掉 {@code aggregate_type='JOB_TASK'}
 * 谓词或去掉 job_task join 都会让「诱饵」WORKFLOW_RUN 事件泄漏进计数 → 断言失败。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ConsoleClusterDiagnosticMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ConsoleClusterDiagnosticMapper mapper;

  @Autowired private JdbcTemplate jdbcTemplate;

  // 全局递增 task_seq,保证 uk_job_task_no_partition_seq (job_instance_id, task_seq) 不撞。
  private final AtomicLong taskSeq = new AtomicLong(0);

  @Test
  @DisplayName("outbox 卡死统计只计本实例的 JOB_TASK 事件,数值撞车的其它 aggregate_type 诱饵被排除")
  void
      outboxStatusCountsForInstance_countsOnlyJobTaskEventsOfThisInstance_notCollidingAggregateIds() {
    // arrange
    String tenantId = "ta-diag-" + System.nanoTime();
    // job_instance_id 只是一个数值(V173 已删 job_task→job_instance 外键,无需实体化 job_instance)。
    // 关键:让某个 job_task 的 id 与 job_instance_id 数值相等,复现 aggregate_id 跨类型撞车。
    long jobInstanceId = nextTaskId();
    long taskA = nextTaskId();
    long taskB = nextTaskId();

    // (a) 本实例的两条 JOB_TASK
    insertJobTask(tenantId, taskA, jobInstanceId);
    insertJobTask(tenantId, taskB, jobInstanceId);
    // 撞车用 job_task:id == jobInstanceId,归属同一实例。去掉 JOB_TASK 谓词时诱饵会经它 join 进来。
    insertJobTask(tenantId, jobInstanceId, jobInstanceId);

    // (b) 两条真正的 JOB_TASK outbox(status=NEW)
    insertOutbox(tenantId, "JOB_TASK", taskA, "NEW");
    insertOutbox(tenantId, "JOB_TASK", taskB, "NEW");
    // 诱饵:非 JOB_TASK 类型,aggregate_id 恰等于 jobInstanceId(旧 buggy SQL 会误计);status=FAILED 便于识别泄漏。
    insertOutbox(tenantId, "WORKFLOW_RUN", jobInstanceId, "FAILED");

    // act
    List<Map<String, Object>> rows = mapper.outboxStatusCountsForInstance(tenantId, jobInstanceId);

    // assert：只应有 NEW=2 这一组;诱饵的 FAILED 不得泄漏
    assertThat(rows).hasSize(1);
    assertThat(countForStatus(rows, "NEW")).isEqualTo(2L);
    assertThat(rows).noneMatch(r -> "FAILED".equals(r.get("status")));
  }

  @Test
  @DisplayName("outbox 卡死统计按租户隔离,不串入他租户同 job_instance_id 的 JOB_TASK 事件")
  void outboxStatusCountsForInstance_isTenantScoped() {
    // arrange
    long jobInstanceId = nextTaskId();

    String tenantA = "ta-scope-" + System.nanoTime();
    long taskA = nextTaskId();
    insertJobTask(tenantA, taskA, jobInstanceId);
    insertOutbox(tenantA, "JOB_TASK", taskA, "NEW");

    // 另一租户:同一 job_instance_id 数值,也是 JOB_TASK outbox——若租户谓词失守会被误计
    String tenantB = "tb-scope-" + System.nanoTime();
    long taskB = nextTaskId();
    insertJobTask(tenantB, taskB, jobInstanceId);
    insertOutbox(tenantB, "JOB_TASK", taskB, "NEW");

    // act
    List<Map<String, Object>> rowsA = mapper.outboxStatusCountsForInstance(tenantA, jobInstanceId);
    List<Map<String, Object>> rowsB = mapper.outboxStatusCountsForInstance(tenantB, jobInstanceId);

    // assert：各自只看到自己那条,互不串号
    assertThat(rowsA).hasSize(1);
    assertThat(countForStatus(rowsA, "NEW")).isEqualTo(1L);
    assertThat(rowsB).hasSize(1);
    assertThat(countForStatus(rowsB, "NEW")).isEqualTo(1L);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private long nextTaskId() {
    return jdbcTemplate.queryForObject("select nextval('batch.job_task_id_seq')", Long.class);
  }

  private void insertJobTask(String tenantId, long id, long jobInstanceId) {
    jdbcTemplate.update(
        """
        INSERT INTO batch.job_task
          (id, tenant_id, job_instance_id, task_type, task_seq, task_status,
           created_at, updated_at)
        VALUES (?, ?, ?, 'EXECUTION', ?, 'RUNNING', now(), now())
        """,
        id,
        tenantId,
        jobInstanceId,
        taskSeq.incrementAndGet());
  }

  private void insertOutbox(
      String tenantId, String aggregateType, long aggregateId, String status) {
    String eventKey = aggregateType + "-" + aggregateId + "-" + System.nanoTime();
    jdbcTemplate.update(
        """
        INSERT INTO batch.outbox_event
          (tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
           publish_status, created_at, updated_at)
        VALUES (?, ?, ?, 'TASK_EVENT', ?, '{}'::jsonb, ?, now(), now())
        """,
        tenantId,
        aggregateType,
        aggregateId,
        eventKey,
        status);
  }

  private static long countForStatus(List<Map<String, Object>> rows, String status) {
    return rows.stream()
        .filter(r -> status.equals(r.get("status")))
        .mapToLong(r -> ((Number) r.get("count")).longValue())
        .sum();
  }
}
