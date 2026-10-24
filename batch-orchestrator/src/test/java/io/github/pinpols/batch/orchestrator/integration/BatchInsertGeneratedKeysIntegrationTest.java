package io.github.pinpols.batch.orchestrator.integration;

import io.github.pinpols.batch.orchestrator.domain.param.OutboxEventMarkPublishingBatchParam;


import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture;
import io.github.pinpols.batch.orchestrator.integration.support.LaunchIntegrationFixture.WorkflowChildTriggerSeed;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchItem;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchRow;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * PERF(任务5) 针对性 IT：真 PG 上验证批量 SQL 的三个"纸面上说不准、必须实测"的机制点：
 *
 * <ol>
 *   <li>5.1 前置核实结论 —— createPartitions/createTasks 的下游<b>依赖生成主键回填</b>（buildTask 用
 *       partition.getId()、step 镜像/outbox eventKey 用 task.getId()），故 insertBatch 必须在 PG + MyBatis 下
 *       正确按序回填多行 id（mapper 通过 {@code keyColumn=id} 只请求主键列）。
 *   <li>5.3 renewLeaseBatch 的 {@code UPDATE ... FROM VALUES JOIN ... RETURNING}（MyBatis select 标签跑
 *       UPDATE）——命中集、cancel_requested 回读、CAS 谓词（invocation 不匹配的行不动）。
 *   <li>5.4 markPublishingBatch 的 {@code UPDATE ... RETURNING id}——抢占胜出集 + publish_attempt 递增 +
 *       PUBLISHING 守卫下 markPublishedBatch 只推胜出行。
 * </ol>
 *
 * <p>刻意不起 Spring：手工构建 SqlSessionFactory；使用共享 Workflow 子请求 fixture 建立真实父链，
 * 保持数据库约束和触发器启用。本类验证批量 SQL 与驱动机制，完整业务链路仍由全栈 IT 覆盖。
 */
@Testcontainers
@DisplayName("真实数据库上的批量语句机制,验证多行插入的主键按序回填,批量续租的命中集合与调用标识比较,以及事件抢占与后续标记的守卫语义")
class BatchInsertGeneratedKeysIntegrationTest {

