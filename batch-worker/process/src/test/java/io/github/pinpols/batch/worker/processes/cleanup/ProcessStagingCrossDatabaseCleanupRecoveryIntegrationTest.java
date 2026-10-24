package io.github.pinpols.batch.worker.processes.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.rls.RlsTenantSessionSupport;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;
import io.github.pinpols.batch.worker.processes.mapper.business.ProcessStagingMapper;
import io.github.pinpols.batch.worker.processes.mapper.platform.ProcessInvocationFenceMapper;
import io.github.pinpols.batch.worker.processes.stage.PlatformProcessInvocationFence;
import io.github.pinpols.batch.worker.processes.stage.PlatformProcessStagingGenerationCoordinator;
import io.github.pinpols.batch.worker.processes.stage.ProcessInvocationFence;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("PROCESS staging 回收:双库提交中断后可安全重试")
class ProcessStagingCrossDatabaseCleanupRecoveryIntegrationTest {

  private static final String TENANT = "tenant_a";
  private static final long TASK_ID = 41L;
  private static final long INSTANCE_ID = 73L;
  private static final long PARTITION_ID = 97L;
  private static final String WORKER_ID = "worker-a";
  private static final String INVOCATION_ID = "invocation-a";
  private static final String OLD_GENERATION = "10000000-0000-0000-0000-000000000041";
  private static final String CURRENT_GENERATION = "20000000-0000-0000-0000-000000000041";
  private static final String ARCHIVED_CURRENT_GENERATION = "30000000-0000-0000-0000-000000000041";
  private static final String BATCH_KEY = "process-41";
  private static final PostgreSQLContainer PLATFORM_POSTGRES =
      TestPostgresContainers.create("batch_cleanup_platform");
  private static final PostgreSQLContainer BUSINESS_POSTGRES =
      TestPostgresContainers.create("batch_cleanup_business");

  private JdbcTemplate platformJdbc;
  private JdbcTemplate businessJdbc;
  private DataSource platformDataSource;
  private DataSource businessDataSource;
  private AnnotationConfigApplicationContext applicationContext;
  private TransactionTemplate platformTransaction;
  private ProcessStagingGenerationCleanupFence cleanupFence;
  private ProcessInvocationFenceMapper platformMapper;
  private ProcessInvocationFence workerFence;
  private ProcessStagingGenerationCandidate candidate;

  @BeforeAll
  static void startDatabases() {
    PLATFORM_POSTGRES.start();
    BUSINESS_POSTGRES.start();
  }

  @AfterAll
  static void stopDatabases() {
    PLATFORM_POSTGRES.stop();
    BUSINESS_POSTGRES.stop();
  }

  @BeforeEach
  void setUp() throws Exception {
    platformDataSource = new DriverManagerDataSource(
        PLATFORM_POSTGRES.getJdbcUrl(),
        PLATFORM_POSTGRES.getUsername(),
        PLATFORM_POSTGRES.getPassword());
    businessDataSource = new DriverManagerDataSource(
        BUSINESS_POSTGRES.getJdbcUrl(),
        BUSINESS_POSTGRES.getUsername(),
        BUSINESS_POSTGRES.getPassword());
    platformJdbc = new JdbcTemplate(platformDataSource);
    businessJdbc = new JdbcTemplate(businessDataSource);
    createPlatformFixture();
    createBusinessFixture();
    createApplicationContext();
    candidate = new ProcessStagingGenerationCandidate(TENANT, TASK_ID, OLD_GENERATION, BATCH_KEY);
  }

  @AfterEach
  void closeApplicationContext() {
    if (applicationContext != null) {
      applicationContext.close();
    }
    RlsTenantContextHolder.clear();
  }

  @Test
  @DisplayName("业务库已提交回收而平台锁事务回滚后,重复回收保持幂等")
  void businessCommit_survivesPlatformRollbackAndRetryConverges() {
    Integer deleted = platformTransaction.execute(status -> {
      int count = cleanupFence.reclaimIfStale(candidate, 100);
      status.setRollbackOnly();
      return count;
    });

    assertThat(deleted).isEqualTo(2);
    assertThat(readPlatformCurrentGeneration()).isEqualTo(CURRENT_GENERATION);
    assertThat(readBusinessGenerationStatus()).isEqualTo("RECLAIMED");
    assertThat(countBusinessStagingRows()).isZero();

    Integer retryDeleted =
        platformTransaction.execute(status -> cleanupFence.reclaimIfStale(candidate, 100));

    assertThat(retryDeleted).isZero();
    assertThat(readBusinessGenerationStatus()).isEqualTo("RECLAIMED");
    assertThat(countBusinessStagingRows()).isZero();
  }

