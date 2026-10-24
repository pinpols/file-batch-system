package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.archive.WorkflowArchiveService;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowRunMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.workflow.archive.enabled=true",
      "batch.workflow.archive.retention-days=1",
      "batch.workflow.archive.batch-size=10"
    })
@DisplayName("独立 Workflow 归档保全节点 JOB 血缘并在冷复制异常时保留热数据")
class WorkflowArchiveIntegrityIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final WorkflowArchiveService archiveService;
  private final WorkflowRunMapper workflowRunMapper;
  private final PlatformTransactionManager transactionManager;
  private final List<ArchiveFixture> fixtures = new ArrayList<>();

  @Autowired
  WorkflowArchiveIntegrityIntegrationTest(
      JdbcTemplate jdbcTemplate,
      WorkflowArchiveService archiveService,
      WorkflowRunMapper workflowRunMapper,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.archiveService = archiveService;
    this.workflowRunMapper = workflowRunMapper;
    this.transactionManager = transactionManager;
  }

  @Test
  @DisplayName("无包装 Job 的 Workflow 归档后逐字段保持根与节点，并保留实际 JOB 关联")
  void shouldPreserveIndependentWorkflowAndJobNodeSnapshots() {
    ArchiveFixture fixture = insertFixture();
    String root = snapshot("batch.workflow_run", fixture.runId());
    String jobNode = snapshot("batch.workflow_node_run", fixture.jobNodeId());
    String endNode = snapshot("batch.workflow_node_run", fixture.endNodeId());
    String job = snapshot("batch.job_instance", fixture.jobId());

    archiveService.archiveOnce();

    assertThat(snapshot("archive.workflow_run_archive", fixture.runId())).isEqualTo(root);
    assertThat(snapshot("archive.workflow_node_run_archive", fixture.jobNodeId()))
        .isEqualTo(jobNode);
    assertThat(snapshot("archive.workflow_node_run_archive", fixture.endNodeId()))
        .isEqualTo(endNode);
    assertThat(count("batch.workflow_run", fixture.runId())).isZero();
    assertThat(count("batch.workflow_node_run", fixture.jobNodeId())).isZero();
    assertThat(count("batch.workflow_node_run", fixture.endNodeId())).isZero();
    assertThat(snapshot("batch.job_instance", fixture.jobId())).isEqualTo(job);

    archiveService.archiveOnce();
    assertThat(count("archive.workflow_run_archive", fixture.runId())).isEqualTo(1);
    assertThat(count("archive.workflow_node_run_archive", fixture.jobNodeId())).isEqualTo(1);
  }

  @Test
  @DisplayName("热 Workflow 节点仍引用 Job 时拒绝删除 JobInstance")
  void shouldRejectDeletingJobInstanceWhileHotWorkflowNodeReferencesIt() {
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where id = ?
    """;
    final String updateJobInstanceSql = """
        update batch.job_instance set id = id + 1000000000000 where id = ?
    """;

    ArchiveFixture fixture = insertFixture();

    assertThatThrownBy(() ->
    jdbcTemplate.update(deleteJobInstanceSql, fixture.jobId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbcTemplate.update(
            updateJobInstanceSql, fixture.jobId()))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(count("batch.job_instance", fixture.jobId())).isEqualTo(1);
    assertThat(count("batch.workflow_node_run", fixture.jobNodeId())).isEqualTo(1);
  }

  @Test
  @DisplayName("Workflow 节点绑定 Job 与父删除并发时不留下悬空引用")
  void shouldSerializeWorkflowNodeAttachmentAgainstJobInstanceDelete() throws Exception {
  final String updateWorkflowNodeRunSql = """
      update batch.workflow_node_run set job_instance_id = null where id = ?
  """;
  final String updateWorkflowNodeRunSql2 = """
      update batch.workflow_node_run set job_instance_id = ? where id = ?
  """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where id = ?
    """;
    final String selectCountSql = """
        select count(*) from pg_stat_activity where pid = ? and wait_event_type = 'Lock'
    """;
    final String selectJobInstanceIdSql = """
        select job_instance_id from batch.workflow_node_run where id = ?
    """;

    ArchiveFixture fixture = insertFixture();
    jdbcTemplate.update(
        updateWorkflowNodeRunSql,
        fixture.jobNodeId());
    CountDownLatch nodeAttached = new CountDownLatch(1);
    CountDownLatch allowNodeCommit = new CountDownLatch(1);
    CountDownLatch deleteStarted = new CountDownLatch(1);
    AtomicInteger deletePid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> attach = executor.submit(() -> new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> {
          jdbcTemplate.update(
              updateWorkflowNodeRunSql2,
              fixture.jobId(),
              fixture.jobNodeId());
          nodeAttached.countDown();
          awaitLatch(allowNodeCommit);
        }));
    Future<?> delete = null;
    try {
      assertThat(nodeAttached.await(5, TimeUnit.SECONDS)).isTrue();
      delete = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            deletePid.set(jdbcTemplate.queryForObject(selectPgBackendPidSql, Integer.class));
            deleteStarted.countDown();
            jdbcTemplate.update(deleteJobInstanceSql, fixture.jobId());
          }));
      assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> jdbcTemplate.queryForObject(
                  selectCountSql,
                  Integer.class,
                  deletePid.get())
              == 1);
      allowNodeCommit.countDown();
      attach.get(10, TimeUnit.SECONDS);
      Future<?> deleteFuture = delete;
      assertThatThrownBy(() -> deleteFuture.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(DataIntegrityViolationException.class);
      assertThat(count("batch.job_instance", fixture.jobId())).isEqualTo(1);
      Long selectedJobInstanceId =
          jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, fixture.jobNodeId());
      assertThat(selectedJobInstanceId).isEqualTo(fixture.jobId());
    } finally {
      allowNodeCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("WorkflowRun 历史 JobInstance 关联与父删除按创建时存在性串行化")
  void shouldSerializeWorkflowRunSourceAttachmentAgainstJobInstanceDelete() throws Exception {
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set job_instance_id = null where id = ?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where id = ?
    """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set related_job_instance_id = ? where id = ?
    """;
    final String selectCountSql = """
        select count(*) from pg_stat_activity where pid = ? and wait_event_type = 'Lock'
    """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.workflow_run where id = ?
    """;

    ArchiveFixture fixture = insertFixture();
    jdbcTemplate.update(
        updateWorkflowNodeRunSql,
        fixture.jobNodeId());
    CountDownLatch parentDeleted = new CountDownLatch(1);
    CountDownLatch allowParentCommit = new CountDownLatch(1);
    CountDownLatch linkStarted = new CountDownLatch(1);
    AtomicInteger linkPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> delete = null;
    Future<?> attach = null;
    try {
      delete = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            jdbcTemplate.update(deleteJobInstanceSql, fixture.jobId());
            parentDeleted.countDown();
            awaitLatch(allowParentCommit);
          }));
      assertThat(parentDeleted.await(5, TimeUnit.SECONDS)).isTrue();
      attach = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            linkPid.set(jdbcTemplate.queryForObject(selectPgBackendPidSql, Integer.class));
            linkStarted.countDown();
            jdbcTemplate.update(
                updateWorkflowRunSql,
                fixture.jobId(),
                fixture.runId());
          }));
      assertThat(linkStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> jdbcTemplate.queryForObject(
                  selectCountSql,
                  Integer.class,
                  linkPid.get())
              == 1);

      allowParentCommit.countDown();
      delete.get(10, TimeUnit.SECONDS);
      Future<?> attachFuture = attach;
      assertThatThrownBy(() -> attachFuture.get(10, TimeUnit.SECONDS))
          .isInstanceOf(ExecutionException.class)
          .hasCauseInstanceOf(DataIntegrityViolationException.class);
      assertThat(count("batch.job_instance", fixture.jobId())).isZero();
      Long selectedRelatedJobInstanceId =
          jdbcTemplate.queryForObject(selectRelatedJobInstanceIdSql, Long.class, fixture.runId());
      assertThat(selectedRelatedJobInstanceId).isNull();
    } finally {
      allowParentCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("WorkflowRun 历史关联提交后允许删除 JobInstance 并保留引用")
  void shouldKeepWorkflowRunSourceLinkAsHistory_whenParentIsDeletedAfterAttachment()
      throws Exception {
        final String updateWorkflowNodeRunSql = """
            update batch.workflow_node_run set job_instance_id = null where id = ?
        """;
        final String updateWorkflowRunSql = """
            update batch.workflow_run set related_job_instance_id = ? where id = ?
        """;
        final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where id = ?
    """;
    final String selectCountSql = """
        select count(*) from pg_stat_activity where pid = ? and wait_event_type = 'Lock'
    """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.workflow_run where id = ?
    """;

    ArchiveFixture fixture = insertFixture();
        jdbcTemplate.update(
        updateWorkflowNodeRunSql,
        fixture.jobNodeId());
    CountDownLatch linkWritten = new CountDownLatch(1);
    CountDownLatch allowLinkCommit = new CountDownLatch(1);
    CountDownLatch deleteStarted = new CountDownLatch(1);
    AtomicInteger deletePid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> attach = null;
    Future<?> delete = null;
    try {
      attach = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            jdbcTemplate.update(
                updateWorkflowRunSql,
                fixture.jobId(),
                fixture.runId());
            linkWritten.countDown();
            awaitLatch(allowLinkCommit);
          }));
      assertThat(linkWritten.await(5, TimeUnit.SECONDS)).isTrue();
      delete = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            deletePid.set(jdbcTemplate.queryForObject(selectPgBackendPidSql, Integer.class));
            deleteStarted.countDown();
            jdbcTemplate.update(deleteJobInstanceSql, fixture.jobId());
          }));
      assertThat(deleteStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> jdbcTemplate.queryForObject(
                  selectCountSql,
                  Integer.class,
                  deletePid.get())
              == 1);

      allowLinkCommit.countDown();
      attach.get(10, TimeUnit.SECONDS);
      delete.get(10, TimeUnit.SECONDS);
      assertThat(count("batch.job_instance", fixture.jobId())).isZero();
      Long selectedRelatedJobInstanceId =
          jdbcTemplate.queryForObject(selectRelatedJobInstanceIdSql, Long.class, fixture.runId());
      assertThat(selectedRelatedJobInstanceId).isEqualTo(fixture.jobId());
    } finally {
      allowLinkCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("WorkflowRun 历史 JobInstance 关联拒绝跨租户目标")
  void shouldRejectCrossTenantWorkflowRunSourceAttachment() {
    final String updateWorkflowRunSql = """
        update batch.workflow_run set related_job_instance_id = ? where id = ?
    """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.workflow_run where id = ?
    """;

    ArchiveFixture fixture = insertFixture();
    ArchiveFixture foreignFixture = insertFixture();

    assertForeignKeyViolation(() -> jdbcTemplate.update(
        updateWorkflowRunSql,
        foreignFixture.jobId(),
        fixture.runId()));
    Long selectedRelatedJobInstanceId =
        jdbcTemplate.queryForObject(selectRelatedJobInstanceIdSql, Long.class, fixture.runId());
    assertThat(selectedRelatedJobInstanceId).isNull();
    assertThat(count("batch.job_instance", foreignFixture.jobId())).isEqualTo(1);
  }

  @Test
  @DisplayName("WorkflowRun 历史关联存在时拒绝改写 JobInstance 身份")
  void shouldRejectRekeyingJobInstanceWhileWorkflowRunHistoryReferencesIt() {
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set job_instance_id = null where id = ?
    """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set related_job_instance_id = ? where id = ?
    """;
    final String updateJobInstanceSql = """
        update batch.job_instance set id = id + 1000000000000 where id = ?
    """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.workflow_run where id = ?
    """;

    ArchiveFixture fixture = insertFixture();
    jdbcTemplate.update(
        updateWorkflowNodeRunSql,
        fixture.jobNodeId());
    jdbcTemplate.update(
        updateWorkflowRunSql,
        fixture.jobId(),
        fixture.runId());

    assertForeignKeyViolation(() -> jdbcTemplate.update(
        updateJobInstanceSql, fixture.jobId()));
    assertThat(count("batch.job_instance", fixture.jobId())).isEqualTo(1);
    Long selectedRelatedJobInstanceId =
        jdbcTemplate.queryForObject(selectRelatedJobInstanceIdSql, Long.class, fixture.runId());
    assertThat(selectedRelatedJobInstanceId).isEqualTo(fixture.jobId());
  }

  @Test
  @DisplayName("WorkflowRun 历史关联存在时拒绝改写 JobInstance 业务日分区身份")
  void shouldRejectChangingJobInstanceBusinessDateWhileWorkflowRunHistoryReferencesIt() {
    final String updateWorkflowNodeRunSql = """
        update batch.workflow_node_run set job_instance_id = null where id = ?
    """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set related_job_instance_id = ? where id = ?
    """;
    final String updateJobInstanceSql = """
        update batch.job_instance set biz_date = biz_date + 1 where id = ?
    """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.workflow_run where id = ?
    """;

    ArchiveFixture fixture = insertFixture();
    jdbcTemplate.update(
        updateWorkflowNodeRunSql,
        fixture.jobNodeId());
    jdbcTemplate.update(
        updateWorkflowRunSql,
        fixture.jobId(),
        fixture.runId());

    assertForeignKeyViolation(() -> jdbcTemplate.update(
        updateJobInstanceSql, fixture.jobId()));
    assertThat(count("batch.job_instance", fixture.jobId())).isEqualTo(1);
    Long selectedRelatedJobInstanceId =
        jdbcTemplate.queryForObject(selectRelatedJobInstanceIdSql, Long.class, fixture.runId());
    assertThat(selectedRelatedJobInstanceId).isEqualTo(fixture.jobId());
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for test transaction release");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "Interrupted while waiting for test transaction release", interrupted);
    }
  }

  @Test
  @DisplayName("复制根记录失败回滚已复制节点，保留热运行树并允许修复后重试")
  void shouldRetainHotTreeAndRollbackColdNodesWhenRootCopyFails() {
    ArchiveFixture fixture = insertFixture();
    String root = snapshot("batch.workflow_run", fixture.runId());
    String jobNode = snapshot("batch.workflow_node_run", fixture.jobNodeId());
    String constraint = "archive_probe_" + UUID.randomUUID().toString().replace("-", "");
    final String alterWorkflowRunArchiveSql = """
        alter table archive.workflow_run_archive add constraint %s check (id <> %s)\
        """.formatted(constraint, fixture.runId());
    jdbcTemplate.execute(alterWorkflowRunArchiveSql);
    try {
      assertThatThrownBy(archiveService::archiveOnce).hasMessageContaining(constraint);
      assertThat(snapshot("batch.workflow_run", fixture.runId())).isEqualTo(root);
      assertThat(snapshot("batch.workflow_node_run", fixture.jobNodeId())).isEqualTo(jobNode);
      assertThat(count("archive.workflow_run_archive", fixture.runId())).isZero();
      assertThat(count("archive.workflow_node_run_archive", fixture.jobNodeId()))
          .isZero();
      assertThat(count("archive.workflow_node_run_archive", fixture.endNodeId()))
          .isZero();
    } finally {
      final String dropWorkflowRunArchiveConstraintSql = """
          alter table archive.workflow_run_archive drop constraint %s\
          """.formatted(constraint);
      jdbcTemplate.execute(dropWorkflowRunArchiveConstraintSql);
    }

    archiveService.archiveOnce();
    assertThat(snapshot("archive.workflow_node_run_archive", fixture.jobNodeId()))
        .isEqualTo(jobNode);
    assertThat(count("batch.workflow_run", fixture.runId())).isZero();
  }

  @Test
  @DisplayName("已有冷节点丢失 JOB 关联时拒绝静默跳过冲突并删除热数据")
  void shouldRejectMismatchedColdNodeBeforeDeletingHotTree() {
    final String insertWorkflowNodeRunArchiveSql = """
          insert into archive.workflow_node_run_archive (
            id, workflow_run_id, node_code, node_type, node_status, job_instance_id
          ) select id, workflow_run_id, node_code, node_type, node_status, null
            from batch.workflow_node_run where id = ?
          """;
    final String deleteWorkflowNodeRunArchiveSql = """
        delete from archive.workflow_node_run_archive where id = ?
    """;

    ArchiveFixture fixture = insertFixture();
    String root = snapshot("batch.workflow_run", fixture.runId());
    String jobNode = snapshot("batch.workflow_node_run", fixture.jobNodeId());
    jdbcTemplate.update(insertWorkflowNodeRunArchiveSql, fixture.jobNodeId());
    try {
      assertThatThrownBy(archiveService::archiveOnce)
          .hasMessageContaining("Workflow archive copy verification failed");
      assertThat(snapshot("batch.workflow_run", fixture.runId())).isEqualTo(root);
      assertThat(snapshot("batch.workflow_node_run", fixture.jobNodeId())).isEqualTo(jobNode);
      assertThat(count("archive.workflow_run_archive", fixture.runId())).isZero();
      assertThat(count("archive.workflow_node_run_archive", fixture.endNodeId()))
          .isZero();
    } finally {
      // 只移除本用例注入的坏冷记录，随后走生产归档入口完成重试。
      jdbcTemplate.update(
          deleteWorkflowNodeRunArchiveSql, fixture.jobNodeId());
    }
    archiveService.archiveOnce();
    assertThat(snapshot("archive.workflow_node_run_archive", fixture.jobNodeId()))
        .isEqualTo(jobNode);
  }

  @Test
  @DisplayName("已有完整一致冷副本时允许幂等完成热表清理")
  void shouldAcceptMatchingColdCopiesWhenRetryingArchive() {
    ArchiveFixture fixture = insertFixture();
    String jobNode = snapshot("batch.workflow_node_run", fixture.jobNodeId());
    workflowRunMapper.archiveWorkflowNodeRunsByWorkflowRunIds(List.of(fixture.runId()));
    workflowRunMapper.archiveWorkflowRunsByIds(List.of(fixture.runId()));

    archiveService.archiveOnce();

    assertThat(count("batch.workflow_run", fixture.runId())).isZero();
    assertThat(count("batch.workflow_node_run", fixture.jobNodeId())).isZero();
    assertThat(snapshot("archive.workflow_node_run_archive", fixture.jobNodeId()))
        .isEqualTo(jobNode);
  }

  @Test
  @DisplayName("已有冷根的版本与热根不一致时保留两侧证据且回滚新复制的冷节点")
  void shouldRejectMismatchedColdRootBeforeDeletingHotTree() {
    final String updateWorkflowRunArchiveSql = """
        update archive.workflow_run_archive set workflow_version_snapshot = 99 where id = ?
    """;
    final String deleteWorkflowRunArchiveSql = """
        delete from archive.workflow_run_archive where id = ?
    """;

    ArchiveFixture fixture = insertFixture();
    String root = snapshot("batch.workflow_run", fixture.runId());
    workflowRunMapper.archiveWorkflowRunsByIds(List.of(fixture.runId()));
    jdbcTemplate.update(
        updateWorkflowRunArchiveSql,
        fixture.runId());
    String conflictingRoot = snapshot("archive.workflow_run_archive", fixture.runId());
    try {
      assertThatThrownBy(archiveService::archiveOnce)
          .hasMessageContaining("Workflow archive copy verification failed");
      assertThat(snapshot("batch.workflow_run", fixture.runId())).isEqualTo(root);
      assertThat(snapshot("archive.workflow_run_archive", fixture.runId()))
          .isEqualTo(conflictingRoot);
      assertThat(count("batch.workflow_node_run", fixture.jobNodeId())).isEqualTo(1);
      assertThat(count("archive.workflow_node_run_archive", fixture.jobNodeId()))
          .isZero();
      assertThat(count("archive.workflow_node_run_archive", fixture.endNodeId()))
          .isZero();
    } finally {
      jdbcTemplate.update(deleteWorkflowRunArchiveSql, fixture.runId());
    }
    archiveService.archiveOnce();
    assertThat(snapshot("archive.workflow_run_archive", fixture.runId())).isEqualTo(root);
  }

  private ArchiveFixture insertFixture() {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
            tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
          ) values (?, 'archive-job', 'Archive Job', 'ATOMIC', 'MANUAL', 'Asia/Shanghai', 'NONE') returning id
          """;
    final String insertJobInstanceSql = """
          insert into batch.job_instance (
            tenant_id, instance_no, job_definition_id, job_code, trigger_type, dedup_key,
            biz_date, instance_status, started_at, finished_at
          ) values (?, ?, ?, 'archive-job', 'MANUAL', ?, current_date, 'SUCCESS',
            now() - interval '3 days', now() - interval '2 days') returning id
          """;
  final String insertWorkflowDefinitionSql = """
          insert into batch.workflow_definition (
            tenant_id, workflow_code, workflow_name, calendar_code
          ) values (?, 'archive-workflow', 'Archive Workflow', 'archive-calendar') returning id
          """;
  final String insertWorkflowRunSql = """
          insert into batch.workflow_run (
            tenant_id, workflow_definition_id, biz_date, run_status, finished_at,
            workflow_version_snapshot, execution_snapshot
          ) values (?, ?, current_date, 'SUCCESS', now() - interval '2 days', 7,
            '{"nodes":["job","end"]}'::jsonb) returning id
          """;
  final String insertWorkflowNodeRunSql = """
          insert into batch.workflow_node_run (
            workflow_run_id, job_instance_id, node_code, node_type, node_status, output
          ) values (?, ?, 'job', 'JOB', 'SUCCESS', '{"result":"retained"}'::jsonb) returning id
          """;
  final String insertWorkflowNodeRunSql2 = """
        insert into batch.workflow_node_run (workflow_run_id, node_code, node_type, node_status)
        values (?, 'end', 'END', 'SUCCESS') returning id
        """;

    String tenant = "archive-" + UUID.randomUUID();
    Long definitionId = jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenant);
    Long jobId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        tenant,
        "archive-job-" + UUID.randomUUID(),
        definitionId,
        "archive-dedup-" + UUID.randomUUID());
    Long workflowId = jdbcTemplate.queryForObject(insertWorkflowDefinitionSql, Long.class, tenant);
    Long runId = jdbcTemplate.queryForObject(insertWorkflowRunSql, Long.class, tenant, workflowId);
    Long jobNodeId = jdbcTemplate.queryForObject(insertWorkflowNodeRunSql, Long.class, runId, jobId);
    Long endNodeId = jdbcTemplate.queryForObject(insertWorkflowNodeRunSql2, Long.class, runId);
    ArchiveFixture fixture = new ArchiveFixture(runId, jobId, jobNodeId, endNodeId);
    fixtures.add(fixture);
    return fixture;
  }

  @AfterEach
  void cleanOwnedRuntimeFixtures() {
    final String deleteWorkflowNodeRunSql = """
        delete from batch.workflow_node_run where id in (?, ?)
    """;
    final String deleteWorkflowNodeRunArchiveSql = """
        delete from archive.workflow_node_run_archive where id in (?, ?)
    """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where id = ?
    """;
    final String deleteWorkflowRunArchiveSql = """
        delete from archive.workflow_run_archive where id = ?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where id = ?
    """;
    final String deleteJobInstanceArchiveSql = """
        delete from archive.job_instance_archive where id = ?
    """;

    for (ArchiveFixture fixture : fixtures) {
      jdbcTemplate.update(
          deleteWorkflowNodeRunSql,
          fixture.jobNodeId(),
          fixture.endNodeId());
      jdbcTemplate.update(
          deleteWorkflowNodeRunArchiveSql,
          fixture.jobNodeId(),
          fixture.endNodeId());
      jdbcTemplate.update(deleteWorkflowRunSql, fixture.runId());
      jdbcTemplate.update(deleteWorkflowRunArchiveSql, fixture.runId());
      jdbcTemplate.update(deleteJobInstanceSql, fixture.jobId());
      jdbcTemplate.update(deleteJobInstanceArchiveSql, fixture.jobId());
    }
  }

  private String snapshot(String table, Long id) {
  final String selectToJsonbSql = """
      select to_jsonb(row_data)::text from %s row_data where id = ?\
      """.formatted(table);
  return jdbcTemplate.queryForObject(
        selectToJsonbSql,
        String.class,
        id);
  }

  private int count(String table, Long id) {
  final String selectCountSql = """
      select count(*) from %s where id = ?\
      """.formatted(table);
  return jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, id);
  }

  private static void assertForeignKeyViolation(Runnable write) {
    Throwable failure = catchThrowable(write::run);
    assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(PSQLException.class);
    assertThat(((PSQLException) cause).getSQLState()).isEqualTo("23503");
  }

  private record ArchiveFixture(Long runId, Long jobId, Long jobNodeId, Long endNodeId) {}
}
