package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchItem;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchRow;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * PERF(任务5) 针对性 IT：真 PG 上验证批量 SQL 的三个"纸面上说不准、必须实测"的机制点：
 *
 * <ol>
 *   <li>5.1 前置核实结论 —— createPartitions/createTasks 的下游<b>依赖生成主键回填</b>（buildTask 用
 *       partition.getId()、step 镜像/outbox eventKey 用 task.getId()），故 insertBatch 必须在 PG + MyBatis 下
 *       正确按序回填多行 id（PG getGeneratedKeys 返回全部行）。
 *   <li>5.3 renewLeaseBatch 的 {@code UPDATE ... FROM VALUES JOIN ... RETURNING}（MyBatis select 标签跑
 *       UPDATE）——命中集、cancel_requested 回读、CAS 谓词（invocation 不匹配的行不动）。
 *   <li>5.4 markPublishingBatch 的 {@code UPDATE ... RETURNING id}——抢占胜出集 + publish_attempt 递增 +
 *       PUBLISHING 守卫下 markPublishedBatch 只推胜出行。
 * </ol>
 *
 * <p>刻意不起 Spring：手工构建 SqlSessionFactory 只挂三个 mapper XML；用 {@code session_replication_role=replica}
 * 关闭 FK 触发器（本 IT 只验证 SQL 形状与驱动行为，不验证业务链路——业务链路由 PartitionJoinPromotionIntegrationTest 等全栈 IT 覆盖）。
 */
@Testcontainers
class BatchInsertGeneratedKeysIntegrationTest {

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer(DockerImageName.parse("postgres:17"))
          .withDatabaseName("batch_sql_batch_it")
          .withUsername("batch_user")
          .withPassword("batch_pass_123");

  private static SingleConnectionDataSource dataSource;
  private static SqlSessionFactory sqlSessionFactory;
  private static JdbcTemplate jdbc;

  @BeforeAll
  static void setUp() throws Exception {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .load()
        .migrate();
    dataSource =
        new SingleConnectionDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true);
    jdbc = new JdbcTemplate(dataSource);
    // 关 FK 触发器：本 IT 只测 SQL/驱动机制，不铺 job_definition/job_instance 全量种子
    jdbc.execute("set session_replication_role = replica");
    Configuration configuration =
        new Configuration(new Environment("it", new JdbcTransactionFactory(), dataSource));
    for (String resource :
        List.of(
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

  @AfterAll
  static void tearDown() {
    if (dataSource != null) {
      dataSource.destroy();
    }
  }

  @Test
  @DisplayName("5.1: job_partition/job_task insertBatch 多行生成主键按序回填(下游硬依赖)")
  void insertBatchBackfillsGeneratedIdsInOrder() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobPartitionMapper partitionMapper = session.getMapper(JobPartitionMapper.class);
      List<JobPartitionEntity> partitions =
          List.of(partition("ta", 900L, 1), partition("ta", 900L, 2), partition("ta", 900L, 3));
      int inserted = partitionMapper.insertBatch(partitions);

      assertThat(inserted).isEqualTo(3);
      assertThat(partitions).allMatch(p -> p.getId() != null);
      assertThat(partitions.get(0).getId()).isLessThan(partitions.get(1).getId());
      assertThat(partitions.get(1).getId()).isLessThan(partitions.get(2).getId());
      // 回填的 id 与 DB 行一一对应(按 partition_no 反查)
      for (JobPartitionEntity p : partitions) {
        Long dbId =
            jdbc.queryForObject(
                "select id from batch.job_partition where tenant_id=? and job_instance_id=? and"
                    + " partition_no=?",
                Long.class,
                p.getTenantId(),
                p.getJobInstanceId(),
                p.getPartitionNo());
        assertThat(p.getId()).isEqualTo(dbId);
      }

      JobTaskMapper taskMapper = session.getMapper(JobTaskMapper.class);
      List<JobTaskEntity> tasks =
          List.of(
              task("ta", 900L, partitions.get(0).getId()),
              task("ta", 900L, partitions.get(1).getId()));
      taskMapper.insertBatch(tasks);
      assertThat(tasks).allMatch(t -> t.getId() != null);
      assertThat(tasks.get(0).getId()).isLessThan(tasks.get(1).getId());
    }
  }