  @Test
  @DisplayName("业务库封口后删除失败时整个业务事务回滚,解除平台锁后重试完成回收")
  void businessTransactionFailure_rollsBackSealAndRowsThenRetryCompletes() {
    final String createSql = """
        create function batch.fail_process_staging_delete()
        returns trigger language plpgsql as $$
        begin
          raise exception 'injected staging delete failure' using errcode = 'P0001';
        end
        $$
        """;
    businessJdbc.execute(createSql);
    final String createSqlValue1 = """
        create trigger fail_process_staging_delete
        before delete on batch.process_staging
        for each row execute function batch.fail_process_staging_delete()
        """;
    businessJdbc.execute(createSqlValue1);

    assertThatThrownBy(() ->
            platformTransaction.execute(status -> cleanupFence.reclaimIfStale(candidate, 100)))
        .hasRootCauseInstanceOf(SQLException.class)
        .hasMessageContaining("injected staging delete failure");

    assertThat(readBusinessGenerationStatus()).isEqualTo("OPEN");
    assertThat(countBusinessStagingRows()).isEqualTo(2);
    assertThat(readPlatformCurrentGeneration()).isEqualTo(CURRENT_GENERATION);

    final String dropSql = """
        drop trigger fail_process_staging_delete on batch.process_staging
    """;
    businessJdbc.execute(dropSql);
    final String dropSqlValue2 = """
        drop function batch.fail_process_staging_delete()
    """;
    businessJdbc.execute(dropSqlValue2);
    Integer retryDeleted =
        platformTransaction.execute(status -> cleanupFence.reclaimIfStale(candidate, 100));

    assertThat(retryDeleted).isEqualTo(2);
    assertThat(readBusinessGenerationStatus()).isEqualTo("RECLAIMED");
    assertThat(countBusinessStagingRows()).isZero();
  }

  @Test
  @DisplayName("分块删除已提交后后续块失败,SEALED generation 可从剩余行续清")
  void committedChunk_survivesLaterChunkFailureAndRetryConverges() {
    final String createSqlValue3 = """
        create function batch.fail_process_staging_delete_for_newer_row()
        returns trigger language plpgsql as $$
        begin
          if old.staged_at > now() - interval '36 hours' then
            raise exception 'injected second chunk failure' using errcode = 'P0001';
          end if;
          return old;
        end
        $$
        """;
    businessJdbc.execute(createSqlValue3);
    final String createSqlValue4 = """
        create trigger fail_process_staging_delete_for_newer_row
        before delete on batch.process_staging
        for each row execute function batch.fail_process_staging_delete_for_newer_row()
        """;
    businessJdbc.execute(createSqlValue4);

    Integer firstChunk =
        platformTransaction.execute(status -> cleanupFence.reclaimIfStale(candidate, 1));

    assertThat(firstChunk).isEqualTo(1);
    assertThat(readBusinessGenerationStatus()).isEqualTo("SEALED");
    assertThat(countBusinessStagingRows()).isEqualTo(1);

    assertThatThrownBy(
            () -> platformTransaction.execute(status -> cleanupFence.reclaimIfStale(candidate, 1)))
        .hasRootCauseInstanceOf(SQLException.class)
        .hasMessageContaining("injected second chunk failure");

    assertThat(readBusinessGenerationStatus()).isEqualTo("SEALED");
    assertThat(countBusinessStagingRows()).isEqualTo(1);

    final String dropSqlValue5 = """
        drop trigger fail_process_staging_delete_for_newer_row on batch.process_staging
    """;
    businessJdbc.execute(
        dropSqlValue5);
    final String dropSqlValue6 = """
        drop function batch.fail_process_staging_delete_for_newer_row()
    """;
    businessJdbc.execute(dropSqlValue6);
    Integer finalChunk =
        platformTransaction.execute(status -> cleanupFence.reclaimIfStale(candidate, 1));

    assertThat(finalChunk).isEqualTo(1);
    assertThat(readBusinessGenerationStatus()).isEqualTo("RECLAIMED");
    assertThat(countBusinessStagingRows()).isZero();
  }

