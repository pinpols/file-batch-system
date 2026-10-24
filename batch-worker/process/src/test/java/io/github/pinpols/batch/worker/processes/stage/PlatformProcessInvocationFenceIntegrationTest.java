package io.github.pinpols.batch.worker.processes.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("PROCESS invocation fence:真实 PostgreSQL 锁与 Orchestrator 回收互斥")
class PlatformProcessInvocationFenceIntegrationTest {

  private static final String TENANT = "tenant-a";
  private static final long TASK_ID = 12L;
  private static final long INSTANCE_ID = 34L;
  private static final long PARTITION_ID = 56L;
  private static final String WORKER_ID = "worker-a";
  private static final String INVOCATION_ID = "invocation-a";
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.create();

  private JdbcTemplate jdbcTemplate;
  private ProcessInvocationFence fence;
  private DataSourceTransactionManager transactionManager;

  @BeforeAll
  static void startPostgres() {
    POSTGRES.start();
  }

  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @BeforeEach
  void setUp() {
    DataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    jdbcTemplate = new JdbcTemplate(dataSource);
    final String dropSql = """
        drop schema if exists batch cascade
    """;
    jdbcTemplate.execute(dropSql);
    final String createSql = """
        create schema batch
    """;
    jdbcTemplate.execute(createSql);
    final String createBatchJobTaskSql = """
        create table batch.job_task (
          tenant_id text not null,
          id bigint not null,
          job_instance_id bigint not null,
          job_partition_id bigint not null,
          task_status text not null,
          process_staging_generation_id uuid,
          process_staging_batch_key text,
          updated_at timestamptz not null default now()
        )
        """;
    jdbcTemplate.execute(createBatchJobTaskSql);
    final String createBatchJobPartitionSql = """
        create table batch.job_partition (
          tenant_id text not null,
          id bigint not null,
          job_instance_id bigint not null,
          partition_status text not null,
          worker_code text not null,
          current_invocation_id text not null
        )
        """;
    jdbcTemplate.execute(createBatchJobPartitionSql);
    final String insertBatchJobTaskSql = """
        insert into batch.job_task(tenant_id, id, job_instance_id, job_partition_id, task_status)
        values (?, ?, ?, ?, 'RUNNING')
        """;
    jdbcTemplate.update(insertBatchJobTaskSql, TENANT, TASK_ID, INSTANCE_ID, PARTITION_ID);
    final String insertBatchJobPartitionSql = """
        insert into batch.job_partition(
          tenant_id, id, job_instance_id, partition_status, worker_code, current_invocation_id)
        values (?, ?, ?, 'RUNNING', ?, ?)
        """;
    jdbcTemplate.update(insertBatchJobPartitionSql, TENANT, PARTITION_ID, INSTANCE_ID, WORKER_ID, INVOCATION_ID);

    transactionManager = new DataSourceTransactionManager(dataSource);
    Environment environment = new Environment(
        "process-invocation-fence-it", new SpringManagedTransactionFactory(), dataSource);
    Configuration configuration = new Configuration(environment);
    configuration.addMapper(ProcessInvocationFenceMapper.class);
    SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    ProcessInvocationFenceMapper mapper =
        new SqlSessionTemplate(sessionFactory).getMapper(ProcessInvocationFenceMapper.class);
    ProcessStagingGenerationCoordinator generationCoordinator =
        new PlatformProcessStagingGenerationCoordinator(mapper, transactionManager);
    fence = new PlatformProcessInvocationFence(mapper, generationCoordinator);
  }

  @Test
  @DisplayName("真实 claim 允许阶段执行,已被新 invocation 接管后拒绝业务副作用")
  void execute_allowsCurrentClaimAndRejectsStaleClaim() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    ProcessJobContext context = claimedContext();
    AtomicBoolean executed = new AtomicBoolean();