  private static final String TENANT_ID = "batch-insert-" + Long.toUnsignedString(System.nanoTime());
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 10, 14);

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      TestPostgresContainers.create("batch_sql_batch_it");

  private static SingleConnectionDataSource dataSource;
  private static SqlSessionFactory sqlSessionFactory;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void setUp() throws Exception {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name) values (?, 'Batch SQL integration')
        """;
    final String bindTenantSql = """
        select set_config('app.tenant_id', ?, false)
        """;

    flywayConfiguration().load().migrate();
    dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbc = new JdbcTemplate(dataSource);
    jdbc.update(insertTenantSql, TENANT_ID);
    jdbc.queryForObject(bindTenantSql, String.class, TENANT_ID);
    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    for (String resource : List.of(
        "mapper/JobPartitionMapper.xml",
        "mapper/JobTaskMapper.xml",
        "mapper/JobStepInstanceMapper.xml",
        "mapper/OutboxEventMapper.xml")) {
      try (InputStream in = Resources.getResourceAsStream(resource)) {
        new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
      }
    }
    sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
  }

  private static FluentConfiguration flywayConfiguration() {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"));
  }

  @AfterEach
  void cleanOwnedRows() {
    final String deleteChildTriggerRequestsSql = """
        delete from batch.trigger_request where tenant_id = ? and parent_workflow_run_id is not null
        """;
    for (String table : List.of("outbox_event", "job_task", "job_partition", "job_instance")) {
      deleteOwnedRows(table);
    }
    jdbc.update(deleteChildTriggerRequestsSql, TENANT_ID);
    deleteOwnedRows("workflow_run");
    for (String table : List.of(
        "trigger_request", "workflow_edge", "workflow_node", "workflow_definition_version",
        "workflow_definition", "job_definition", "business_calendar", "tenant_configuration_revision")) {
      deleteOwnedRows(table);
    }
  }

  private static void deleteOwnedRows(String table) {
    // 表名只来自本类固定清理顺序；租户始终绑定，不能清理其它测试或迁移种子。
    String deleteOwnedRowsSql = "delete from batch." + table + " where tenant_id = ?";
    jdbc.update(deleteOwnedRowsSql, TENANT_ID);
  }

  @AfterAll
  static void tearDown() {
    if (dataSource != null) {
      final String deleteTenantSql = """
          delete from batch.tenant where tenant_id = ?
          """;
      try {
        jdbc.update(deleteTenantSql, TENANT_ID);
      } finally {
        dataSource.destroy();
      }
    }
  }

  @Test
  @DisplayName("批量插入多行时按序回填生成主键,回填标识与数据库行一一对应且任务插入同样按序回填")
  void shouldBackfillGeneratedIdsInOrder_whenBatchInserting() {
    final String selectIdSql = """
        select id from batch.job_partition where tenant_id=? and job_instance_id=? and partition_no=?\
        """;

    Long instanceId = seedJobInstance("generated-keys");
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobPartitionMapper partitionMapper = session.getMapper(JobPartitionMapper.class);
      List<JobPartitionEntity> partitions = List.of(
          partition(TENANT_ID, instanceId, 1),
          partition(TENANT_ID, instanceId, 2),
          partition(TENANT_ID, instanceId, 3));
      int inserted = partitionMapper.insertBatch(partitions);

      assertThat(inserted).isEqualTo(3);
      assertThat(partitions).allMatch(p -> p.getId() != null);
      assertThat(partitions.get(0).getId()).isLessThan(partitions.get(1).getId());
      assertThat(partitions.get(1).getId()).isLessThan(partitions.get(2).getId());
      // 回填的 id 与 DB 行一一对应(按 partition_no 反查)
      for (JobPartitionEntity p : partitions) {
        Long dbId = jdbc.queryForObject(
            selectIdSql,
            Long.class,
            p.getTenantId(),
            p.getJobInstanceId(),
            p.getPartitionNo());
        assertThat(p.getId()).isEqualTo(dbId);
      }

      JobTaskMapper taskMapper = session.getMapper(JobTaskMapper.class);
      List<JobTaskEntity> tasks = List.of(
          task(TENANT_ID, instanceId, partitions.get(0).getId()),
          task(TENANT_ID, instanceId, partitions.get(1).getId()));
      taskMapper.insertBatch(tasks);
      assertThat(tasks).allMatch(t -> t.getId() != null);
      assertThat(tasks.get(0).getId()).isLessThan(tasks.get(1).getId());
    }
  }

  @Test
  @DisplayName("批量续租只命中调用标识匹配的占位,回读取消标记并真正写入租约到期时间")
  void shouldUpdateLeaseAndReturnCancelFlag_whenBatchRenewing() {
    final String updateJobPartitionSql = """
        update batch.job_partition set partition_status='RUNNING', worker_code='w1',\s\
        current_invocation_id='inv-'||partition_no where tenant_id=? and job_instance_id=?\
        """;
    final String updateJobTaskSql = """
        update batch.job_task set task_status='RUNNING', assigned_worker_code='w1' where tenant_id=? and job_instance_id=?\
        """;
    final String updateJobTaskSql2 = """
        update batch.job_task set cancel_requested=true where tenant_id=? and id=?
    """;
    final String selectCountSql = """
        select count(*) from batch.job_partition where tenant_id=? and job_instance_id=? and lease_expire_at is not null\
        """;

    Long instanceId = seedJobInstance("batch-lease");
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobPartitionMapper partitionMapper = session.getMapper(JobPartitionMapper.class);
      JobTaskMapper taskMapper = session.getMapper(JobTaskMapper.class);
      JobPartitionEntity p1 = partition(TENANT_ID, instanceId, 1);
      JobPartitionEntity p2 = partition(TENANT_ID, instanceId, 2);
      partitionMapper.insertBatch(List.of(p1, p2));
      jdbc.update(updateJobPartitionSql, TENANT_ID, instanceId);
      JobTaskEntity t1 = task(TENANT_ID, instanceId, p1.getId());
      JobTaskEntity t2 = task(TENANT_ID, instanceId, p2.getId());
      taskMapper.insertBatch(List.of(t1, t2));
      jdbc.update(updateJobTaskSql, TENANT_ID, instanceId);
      jdbc.update(updateJobTaskSql2, TENANT_ID, t2.getId());

      Instant newLease = Instant.now().plus(300, ChronoUnit.SECONDS);
      List<RenewLeaseBatchRow> rows = partitionMapper.renewLeaseBatch(
          List.of(
              item(TENANT_ID, t1.getId(), "w1", "inv-1"),
              item(TENANT_ID, t2.getId(), "w1", "inv-2"),
              // invocation 不匹配(R3-P1-10 CAS):不得续、不得出现在 RETURNING
              item(TENANT_ID, t1.getId(), "w1", "inv-wrong")),
          newLease,
          "RUNNING");

      assertThat(rows).hasSize(2);
      assertThat(rows)
          .extracting(RenewLeaseBatchRow::getTaskId)
          .containsExactlyInAnyOrder(t1.getId(), t2.getId());
      RenewLeaseBatchRow rowT2 = rows.stream()
          .filter(r -> r.getTaskId().equals(t2.getId()))
          .findFirst()
          .orElseThrow();
      assertThat(rowT2.getCancelRequested()).isTrue();
      // lease 确实写进去了
      Integer renewedCount = jdbc.queryForObject(
          selectCountSql,
          Integer.class, TENANT_ID, instanceId);
      assertThat(renewedCount).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("批量抢占发布中状态时只命中可发布的行,递增发布尝试次数且已发布行不被后续标记误改")
  void shouldClaimOnlyPublishableRows_whenBatchMarkingPublishing() {
    final String insertOutboxEventSql = """
        insert into batch.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type, event_key,\s\
        payload_json, publish_status, publish_attempt)\s\
        values (?,'JOB_TASK',1,'IMPORT','k1','{}','NEW',0),\s\
        (?,'JOB_TASK',2,'IMPORT','k2','{}','FAILED',1), (?,'JOB_TASK',3,'IMPORT','k3','{}','PUBLISHED',1)\
        """;
    final String selectIdSql = """
        select id from batch.outbox_event where tenant_id=? order by id
    """;
    final String selectPublishAttemptSql = """
        select publish_attempt from batch.outbox_event where tenant_id=? and id=?
    """;

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      OutboxEventMapper outboxMapper = session.getMapper(OutboxEventMapper.class);
      jdbc.update(
          insertOutboxEventSql, TENANT_ID, TENANT_ID, TENANT_ID);
      List<Long> ids = jdbc.queryForList(
          selectIdSql, Long.class, TENANT_ID);

      List<Long> won = outboxMapper.markPublishingBatch(OutboxEventMarkPublishingBatchParam.builder()
          .tenantId(TENANT_ID)
          .ids(ids)
          .publishingStatus("PUBLISHING")
          .pendingStatus1("NEW")
          .pendingStatus2("FAILED")
          .build());

      // 只有 NEW/FAILED 两行被抢占;PUBLISHED 行不动(可重入集 CAS 与单条 markPublishing 一致)
      assertThat(won).containsExactlyInAnyOrder(ids.get(0), ids.get(1));
      Integer selectedPublishAttempt =
          jdbc.queryForObject(selectPublishAttemptSql, Integer.class, TENANT_ID, ids.get(0));
      assertThat(selectedPublishAttempt).isEqualTo(1);

      // 阶段三:markPublishedBatch 带 PUBLISHING 守卫 —— 已 PUBLISHED 的第三行不被误改
      int published = outboxMapper.markPublishedBatch(TENANT_ID, ids, "PUBLISHED", "PUBLISHING");
      assertThat(published).isEqualTo(2);
    }
  }

  private static Long seedJobInstance(String suffix) {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone, trigger_mode
        ) values (?, ?, ?, 'IMPORT', 'MANUAL', 'UTC', 'API') returning id
        """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance (
            tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
            trigger_type, instance_status, dedup_key, biz_date
        ) values (?, ?, ?, ?, ?, 'EVENT', 'RUNNING', ?, ?) returning id
        """;
    String jobCode = "BATCH_SQL_" + suffix;
    Long jobDefinitionId = jdbc.queryForObject(
        insertJobDefinitionSql, Long.class, TENANT_ID, jobCode, jobCode);
    WorkflowChildTriggerSeed child =
        LaunchIntegrationFixture.prepareWorkflowChildTriggerRequestForDefinition(
            jdbc, TENANT_ID, jobDefinitionId, jobCode, suffix, BIZ_DATE, TriggerType.API);
    return jdbc.queryForObject(
        insertJobInstanceSql, Long.class, TENANT_ID, jobDefinitionId, child.triggerRequestId(),
        jobCode, "INSTANCE_" + suffix, "DEDUP_" + suffix, BIZ_DATE);
  }

  private static JobPartitionEntity partition(String tenantId, Long instanceId, int no) {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setTenantId(tenantId);
    p.setJobInstanceId(instanceId);
    p.setJobInstanceBizDate(BIZ_DATE);
    p.setPartitionNo(no);
    p.setPartitionKey("k-" + instanceId + "-" + no);
    p.setPartitionStatus("CREATED");
    p.setRetryCount(0);
    p.setIdempotencyKey(instanceId + ":" + no);
    return p;
  }

  private static JobTaskEntity task(String tenantId, Long instanceId, Long partitionId) {
    JobTaskEntity t = new JobTaskEntity();
    t.setTenantId(tenantId);
    t.setJobInstanceId(instanceId);
    t.setJobInstanceBizDate(BIZ_DATE);
    t.setJobPartitionId(partitionId);
    t.setTaskType("IMPORT");
    t.setTaskSeq(1);
    t.setTaskStatus("CREATED");
    t.setVersion(0L);
    return t;
  }

  private static RenewLeaseBatchItem item(
      String tenantId, Long taskId, String workerCode, String invocationId) {
    return RenewLeaseBatchItem.builder()
        .tenantId(tenantId)
        .taskId(taskId)
        .workerCode(workerCode)
        .invocationId(invocationId)
        .build();
  }
}