  @Test
  @DisplayName("task 归档后仍从冷表读取当前 generation 并阻止误回收")
  void archivedTaskCurrentGeneration_blocksCleanup() {
    final String deleteBatchJobTaskSql = """
        delete from batch.job_task where tenant_id = ? and id = ?
    """;
    platformJdbc.update(
        deleteBatchJobTaskSql, TENANT, TASK_ID);
    final String insertArchiveJobTaskArchiveSql = """
        insert into archive.job_task_archive
          (tenant_id, id, job_instance_id, process_staging_generation_id)
        values (?, ?, ?, cast(? as uuid))
        """;
    platformJdbc.update(insertArchiveJobTaskArchiveSql, TENANT, TASK_ID, INSTANCE_ID, ARCHIVED_CURRENT_GENERATION);
    final String insertBatchProcessStagingGenerationSql = """
        insert into batch.process_staging_generation
          (generation_id, tenant_id, job_task_id, batch_key, generation_status, created_at)
        values (cast(? as uuid), ?, ?, ?, 'OPEN', now() - interval '3 days')
        """;
    businessJdbc.update(insertBatchProcessStagingGenerationSql, ARCHIVED_CURRENT_GENERATION, TENANT, TASK_ID, BATCH_KEY);
    final String insertBatchProcessStagingSql = """
        insert into batch.process_staging (tenant_id, staging_generation_id, batch_key, staged_at)
        values (?, cast(? as uuid), ?, now() - interval '2 days')
        """;
    businessJdbc.update(insertBatchProcessStagingSql, TENANT, ARCHIVED_CURRENT_GENERATION, BATCH_KEY);

    assertThat(platformMapper.selectTaskInstanceId(TENANT, TASK_ID)).isEqualTo(INSTANCE_ID);
    assertThat(platformMapper.selectTaskCurrentStagingGeneration(TENANT, TASK_ID))
        .isEqualTo(ARCHIVED_CURRENT_GENERATION);
    ProcessStagingGenerationCandidate archivedCurrent = new ProcessStagingGenerationCandidate(
        TENANT, TASK_ID, ARCHIVED_CURRENT_GENERATION, BATCH_KEY);

    Integer reclaimed =
        platformTransaction.execute(status -> cleanupFence.reclaimIfStale(archivedCurrent, 100));
    assertThat(reclaimed).isZero();
    final String selectBatchProcessStagingGenerationSql = """
        select generation_status from batch.process_staging_generation
        where tenant_id = ? and generation_id = cast(? as uuid) and batch_key = ?
        """;
    String selectedGenerationStatus = businessJdbc.queryForObject(
        selectBatchProcessStagingGenerationSql,
        String.class,
        TENANT,
        ARCHIVED_CURRENT_GENERATION,
        BATCH_KEY);
    assertThat(selectedGenerationStatus).isEqualTo("OPEN");
    final String selectBatchProcessStagingSql = """
        select count(*) from batch.process_staging
        where tenant_id = ? and staging_generation_id = cast(? as uuid) and batch_key = ?
        """;
    Integer selectedProcessStagingCount = businessJdbc.queryForObject(
        selectBatchProcessStagingSql,
        Integer.class,
        TENANT,
        ARCHIVED_CURRENT_GENERATION,
        BATCH_KEY);
    assertThat(selectedProcessStagingCount).isEqualTo(1);
  }

