package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.ops.mapper.ConsoleClusterDiagnosticMapper;
import io.github.pinpols.batch.console.domain.ops.view.cluster.OutboxStatusCountView;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
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
@DisplayName("卡死诊断统计映射: outbox 状态聚合的租户隔离与终态父子一致性")
class ConsoleClusterDiagnosticMapperIntegrationTest extends AbstractIntegrationTest {

  @Autowired
  private ConsoleClusterDiagnosticMapper mapper;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private final List<String> ownedTenants = new ArrayList<>();

  @AfterEach
  void removeOwnedFixture() {
    final String deleteOutboxEventSql = """
        delete from batch.outbox_event where tenant_id=?
    """;
    final String deleteJobTaskSql = """
        delete from batch.job_task where tenant_id=?
    """;
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id=?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id=?
    """;

    for (String tenantId : ownedTenants) {
      jdbcTemplate.update(deleteOutboxEventSql, tenantId);
      jdbcTemplate.update(deleteJobTaskSql, tenantId);
      jdbcTemplate.update(deleteJobPartitionSql, tenantId);
      jdbcTemplate.update(deleteJobInstanceSql, tenantId);
      jdbcTemplate.update(deleteJobDefinitionSql, tenantId);
    }
  }

  // 全局递增 task_seq,保证 uk_job_task_no_partition_seq (job_instance_id, task_seq) 不撞。
  private final AtomicLong taskSeq = new AtomicLong(0);

  @Test
  @DisplayName("outbox 卡死统计只计本实例的 JOB_TASK 事件,数值撞车的其它 aggregate_type 诱饵被排除")
  void
      outboxStatusCountsForInstance_countsOnlyJobTaskEventsOfThisInstance_notCollidingAggregateIds() {
    // arrange
    String tenantId = ownTenant("ta-diag-");
    // 新复合外键要求实际 JobInstance 父行；仍让一个 task ID 与 instance ID 数值相等，保留 aggregate_type 撞车场景。
    // 关键:让某个 job_task 的 id 与 job_instance_id 数值相等,复现 aggregate_id 跨类型撞车。
    long jobInstanceId = nextTaskId();
    insertJobInstance(tenantId, jobInstanceId);
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
    List<OutboxStatusCountView> rows =
        mapper.outboxStatusCountsForInstance(tenantId, jobInstanceId);

    // assert：只应有 NEW=2 这一组;诱饵的 FAILED 不得泄漏
    assertThat(rows).hasSize(1);
    assertThat(countForStatus(rows, "NEW")).isEqualTo(2L);
    assertThat(rows).noneMatch(r -> "FAILED".equals(r.status()));
  }

  @Test
  @DisplayName("outbox 卡死统计按租户隔离,不串入他租户同 job_instance_id 的 JOB_TASK 事件")
  void outboxStatusCountsForInstance_isTenantScoped() {
    // arrange
    String tenantA = ownTenant("ta-scope-");
    long jobInstanceIdA = insertJobInstance(tenantA);
    long taskA = nextTaskId();
    insertJobTask(tenantA, taskA, jobInstanceIdA);
    insertOutbox(tenantA, "JOB_TASK", taskA, "NEW");

    // 另一租户使用自己的真实实例；复合主键不允许跨租户复用同一个 instance ID。
    String tenantB = ownTenant("tb-scope-");
    long jobInstanceIdB = insertJobInstance(tenantB);
    long taskB = nextTaskId();
    insertJobTask(tenantB, taskB, jobInstanceIdB);
    insertOutbox(tenantB, "JOB_TASK", taskB, "NEW");

    // act
    List<OutboxStatusCountView> rowsA =
        mapper.outboxStatusCountsForInstance(tenantA, jobInstanceIdA);
    List<OutboxStatusCountView> rowsB =
        mapper.outboxStatusCountsForInstance(tenantB, jobInstanceIdB);
    List<OutboxStatusCountView> crossTenantRows =
        mapper.outboxStatusCountsForInstance(tenantA, jobInstanceIdB);

    // assert：各自只看到自己那条,互不串号
    assertThat(rowsA).hasSize(1);
    assertThat(countForStatus(rowsA, "NEW")).isEqualTo(1L);
    assertThat(rowsB).hasSize(1);
    assertThat(countForStatus(rowsB, "NEW")).isEqualTo(1L);
    assertThat(crossTenantRows).isEmpty();
  }

  @Test
  @DisplayName("终态父子一致性诊断覆盖 dry-run 成功与失败终态")
  void shouldIncludeDryRunTerminalStates_whenCountingTerminalInstancesWithActiveChildren() {
    final String deleteJobPartitionSql = """
        delete from batch.job_partition where tenant_id = ? and job_instance_id in (?, ?)
    """;

    String tenantId = ownTenant("ta-dry-run-");
    long successId = insertJobInstanceWithActivePartition(tenantId, "SUCCESS_DRY_RUN");
    long failedId = insertJobInstanceWithActivePartition(tenantId, "FAILED_DRY_RUN");

    assertThat(mapper.countTerminalInstancesWithActiveChildren(tenantId)).isEqualTo(2L);

    jdbcTemplate.update(deleteJobPartitionSql, tenantId, successId, failedId);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private String ownTenant(String prefix) {
    String tenantId = prefix + UUID.randomUUID();
    ownedTenants.add(tenantId);
    return tenantId;
  }

  private long nextTaskId() {
    final String selectNextvalSql = """
        select nextval('batch.job_task_id_seq')
    """;

    return jdbcTemplate.queryForObject(selectNextvalSql, Long.class);
  }

  private void insertJobTask(String tenantId, long id, long jobInstanceId) {
    final String insertJobTaskSql = """
          INSERT INTO batch.job_task
            (id, tenant_id, job_instance_id, job_instance_biz_date, task_type, task_seq, task_status,
             created_at, updated_at)
          VALUES (?, ?, ?, (SELECT biz_date FROM batch.job_instance WHERE tenant_id = ?\s\
          AND id = ?), 'EXECUTION', ?, 'RUNNING', now(), now())
          """;

    jdbcTemplate.update(
        insertJobTaskSql,
        id,
        tenantId,
        jobInstanceId,
        tenantId,
        jobInstanceId,
        taskSeq.incrementAndGet());
  }

  private long insertJobInstance(String tenantId) {
    return insertJobInstance(tenantId, null);
  }

  private long insertJobInstance(String tenantId, Long instanceId) {
    final String insertJobDefinitionSql = """
              insert into batch.job_definition(
                tenant_id, job_code, job_name, job_type, schedule_type, timezone
              ) values (?, ?, 'Diagnostic Test', 'GENERAL', 'MANUAL', 'Asia/Shanghai')
              returning id
              """;
    final String insertJobInstanceSql = """
                insert into batch.job_instance(
                  tenant_id, job_definition_id, job_code, instance_no, biz_date,
                  trigger_type, instance_status, priority, dedup_key
                ) values (?, ?, ?, ?, current_date, 'MANUAL', 'RUNNING', 5, ?)
                returning id
                """;
    final String insertJobInstanceSql2 = """
        insert into batch.job_instance(
          id, tenant_id, job_definition_id, job_code, instance_no, biz_date,
          trigger_type, instance_status, priority, dedup_key
        ) values (?, ?, ?, ?, ?, current_date, 'MANUAL', 'RUNNING', 5, ?)
        """;

    String suffix = UUID.randomUUID().toString();
    String jobCode = "DIAG-" + suffix;
    Long definitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId, jobCode);
    if (instanceId == null) {
      return jdbcTemplate.queryForObject(
          insertJobInstanceSql,
          Long.class,
          tenantId,
          definitionId,
          jobCode,
          "INST-" + suffix,
          tenantId + ":" + suffix);
    }
    jdbcTemplate.update(
        insertJobInstanceSql2,
        instanceId,
        tenantId,
        definitionId,
        jobCode,
        "INST-" + suffix,
        tenantId + ":" + suffix);
    return instanceId;
  }

  private void insertOutbox(
      String tenantId, String aggregateType, long aggregateId, String status) {
    final String insertOutboxEventSql = """
    INSERT INTO batch.outbox_event
    (tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
    publish_status, created_at, updated_at)
    VALUES (?, ?, ?, 'TASK_EVENT', ?, '{}'::jsonb, ?, now(), now())
    """;

    String eventKey = aggregateType + "-" + aggregateId + "-" + System.nanoTime();
    jdbcTemplate.update(
        insertOutboxEventSql, tenantId, aggregateType, aggregateId, eventKey, status);
  }

  private long insertJobInstanceWithActivePartition(String tenantId, String status) {
    final String insertJobDefinitionSql = """
              insert into batch.job_definition(
                tenant_id, job_code, job_name, job_type, schedule_type, timezone
              ) values (?, ?, 'Diagnostic Test', 'GENERAL', 'MANUAL', 'Asia/Shanghai')
              returning id
              """;
    final String insertJobInstanceSql = """
              insert into batch.job_instance(
                tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
                instance_status, priority, dedup_key, expected_partition_count,
                success_partition_count, failed_partition_count, trace_id, dry_run
              ) values (?, ?, ?, ?, current_date, 'MANUAL', ?, 5, ?, 1, 0, 0, ?, true)
              returning id
              """;
    final String insertJobPartitionSql = """
          insert into batch.job_partition(
            tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status, business_key,
            idempotency_key, dry_run
          ) values (?, ?, (select biz_date from batch.job_instance where tenant_id=?\s\
          and id=?), 1, 'RUNNING', ?, ?, true)
          """;

    String suffix = UUID.randomUUID().toString();
    String jobCode = "DIAG-" + suffix;
    Long definitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId, jobCode);
    Long instanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenantId,
        definitionId,
        jobCode,
        "INST-" + suffix,
        status,
        "DEDUP-" + suffix,
        "TRACE-" + suffix);
    jdbcTemplate.update(
        insertJobPartitionSql,
        tenantId,
        instanceId,
        tenantId,
        instanceId,
        "BIZ-" + suffix,
        "IDEM-" + suffix);
    return instanceId;
  }

  private static long countForStatus(List<OutboxStatusCountView> rows, String status) {
    return rows.stream()
        .filter(r -> status.equals(r.status()))
        .mapToLong(r -> r.count() == null ? 0L : r.count())
        .sum();
  }
}