    ProcessStageResult result =
        transaction.execute(status -> fence.execute(context, ProcessStage.PREPARE, () -> {
          executed.set(true);
          return ProcessStageResult.success(ProcessStage.PREPARE);
        }));

    assertThat(result).isNotNull();
    assertThat(result.success()).isTrue();
    assertThat(executed).isTrue();

    final String updateBatchJobPartitionSql = """
        update batch.job_partition set current_invocation_id = ? where tenant_id = ? and id = ?
    """;
    jdbcTemplate.update(
        updateBatchJobPartitionSql,
        "invocation-b",
        TENANT,
        PARTITION_ID);
    executed.set(false);
    assertThatThrownBy(() ->
            transaction.execute(status -> fence.execute(context, ProcessStage.PREPARE, () -> {
              executed.set(true);
              return ProcessStageResult.success(ProcessStage.PREPARE);
            })))
        .isInstanceOf(BizException.class);
    assertThat(executed).isFalse();
  }

  @Test
  @DisplayName("COMPUTE generation 在业务阶段失败前已独立持久化")
  void computeGeneration_commitsBeforeStageSideEffects() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    ProcessJobContext context = claimedContext();
    context.setBatchKey("explicit-batch-key");

    assertThatThrownBy(() ->
            transaction.execute(status -> fence.execute(context, ProcessStage.COMPUTE, () -> {
              throw new IllegalStateException("simulated business database failure");
            })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("simulated business database failure");

    final String selectBatchJobTaskSql = """
        select process_staging_generation_id from batch.job_task where tenant_id = ? and id = ?
    """;
    UUID selectedProcessStagingGenerationId =
        jdbcTemplate.queryForObject(selectBatchJobTaskSql, UUID.class, TENANT, TASK_ID);
    assertThat(selectedProcessStagingGenerationId).isNotNull();
    final String selectBatchJobTaskSqlValue1 = """
        select process_staging_batch_key from batch.job_task where tenant_id = ? and id = ?
    """;
    String selectedProcessStagingBatchKey =
        jdbcTemplate.queryForObject(selectBatchJobTaskSqlValue1, String.class, TENANT, TASK_ID);
    assertThat(selectedProcessStagingBatchKey).isEqualTo("explicit-batch-key");
  }

  @Test
  @DisplayName("重跑 COMPUTE 轮换 staging generation,同次 checkpoint 续跑沿用当前代")
  void newComputeAttempt_rotatesGenerationAndCheckpointReusesCurrentGeneration() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    ProcessJobContext firstAttempt = claimedContext();
    firstAttempt.setBatchKey("process-12");

    transaction.execute(status -> fence.execute(
        firstAttempt,
        ProcessStage.COMPUTE,
        () -> ProcessStageResult.success(ProcessStage.COMPUTE)));

    String firstGeneration = firstAttempt.getStagingGenerationId();
    assertThat(firstGeneration).isNotBlank();
    final String updateBatchJobPartitionSqlValue2 = """
        update batch.job_partition set current_invocation_id = ? where tenant_id = ? and id = ?
    """;
    jdbcTemplate.update(
        updateBatchJobPartitionSqlValue2,
        "invocation-b",
        TENANT,
        PARTITION_ID);

    ProcessJobContext restartedAttempt = claimedContext();
    restartedAttempt
        .getAttributes()
        .put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "invocation-b");
    restartedAttempt.setBatchKey("process-12");
    transaction.execute(status -> fence.execute(
        restartedAttempt,
        ProcessStage.COMPUTE,
        () -> ProcessStageResult.success(ProcessStage.COMPUTE)));

    String restartedGeneration = restartedAttempt.getStagingGenerationId();
    assertThat(restartedGeneration).isNotBlank().isNotEqualTo(firstGeneration);
    final String selectBatchJobTaskSqlValue3 = """
        select process_staging_generation_id from batch.job_task where tenant_id = ? and id = ?
    """;
    assertThat(jdbcTemplate
            .queryForObject(
                selectBatchJobTaskSqlValue3,
                UUID.class,
                TENANT,
                TASK_ID)
            .toString())
        .isEqualTo(restartedGeneration);

    ProcessJobContext checkpointResume = claimedContext();
    checkpointResume
        .getAttributes()
        .put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, "invocation-b");
    transaction.execute(status -> fence.execute(
        checkpointResume,
        ProcessStage.VALIDATE,
        () -> ProcessStageResult.success(ProcessStage.VALIDATE)));

    assertThat(checkpointResume.getStagingGenerationId()).isEqualTo(restartedGeneration);
  }

  @Test
  @DisplayName("Worker 持有实例 fence 时,Orchestrator 同键 reclaim 锁等待到业务阶段结束")
  void stageExecution_serializesWithOrchestratorInstanceFence() throws Exception {
    CountDownLatch stageEntered = new CountDownLatch(1);
    CountDownLatch allowStageToFinish = new CountDownLatch(1);
    CountDownLatch reclaimAttempting = new CountDownLatch(1);
    CountDownLatch reclaimLockAcquired = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    try {
      ProcessJobContext context = claimedContext();
      context.setBatchKey("process-12");
      Future<ProcessStageResult> stage = executor.submit(() ->
          transaction.execute(status -> fence.execute(context, ProcessStage.COMPUTE, () -> {
            stageEntered.countDown();
            await(allowStageToFinish);
            return ProcessStageResult.success(ProcessStage.COMPUTE);
          })));
      assertThat(stageEntered.await(10, TimeUnit.SECONDS)).isTrue();

      Future<Void> reclaim = executor.submit(() -> transaction.execute(status -> {
        reclaimAttempting.countDown();
        final String selectSql = """
            select 1
            from (
              select pg_advisory_xact_lock(hashtext(? || ':' || ?::text))
            ) locked
            """;
        jdbcTemplate.queryForObject(selectSql, Integer.class, TENANT, INSTANCE_ID);
        reclaimLockAcquired.countDown();
        return null;
      }));

      assertThat(reclaimAttempting.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(awaitAdvisoryLockWait()).isTrue();
      assertThat(reclaimLockAcquired.getCount()).isEqualTo(1);
      allowStageToFinish.countDown();
      assertThat(stage.get(10, TimeUnit.SECONDS).success()).isTrue();
      final String selectBatchJobTaskSqlValue4 = """
          select process_staging_generation_id from batch.job_task where tenant_id = ? and id = ?
      """;
      UUID selectedProcessStagingGenerationId =
          jdbcTemplate.queryForObject(selectBatchJobTaskSqlValue4, UUID.class, TENANT, TASK_ID);
      assertThat(selectedProcessStagingGenerationId).isNotNull();
      reclaim.get(10, TimeUnit.SECONDS);
      assertThat(reclaimLockAcquired.getCount()).isZero();
    } finally {
      allowStageToFinish.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static ProcessJobContext claimedContext() {
    ProcessJobContext context = new ProcessJobContext();
    context.setTenantId(TENANT);
    context.setWorkerId(WORKER_ID);
    context.getAttributes().put(PipelineRuntimeKeys.TASK_ID, TASK_ID);
    context.getAttributes().put(PipelineRuntimeKeys.JOB_INSTANCE_ID, INSTANCE_ID);
    context.getAttributes().put(PipelineRuntimeKeys.JOB_PARTITION_ID, PARTITION_ID);
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, INVOCATION_ID);
    return context;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for stage release");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for stage release", exception);
    }
  }

  private boolean awaitAdvisoryLockWait() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      final String selectPgLocksSql = """
          select count(*) from pg_locks where locktype = 'advisory' and not granted
      """;
      Integer waiting = jdbcTemplate.queryForObject(
          selectPgLocksSql,
          Integer.class);
      if (waiting != null && waiting > 0) {
        return true;
      }
      Thread.sleep(20);
    }
    return false;
  }
}