  @Test
  @DisplayName("checkpoint 读取当前代时清理等待同一实例 fence,随后确认当前代并保留暂存")
  void checkpointResume_serializesWithCleanupAndKeepsCurrentGeneration() throws Exception {
    final String insertBatchProcessStagingGenerationSqlValue7 = """
        insert into batch.process_staging_generation
          (generation_id, tenant_id, job_task_id, batch_key, generation_status, created_at)
        values (cast(? as uuid), ?, ?, ?, 'OPEN', now() - interval '3 days')
        """;
    businessJdbc.update(insertBatchProcessStagingGenerationSqlValue7, CURRENT_GENERATION, TENANT, TASK_ID, BATCH_KEY);
    final String insertBatchProcessStagingSqlValue8 = """
        insert into batch.process_staging (tenant_id, staging_generation_id, batch_key, staged_at)
        values (?, cast(? as uuid), ?, now() - interval '2 days')
        """;
    businessJdbc.update(insertBatchProcessStagingSqlValue8, TENANT, CURRENT_GENERATION, BATCH_KEY);

    ProcessStagingGenerationCandidate currentCandidate =
        new ProcessStagingGenerationCandidate(TENANT, TASK_ID, CURRENT_GENERATION, BATCH_KEY);
    ProcessJobContext checkpoint = claimedContext();
    checkpoint.setBatchKey(BATCH_KEY);
    CountDownLatch checkpointEntered = new CountDownLatch(1);
    CountDownLatch allowCheckpointToFinish = new CountDownLatch(1);
    CountDownLatch cleanupAttempting = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<ProcessStageResult> resume = executor.submit(() -> platformTransaction.execute(
          status -> workerFence.execute(checkpoint, ProcessStage.VALIDATE, () -> {
            checkpointEntered.countDown();
            awaitLatch(allowCheckpointToFinish);
            return writeCheckpointStagingRow(checkpoint);
          })));
      assertThat(checkpointEntered.await(10, TimeUnit.SECONDS)).isTrue();

      Future<Integer> reclaim = executor.submit(() -> platformTransaction.execute(status -> {
        cleanupAttempting.countDown();
        return cleanupFence.reclaimIfStale(currentCandidate, 100);
      }));
      assertThat(cleanupAttempting.await(10, TimeUnit.SECONDS)).isTrue();
      awaitAdvisoryLockWait();
      assertThat(reclaim.isDone()).isFalse();

      allowCheckpointToFinish.countDown();
      ProcessStageResult resumed = resume.get(10, TimeUnit.SECONDS);
      assertThat(resumed.success()).isTrue();
      assertThat(reclaim.get(10, TimeUnit.SECONDS)).isZero();
      final String selectBatchProcessStagingGenerationSqlValue9 = """
          select generation_status from batch.process_staging_generation
          where tenant_id = ? and generation_id = cast(? as uuid) and batch_key = ?
          """;
      String selectedGenerationStatus = businessJdbc.queryForObject(
          selectBatchProcessStagingGenerationSqlValue9,
          String.class,
          TENANT,
          CURRENT_GENERATION,
          BATCH_KEY);
      assertThat(selectedGenerationStatus).isEqualTo("OPEN");
      final String selectBatchProcessStagingSqlValue10 = """
          select count(*) from batch.process_staging
          where tenant_id = ? and staging_generation_id = cast(? as uuid) and batch_key = ?
              """;
      Integer selectedProcessStagingCount = businessJdbc.queryForObject(
          selectBatchProcessStagingSqlValue10, Integer.class, TENANT, CURRENT_GENERATION, BATCH_KEY);
      assertThat(selectedProcessStagingCount).isEqualTo(2);
    } finally {
      allowCheckpointToFinish.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("清理器先持有实例 fence 回收旧代时,checkpoint 等待后只读取当前代")
  void cleanupFirst_serializesWithCheckpointAndReclaimsOnlyOldGeneration() throws Exception {
    final String insertBatchProcessStagingGenerationSqlValue11 = """
        insert into batch.process_staging_generation
          (generation_id, tenant_id, job_task_id, batch_key, generation_status, created_at)
        values (cast(? as uuid), ?, ?, ?, 'OPEN', now() - interval '3 days')
        """;
    businessJdbc.update(insertBatchProcessStagingGenerationSqlValue11, CURRENT_GENERATION, TENANT, TASK_ID, BATCH_KEY);
    final String insertBatchProcessStagingSqlValue12 = """
        insert into batch.process_staging (tenant_id, staging_generation_id, batch_key, staged_at)
        values (?, cast(? as uuid), ?, now() - interval '2 days')
        """;
    businessJdbc.update(insertBatchProcessStagingSqlValue12, TENANT, CURRENT_GENERATION, BATCH_KEY);
    final String createBatchCleanupGateSql = """
        create table batch.cleanup_gate (id integer primary key)
    """;
    businessJdbc.execute(createBatchCleanupGateSql);
    final String insertBatchCleanupGateSql = """
        insert into batch.cleanup_gate (id) values (1)
    """;
    businessJdbc.update(insertBatchCleanupGateSql);
    final String createBatchCleanupGateSqlValue13 = """
        create function batch.block_process_staging_delete()
        returns trigger language plpgsql as $$
        begin
          perform 1 from batch.cleanup_gate where id = 1 for update;
          return old;
        end
        $$
        """;
    businessJdbc.execute(createBatchCleanupGateSqlValue13);
    final String createSqlValue14 = """
        create trigger block_process_staging_delete
        before delete on batch.process_staging
        for each row execute function batch.block_process_staging_delete()
        """;
    businessJdbc.execute(createSqlValue14);

    CountDownLatch businessGateLocked = new CountDownLatch(1);
    CountDownLatch releaseBusinessGate = new CountDownLatch(1);
    CountDownLatch checkpointAttempting = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    TransactionTemplate businessBlockTransaction =
        new TransactionTemplate(new DataSourceTransactionManager(businessDataSource));
    try {
      Future<?> businessBlocker = executor.submit(() -> businessBlockTransaction.execute(status -> {
        final String selectBatchCleanupGateSql = """
            select id from batch.cleanup_gate where id = 1 for update
        """;
        businessJdbc.queryForObject(
            selectBatchCleanupGateSql, Integer.class);
        businessGateLocked.countDown();
        awaitLatch(releaseBusinessGate);
        return null;
      }));
      assertThat(businessGateLocked.await(10, TimeUnit.SECONDS)).isTrue();

      Future<Integer> reclaim = executor.submit(
          () -> platformTransaction.execute(status -> cleanupFence.reclaimIfStale(candidate, 100)));
      awaitPlatformAdvisoryLockGranted();
      assertThat(reclaim.isDone()).isFalse();

      ProcessJobContext checkpoint = claimedContext();
      checkpoint.setBatchKey(BATCH_KEY);
      Future<ProcessStageResult> resume =
          executor.submit(() -> platformTransaction.execute(status -> {
            checkpointAttempting.countDown();
            return workerFence.execute(
                checkpoint, ProcessStage.VALIDATE, () -> writeCheckpointStagingRow(checkpoint));
          }));
      assertThat(checkpointAttempting.await(10, TimeUnit.SECONDS)).isTrue();
      awaitAdvisoryLockWait();
      assertThat(resume.isDone()).isFalse();

      releaseBusinessGate.countDown();
      assertThat(reclaim.get(10, TimeUnit.SECONDS)).isEqualTo(2);
      assertThat(resume.get(10, TimeUnit.SECONDS).success()).isTrue();
      businessBlocker.get(10, TimeUnit.SECONDS);

      assertThat(readBusinessGenerationStatus()).isEqualTo("RECLAIMED");
      assertThat(countBusinessStagingRows()).isZero();
      assertThat(readBusinessGenerationStatus(CURRENT_GENERATION)).isEqualTo("OPEN");
      assertThat(countBusinessStagingRows(CURRENT_GENERATION)).isEqualTo(2);
    } finally {
      releaseBusinessGate.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  private void createApplicationContext() throws Exception {
    SqlSessionFactory platformFactory = mapperFactory(platformDataSource, null);
    SqlSessionFactory businessFactory = mapperFactory(
        businessDataSource, new ClassPathResource("mapper/business/ProcessStagingMapper.xml"));
    platformMapper =
        new SqlSessionTemplate(platformFactory).getMapper(ProcessInvocationFenceMapper.class);
    ProcessStagingMapper businessMapper =
        new SqlSessionTemplate(businessFactory).getMapper(ProcessStagingMapper.class);
    DataSourceTransactionManager platformManager =
        new DataSourceTransactionManager(platformDataSource);
    DataSourceTransactionManager businessManager =
        new DataSourceTransactionManager(businessDataSource);

    applicationContext = new AnnotationConfigApplicationContext();
    applicationContext.register(TransactionConfiguration.class);
    applicationContext.registerBean(
        "processPlatformDataSource", DataSource.class, () -> platformDataSource);
    applicationContext.registerBean(
        "processBusinessDataSource", DataSource.class, () -> businessDataSource);
    applicationContext.registerBean(
        "transactionManager", PlatformTransactionManager.class, () -> platformManager);
    applicationContext.registerBean(
        "processBusinessTransactionManager",
        PlatformTransactionManager.class,
        () -> businessManager);
    applicationContext.registerBean(ProcessInvocationFenceMapper.class, () -> platformMapper);
    applicationContext.registerBean(ProcessStagingMapper.class, () -> businessMapper);
    applicationContext.registerBean(ProcessStagingBusinessCleanupTransaction.class);
    applicationContext.registerBean(ProcessStagingBusinessCleanupService.class);
    applicationContext.registerBean(ProcessStagingGenerationCleanupFence.class);
    applicationContext.refresh();

    cleanupFence = applicationContext.getBean(ProcessStagingGenerationCleanupFence.class);
    assertThat(AopUtils.isAopProxy(cleanupFence)).isTrue();
    workerFence = new PlatformProcessInvocationFence(
        platformMapper,
        new PlatformProcessStagingGenerationCoordinator(platformMapper, platformManager));
    platformTransaction = new TransactionTemplate(platformManager);
  }

  private ProcessJobContext claimedContext() {
    ProcessJobContext context = new ProcessJobContext();
    context.setTenantId(TENANT);
    context.setWorkerId(WORKER_ID);
    context.getAttributes().put(PipelineRuntimeKeys.TASK_ID, TASK_ID);
    context.getAttributes().put(PipelineRuntimeKeys.JOB_INSTANCE_ID, INSTANCE_ID);
    context.getAttributes().put(PipelineRuntimeKeys.JOB_PARTITION_ID, PARTITION_ID);
    context.getAttributes().put(PipelineRuntimeKeys.PARTITION_INVOCATION_ID, INVOCATION_ID);
    return context;
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for checkpoint release");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while waiting for checkpoint release", exception);
    }
  }

  private ProcessStageResult writeCheckpointStagingRow(ProcessJobContext checkpoint) {
    assertThat(checkpoint.getStagingGenerationId()).isEqualTo(CURRENT_GENERATION);
    return RlsTenantContextHolder.runWithTenant(TENANT, () -> {
      PlatformTransactionManager businessManager = applicationContext.getBean(
          "processBusinessTransactionManager", PlatformTransactionManager.class);
      return new TransactionTemplate(businessManager).execute(status -> {
        RlsTenantSessionSupport.applyIfPresent(businessDataSource);
        final String selectSql = """
            select set_config('app.process_staging_generation_id', ?, true)
        """;
        businessJdbc.queryForObject(
            selectSql,
            String.class,
            CURRENT_GENERATION);
        final String insertBatchProcessStagingSqlValue15 = """
            insert into batch.process_staging (tenant_id, staging_generation_id, batch_key)
            values (?, cast(? as uuid), ?)
            """;
        businessJdbc.update(insertBatchProcessStagingSqlValue15, TENANT, CURRENT_GENERATION, BATCH_KEY);
        return ProcessStageResult.success(ProcessStage.VALIDATE);
      });
    });
  }

  private void awaitAdvisoryLockWait() {
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      final String selectPgLocksSql = """
          select count(*) from pg_locks where locktype = 'advisory' and not granted
      """;
      Integer waiting = platformJdbc.queryForObject(
          selectPgLocksSql,
          Integer.class);
      assertThat(waiting).isGreaterThan(0);
    });
  }

  private void awaitPlatformAdvisoryLockGranted() {
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
      final String selectPgLocksSqlValue16 = """
          select count(*) from pg_locks where locktype = 'advisory' and granted
      """;
      Integer granted = platformJdbc.queryForObject(
          selectPgLocksSqlValue16, Integer.class);
      assertThat(granted).isGreaterThan(0);
    });
  }

  private static SqlSessionFactory mapperFactory(
      DataSource dataSource, ClassPathResource mapperResource) throws Exception {
    Configuration configuration = new Configuration(new Environment(
        "process-staging-cleanup-recovery", new SpringManagedTransactionFactory(), dataSource));
    if (mapperResource == null) {
      configuration.addMapper(ProcessInvocationFenceMapper.class);
    }
    SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
    factoryBean.setDataSource(dataSource);
    factoryBean.setConfiguration(configuration);
    if (mapperResource != null) {
      factoryBean.setMapperLocations(mapperResource);
    }
    SqlSessionFactory factory = factoryBean.getObject();
    if (factory == null) {
      throw new IllegalStateException("MyBatis factory was not initialized");
    }
    return factory;
  }

  private void createPlatformFixture() {
    final String dropSqlValue17 = """
        drop schema if exists archive cascade
    """;
    platformJdbc.execute(dropSqlValue17);
    final String dropSqlValue18 = """
        drop schema if exists batch cascade
    """;
    platformJdbc.execute(dropSqlValue18);
    final String createSqlValue19 = """
        create schema batch
    """;
    platformJdbc.execute(createSqlValue19);
    final String createSqlValue20 = """
        create schema archive
    """;
    platformJdbc.execute(createSqlValue20);
    final String createBatchJobTaskSql = """
        create table batch.job_task (
          tenant_id text not null,
          id bigint not null,
          job_instance_id bigint not null,
          job_partition_id bigint not null,
          task_status text not null,
          process_staging_generation_id uuid,
          process_staging_batch_key text,
          primary key (tenant_id, id)
        )
        """;
    platformJdbc.execute(createBatchJobTaskSql);
    final String createBatchJobPartitionSql = """
        create table batch.job_partition (
          tenant_id text not null,
          id bigint not null,
          job_instance_id bigint not null,
          partition_status text not null,
          worker_code text not null,
          current_invocation_id text not null,
          primary key (tenant_id, id)
        )
        """;
    platformJdbc.execute(createBatchJobPartitionSql);
    final String createArchiveJobTaskArchiveSql = """
        create table archive.job_task_archive (
          tenant_id text not null,
          id bigint not null,
          job_instance_id bigint not null,
          process_staging_generation_id uuid,
          primary key (tenant_id, id)
        )
        """;
    platformJdbc.execute(createArchiveJobTaskArchiveSql);
    final String insertBatchJobTaskSql = """
        insert into batch.job_task
          (tenant_id, id, job_instance_id, job_partition_id, task_status,
           process_staging_generation_id, process_staging_batch_key)
        values (?, ?, ?, ?, 'RUNNING', cast(? as uuid), ?)
        """;
    platformJdbc.update(
        insertBatchJobTaskSql, TENANT, TASK_ID, INSTANCE_ID, PARTITION_ID, CURRENT_GENERATION, BATCH_KEY);
    final String insertBatchJobPartitionSql = """
        insert into batch.job_partition(
          tenant_id, id, job_instance_id, partition_status, worker_code, current_invocation_id)
        values (?, ?, ?, 'RUNNING', ?, ?)
        """;
    platformJdbc.update(insertBatchJobPartitionSql, TENANT, PARTITION_ID, INSTANCE_ID, WORKER_ID, INVOCATION_ID);
  }

  private void createBusinessFixture() {
    final String dropSqlValue21 = """
        drop schema if exists batch cascade
    """;
    businessJdbc.execute(dropSqlValue21);
    final String createSqlValue22 = """
        create schema batch
    """;
    businessJdbc.execute(createSqlValue22);
    final String createBatchProcessStagingGenerationSql = """
        create table batch.process_staging_generation (
          generation_id uuid primary key,
          tenant_id text not null,
          job_task_id bigint not null,
          batch_key text not null,
          generation_status text not null,
          created_at timestamptz not null default now(),
          sealed_at timestamptz,
          unique (tenant_id, generation_id, batch_key)
        )
        """;
    businessJdbc.execute(createBatchProcessStagingGenerationSql);
    final String alterBatchProcessStagingGenerationSql = """
        alter table batch.process_staging_generation enable row level security
    """;
    businessJdbc.execute(alterBatchProcessStagingGenerationSql);
    final String alterBatchProcessStagingGenerationSqlValue23 = """
        alter table batch.process_staging_generation force row level security
    """;
    businessJdbc.execute(alterBatchProcessStagingGenerationSqlValue23);
    final String createSqlValue24 = """
        create policy tenant_isolation_strict on batch.process_staging_generation
        as permissive for all to public
        using (tenant_id = current_setting('app.tenant_id', true))
        with check (tenant_id = current_setting('app.tenant_id', true))
        """;
    businessJdbc.execute(createSqlValue24);
    final String createBatchProcessStagingSql = """
        create table batch.process_staging (
          tenant_id text not null,
          staging_generation_id uuid not null,
          batch_key text not null,
          staged_at timestamptz not null default now()
        )
        """;
    businessJdbc.execute(createBatchProcessStagingSql);
    final String alterBatchProcessStagingSql = """
        alter table batch.process_staging enable row level security
    """;
    businessJdbc.execute(alterBatchProcessStagingSql);
    final String alterBatchProcessStagingSqlValue25 = """
        alter table batch.process_staging force row level security
    """;
    businessJdbc.execute(alterBatchProcessStagingSqlValue25);
    final String createSqlValue26 = """
        create policy tenant_isolation_strict on batch.process_staging
        as permissive for all to public
        using (tenant_id = current_setting('app.tenant_id', true)
          and staging_generation_id::text = current_setting('app.process_staging_generation_id', true))
        with check (tenant_id = current_setting('app.tenant_id', true)
          and staging_generation_id::text = current_setting('app.process_staging_generation_id', true))
        """;
    businessJdbc.execute(createSqlValue26);
    final String insertBatchProcessStagingGenerationSqlValue27 = """
        insert into batch.process_staging_generation
          (generation_id, tenant_id, job_task_id, batch_key, generation_status, created_at)
        values (cast(? as uuid), ?, ?, ?, 'OPEN', now() - interval '3 days')
        """;
    businessJdbc.update(insertBatchProcessStagingGenerationSqlValue27, OLD_GENERATION, TENANT, TASK_ID, BATCH_KEY);
    final String insertBatchProcessStagingSqlValue28 = """
        insert into batch.process_staging (tenant_id, staging_generation_id, batch_key, staged_at)
        values (?, cast(? as uuid), ?, now() - interval '2 days'),
               (?, cast(? as uuid), ?, now() - interval '1 day')
        """;
    businessJdbc.update(insertBatchProcessStagingSqlValue28, TENANT, OLD_GENERATION, BATCH_KEY, TENANT, OLD_GENERATION, BATCH_KEY);
  }

  private String readPlatformCurrentGeneration() {
    final String selectBatchJobTaskSql = """
        select process_staging_generation_id::text
        from batch.job_task where tenant_id = ? and id = ?
        """;
    return platformJdbc.queryForObject(selectBatchJobTaskSql, String.class, TENANT, TASK_ID);
  }

  private String readBusinessGenerationStatus() {
    return readBusinessGenerationStatus(OLD_GENERATION);
  }

  private String readBusinessGenerationStatus(String generationId) {
    return RlsTenantContextHolder.runWithTenant(TENANT, () -> {
      var transactionManager = applicationContext.getBean(
          "processBusinessTransactionManager", PlatformTransactionManager.class);
      return new TransactionTemplate(transactionManager).execute(status -> {
        RlsTenantSessionSupport.applyIfPresent(businessDataSource);
        final String selectBatchProcessStagingGenerationSqlValue29 = """
            select generation_status from batch.process_staging_generation
            where tenant_id = ? and generation_id = cast(? as uuid) and batch_key = ?
            """;
        return businessJdbc.queryForObject(selectBatchProcessStagingGenerationSqlValue29, String.class, TENANT, generationId, BATCH_KEY);
      });
    });
  }

  private int countBusinessStagingRows() {
    return countBusinessStagingRows(OLD_GENERATION);
  }

  private int countBusinessStagingRows(String generationId) {
    return RlsTenantContextHolder.runWithTenant(TENANT, () -> {
      var transactionManager = applicationContext.getBean(
          "processBusinessTransactionManager", PlatformTransactionManager.class);
      return new TransactionTemplate(transactionManager).execute(status -> {
        RlsTenantSessionSupport.applyIfPresent(businessDataSource);
        final String selectSqlValue30 = """
            select set_config('app.process_staging_generation_id', ?, true)
        """;
        businessJdbc.queryForObject(
            selectSqlValue30,
            String.class,
            generationId);
        final String selectBatchProcessStagingSqlValue31 = """
            select count(*) from batch.process_staging
            where tenant_id = ? and staging_generation_id = cast(? as uuid) and batch_key = ?
            """;
        return businessJdbc.queryForObject(selectBatchProcessStagingSqlValue31, Integer.class, TENANT, generationId, BATCH_KEY);
      });
    });
  }

  @Component
  @EnableTransactionManagement
  static class TransactionConfiguration {}
}