  @Test
  @DisplayName("5.3: renewLeaseBatch 一条 UPDATE...RETURNING — 命中集/cancel 回读/CAS 谓词")
  void renewLeaseBatchUpdatesLeaseAndReturnsCancelFlag() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      JobPartitionMapper partitionMapper = session.getMapper(JobPartitionMapper.class);
      JobTaskMapper taskMapper = session.getMapper(JobTaskMapper.class);
      JobPartitionEntity p1 = partition("ta", 901L, 1);
      JobPartitionEntity p2 = partition("ta", 901L, 2);
      partitionMapper.insertBatch(List.of(p1, p2));
      jdbc.update(
          "update batch.job_partition set partition_status='RUNNING', worker_code='w1',"
              + " current_invocation_id='inv-'||partition_no where job_instance_id=901");
      JobTaskEntity t1 = task("ta", 901L, p1.getId());
      JobTaskEntity t2 = task("ta", 901L, p2.getId());
      taskMapper.insertBatch(List.of(t1, t2));
      jdbc.update(
          "update batch.job_task set task_status='RUNNING', assigned_worker_code='w1'"
              + " where job_instance_id=901");
      jdbc.update("update batch.job_task set cancel_requested=true where id=?", t2.getId());

      Instant newLease = Instant.now().plus(300, ChronoUnit.SECONDS);
      List<RenewLeaseBatchRow> rows =
          partitionMapper.renewLeaseBatch(
              List.of(
                  item("ta", t1.getId(), "w1", "inv-1"),
                  item("ta", t2.getId(), "w1", "inv-2"),
                  // invocation 不匹配(R3-P1-10 CAS):不得续、不得出现在 RETURNING
                  item("ta", t1.getId(), "w1", "inv-wrong")),
              newLease,
              "RUNNING");

      assertThat(rows).hasSize(2);
      assertThat(rows)
          .extracting(RenewLeaseBatchRow::getTaskId)
          .containsExactlyInAnyOrder(t1.getId(), t2.getId());
      RenewLeaseBatchRow rowT2 =
          rows.stream().filter(r -> r.getTaskId().equals(t2.getId())).findFirst().orElseThrow();
      assertThat(rowT2.getCancelRequested()).isTrue();
      // lease 确实写进去了
      Integer renewedCount =
          jdbc.queryForObject(
              "select count(*) from batch.job_partition where job_instance_id=901 and"
                  + " lease_expire_at is not null",
              Integer.class);
      assertThat(renewedCount).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("5.4: markPublishingBatch UPDATE...RETURNING 抢占 + attempt 递增 + PUBLISHING 守卫")
  void markPublishingBatchClaimsAndGuardsFollowUpUpdates() {
    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      OutboxEventMapper outboxMapper = session.getMapper(OutboxEventMapper.class);
      jdbc.update(
          "insert into batch.outbox_event (tenant_id, aggregate_type, aggregate_id, event_type,"
              + " event_key, payload_json, publish_status, publish_attempt)"
              + " values ('ta','JOB_TASK',1,'IMPORT','k1','{}','NEW',0),"
              + " ('ta','JOB_TASK',2,'IMPORT','k2','{}','FAILED',1),"
              + " ('ta','JOB_TASK',3,'IMPORT','k3','{}','PUBLISHED',1)");
      List<Long> ids =
          jdbc.queryForList(
              "select id from batch.outbox_event where tenant_id='ta' order by id", Long.class);

      List<Long> won = outboxMapper.markPublishingBatch("ta", ids, "PUBLISHING", "NEW", "FAILED");

      // 只有 NEW/FAILED 两行被抢占;PUBLISHED 行不动(可重入集 CAS 与单条 markPublishing 一致)
      assertThat(won).containsExactlyInAnyOrder(ids.get(0), ids.get(1));
      assertThat(
              jdbc.queryForObject(
                  "select publish_attempt from batch.outbox_event where id=?",
                  Integer.class,
                  ids.get(0)))
          .isEqualTo(1);

      // 阶段三:markPublishedBatch 带 PUBLISHING 守卫 —— 已 PUBLISHED 的第三行不被误改
      int published = outboxMapper.markPublishedBatch("ta", ids, "PUBLISHED", "PUBLISHING");
      assertThat(published).isEqualTo(2);
    }
  }

  private static JobPartitionEntity partition(String tenantId, Long instanceId, int no) {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setTenantId(tenantId);
    p.setJobInstanceId(instanceId);
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
