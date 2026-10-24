package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import io.github.pinpols.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
  "batch.job-instance.archive.enabled=true",
  "batch.job-instance.archive.retention-days=1",
  "batch.job-instance.archive.batch-size=10"
})
@DisplayName("Pipeline 续跑位点随运行树归档，复制失败保留热数据且父删除与新写入保持完整性")
class PipelineProgressArchiveIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbc;
  private final SuccessInstanceArchiveService archiveService;
  private final SuccessInstanceArchiveMapper archiveMapper;
  private final List<Fixture> fixtures = new ArrayList<>();

  @Autowired
  PipelineProgressArchiveIntegrationTest(
      JdbcTemplate jdbc,
      SuccessInstanceArchiveService archiveService,
      SuccessInstanceArchiveMapper archiveMapper) {
    this.jdbc = jdbc;
    this.archiveService = archiveService;
    this.archiveMapper = archiveMapper;
  }

  @ParameterizedTest
  @ValueSource(strings = {"SUCCESS", "FAILED", "TERMINATED"})
  @DisplayName("终态 Pipeline 归档保留完整位点及同租户冷运行树，未归档实例位点不变")
  void shouldPreserveCheckpointAndColdLineageBeforeRemovingHotPipeline(String status) {
    Fixture target = insertFixture(status);
    Fixture running = insertFixture("RUNNING");
    String progress = snapshot("batch.pipeline_progress", target.progressId());
    String runningProgress = snapshot("batch.pipeline_progress", running.progressId());

    archiveService.archiveOnce();

    assertThat(snapshot("archive.pipeline_progress_archive", target.progressId())).isEqualTo(progress);
    assertThat(count("batch.pipeline_progress", target.progressId())).isZero();
    assertThat(count("batch.pipeline_instance", target.pipelineId())).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from archive.pipeline_progress_archive progress
        join archive.pipeline_instance_archive pipeline
          on pipeline.id = progress.pipeline_instance_id and pipeline.tenant_id = progress.tenant_id
        join archive.job_instance_archive job
          on job.id = pipeline.related_job_instance_id and job.tenant_id = pipeline.tenant_id
        where progress.id = ?
        """, Integer.class, target.progressId())).isEqualTo(1);
    assertThat(snapshot("batch.pipeline_progress", running.progressId())).isEqualTo(runningProgress);
  }

  @Test
  @DisplayName("位点冷复制失败时整批回滚，热父子保持不变并允许修复后重试")
  void shouldRetainHotRuntimeTreeWhenCheckpointCopyFails() {
    Fixture fixture = insertFixture("SUCCESS");
    String progress = snapshot("batch.pipeline_progress", fixture.progressId());
    String pipeline = snapshot("batch.pipeline_instance", fixture.pipelineId());
    String constraint = "progress_probe_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("alter table archive.pipeline_progress_archive add constraint " + constraint
        + " check (id <> " + fixture.progressId() + ")");
    try {
      assertThatThrownBy(archiveService::archiveOnce).hasMessageContaining(constraint);
      assertThat(snapshot("batch.pipeline_progress", fixture.progressId())).isEqualTo(progress);
      assertThat(snapshot("batch.pipeline_instance", fixture.pipelineId())).isEqualTo(pipeline);
      assertThat(count("batch.job_instance", fixture.jobId())).isEqualTo(1);
      assertThat(count("archive.job_instance_archive", fixture.jobId())).isZero();
    } finally {
      jdbc.execute("alter table archive.pipeline_progress_archive drop constraint " + constraint);
    }
    archiveService.archiveOnce();
    assertThat(snapshot("archive.pipeline_progress_archive", fixture.progressId())).isEqualTo(progress);
  }

  @Test
  @DisplayName("已有错误冷位点时不以幂等为由删除热位点和父实例")
  void shouldRejectConflictingColdCheckpointBeforeHotCleanup() {
    Fixture fixture = insertFixture("SUCCESS");
    String progress = snapshot("batch.pipeline_progress", fixture.progressId());
    jdbc.update("""
        insert into archive.pipeline_progress_archive (
          id, tenant_id, pipeline_instance_id, stage, position_marker, processed_count,
          completed, completed_at, created_at, updated_at
        ) select id, tenant_id, pipeline_instance_id, stage, 'wrong-marker', processed_count,
          completed, completed_at, created_at, updated_at
          from batch.pipeline_progress where id = ?
        """, fixture.progressId());
    try {
      assertThatThrownBy(archiveService::archiveOnce)
          .hasMessageContaining("Pipeline checkpoint archive copy verification failed");
      assertThat(snapshot("batch.pipeline_progress", fixture.progressId())).isEqualTo(progress);
      assertThat(count("batch.pipeline_instance", fixture.pipelineId())).isEqualTo(1);
      assertThat(count("archive.job_instance_archive", fixture.jobId())).isZero();
    } finally {
      jdbc.update("delete from archive.pipeline_progress_archive where id = ?", fixture.progressId());
    }
    archiveService.archiveOnce();
    assertThat(snapshot("archive.pipeline_progress_archive", fixture.progressId())).isEqualTo(progress);
  }

  @Test
  @DisplayName("数据库拒绝错租户和悬空位点，且不允许直接删除仍有位点的父实例")
  void shouldEnforceTenantScopedParentReferenceForDirectSql() {
    Fixture fixture = insertFixture("RUNNING");
    assertForeignKeyFailure(() -> jdbc.update("""
        insert into batch.pipeline_progress (tenant_id, pipeline_instance_id, stage)
        values ('wrong-tenant', ?, 'LOAD')
        """, fixture.pipelineId()));
    assertForeignKeyFailure(() -> jdbc.update("""
        insert into batch.pipeline_progress (tenant_id, pipeline_instance_id, stage)
        values (?, -1, 'LOAD')
        """, fixture.tenant()));
    assertForeignKeyFailure(() -> jdbc.update(
        "delete from batch.pipeline_instance where id = ?", fixture.pipelineId()));
    assertThat(count("batch.pipeline_instance", fixture.pipelineId())).isEqualTo(1);
    assertThat(count("batch.pipeline_progress", fixture.progressId())).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  @DisplayName("归档持父子锁期间首次写入或已有位点更新等待，提交后不产生热孤儿或静默覆盖冷证据")
  void shouldProtectCheckpointWritesWhileParentArchiveCommits(boolean firstWrite) throws Exception {
    Fixture fixture = insertFixture("SUCCESS");
    String originalProgress = snapshot("batch.pipeline_progress", fixture.progressId());
    if (firstWrite) {
      jdbc.update("delete from batch.pipeline_progress where id = ?", fixture.progressId());
    }
    TransactionTemplate transaction = new TransactionTemplate(
        new DataSourceTransactionManager(jdbc.getDataSource()));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    AtomicInteger writerPid = new AtomicInteger();
    try {
      Future<Integer> writer = transaction.execute(status -> {
        assertThat(archiveMapper.selectPipelineInstanceIdsForUpdate(List.of(fixture.jobId())))
            .containsExactly(fixture.pipelineId());
        archiveMapper.selectPipelineProgressIdsForUpdate(List.of(fixture.pipelineId()));
        Future<Integer> pending = executor.submit(() -> {
          try (Connection connection = jdbc.getDataSource().getConnection()) {
            try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select pg_backend_pid()")) {
              result.next();
              writerPid.set(result.getInt(1));
            }
            String sql = firstWrite ? """
                insert into batch.pipeline_progress (tenant_id, pipeline_instance_id, stage)
                values (?, ?, 'LOAD')
                """ : """
                update batch.pipeline_progress set position_marker = 'late-marker'
                where tenant_id = ? and id = ?
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
              statement.setString(1, fixture.tenant());
              statement.setLong(2, firstWrite ? fixture.pipelineId() : fixture.progressId());
              return statement.executeUpdate();
            }
          } catch (SQLException exception) {
            throw new IllegalStateException("concurrent checkpoint write rejected", exception);
          }
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
          assertThat(writerPid.get()).isPositive();
          assertThat(jdbc.queryForObject(
              "select wait_event_type from pg_stat_activity where pid = ?", String.class, writerPid.get()))
              .isEqualTo("Lock");
        });
        archiveService.archiveOnce();
        return pending;
      });
      if (firstWrite) {
        assertThatThrownBy(() -> writer.get(5, TimeUnit.SECONDS))
            .rootCause().isInstanceOfSatisfying(
                SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
      } else {
        assertThat(writer.get(5, TimeUnit.SECONDS)).isZero();
        assertThat(snapshot("archive.pipeline_progress_archive", fixture.progressId())).isEqualTo(originalProgress);
      }
      assertThat(jdbc.queryForObject(
          "select count(*) from batch.pipeline_progress where pipeline_instance_id = ?",
          Integer.class, fixture.pipelineId())).isZero();
      assertThat(count("archive.pipeline_instance_archive", fixture.pipelineId())).isEqualTo(1);
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "scripts/db/cleanup-success-instances.sql",
    "scripts/db/cleanup-historical-failures.sql"
  })
  @DisplayName("保留期清理脚本在删除前拒绝带位点的候选，保持热数据与证据")
  void shouldRejectRetentionOnlyCleanupBeforeAnyDelete(String script) throws Exception {
    Fixture fixture = insertFixture(script.contains("historical") ? "FAILED" : "SUCCESS");
    String progress = snapshot("batch.pipeline_progress", fixture.progressId());
    String job = snapshot("batch.job_instance", fixture.jobId());

    ExecResult result = executeCleanupScript(script, "success_retention_days", "1");

    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isEqualTo(3);
    assertThat(result.getStderr()).contains("checkpoint-bearing instances require");
    assertThat(snapshot("batch.pipeline_progress", fixture.progressId())).isEqualTo(progress);
    assertThat(snapshot("batch.job_instance", fixture.jobId())).isEqualTo(job);
    assertThat(count("batch.pipeline_instance", fixture.pipelineId())).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "load-tests/sql/cleanup-control-plane-worker.sql",
    "load-tests/sql/cleanup-worker-load-platform.sql",
    "scripts/local/sql/validate-seed-cleanup-platform.sql"
  })
  @DisplayName("隔离夹具清理先删除自有位点和父实例，保留另一个运行的位点")
  void shouldCleanOnlyOwnedFixtureCheckpointsBeforeDeletingParents(String script) throws Exception {
    Fixture target = insertFixture("SUCCESS");
    Fixture other = insertFixture("RUNNING");
    String otherProgress = snapshot("batch.pipeline_progress", other.progressId());
    String runId = "progress-probe-" + UUID.randomUUID();
    String jobCode = script.contains("validate-seed") ? runId : "import_customer_job";
    jdbc.update("update batch.job_definition set job_code = ? where tenant_id = ?", jobCode, target.tenant());
    jdbc.update("""
        update batch.job_instance set job_code = ?,
          params_snapshot = jsonb_build_object('metadata', jsonb_build_object('runId', ?::text))
        where id = ?
        """, jobCode, runId, target.jobId());
    jdbc.update("update batch.pipeline_instance set job_code = ? where id = ?", jobCode, target.pipelineId());

    ExecResult result = executeCleanupScript(script, script.contains("validate-seed") ? "pattern" : "run_id", runId);

    assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
    assertThat(count("batch.pipeline_progress", target.progressId())).isZero();
    assertThat(count("batch.pipeline_instance", target.pipelineId())).isZero();
    assertThat(count("batch.job_instance", target.jobId())).isZero();
    assertThat(snapshot("batch.pipeline_progress", other.progressId())).isEqualTo(otherProgress);
    assertThat(count("batch.pipeline_instance", other.pipelineId())).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {
    "scripts/db/cleanup-success-instances.sql",
    "scripts/db/cleanup-historical-failures.sql"
  })
  @DisplayName("独立空库中无位点的过期候选仍可清理，运行中控制实例保留")
  void shouldAllowRetentionCleanupWithoutCheckpointInIsolatedDatabase(String script) throws Exception {
    String database = "checkpoint_cleanup_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("create database " + database);
    try {
      String url = "jdbc:postgresql://" + PLATFORM_POSTGRES.getHost() + ":"
          + PLATFORM_POSTGRES.getMappedPort(5432) + "/" + database;
      Flyway.configure().dataSource(url, PLATFORM_POSTGRES.getUsername(), PLATFORM_POSTGRES.getPassword())
          .schemas("batch", "quartz").defaultSchema("batch").locations("classpath:db/migration")
          .configuration(Map.of("flyway.postgresql.transactional.lock", "false")).load().migrate();
      JdbcTemplate isolated = new JdbcTemplate(new DriverManagerDataSource(
          url, PLATFORM_POSTGRES.getUsername(), PLATFORM_POSTGRES.getPassword()));
      Long target = insertPurgeCandidate(isolated, script.contains("historical") ? "FAILED" : "SUCCESS");
      Long running = insertPurgeCandidate(isolated, "RUNNING");

      ExecResult result = executeCleanupScript(script, "success_retention_days", "1", database);

      assertThat(result.getExitCode()).as(result.getStdout() + result.getStderr()).isZero();
      assertThat(isolated.queryForObject(
          "select count(*) from batch.job_instance where id = ?", Integer.class, target)).isZero();
      assertThat(isolated.queryForObject(
          "select instance_status from batch.job_instance where id = ?", String.class, running))
          .isEqualTo("RUNNING");
    } finally {
      jdbc.execute("drop database " + database + " with (force)");
    }
  }

  private Long insertPurgeCandidate(JdbcTemplate database, String status) {
    String tenant = "cleanup-" + UUID.randomUUID();
    Long definition = database.queryForObject("""
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
        ) values (?, 'cleanup-job', 'Cleanup Job', 'GENERAL', 'MANUAL', 'Asia/Shanghai', 'NONE') returning id
        """, Long.class, tenant);
    return database.queryForObject("""
        insert into batch.job_instance (
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, dedup_key, created_at, finished_at
        ) values (?, ?, 'cleanup-job', ?, current_date - 3, 'MANUAL', ?, ?,
          now() - interval '3 days', now() - interval '2 days') returning id
        """, Long.class, tenant, definition, UUID.randomUUID().toString(), status, UUID.randomUUID().toString());
  }

  private ExecResult executeCleanupScript(String script, String variable, String value) throws Exception {
    return executeCleanupScript(script, variable, value, jdbc.queryForObject("select current_database()", String.class));
  }

  private ExecResult executeCleanupScript(String script, String variable, String value, String database) throws Exception {
    Path repository = Path.of(System.getProperty("user.dir")).getParent();
    PLATFORM_POSTGRES.copyFileToContainer(
        MountableFile.forHostPath(repository.resolve(script).toString()), "/tmp/checkpoint-cleanup-probe.sql");
    return PLATFORM_POSTGRES.execInContainer(
        "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", PLATFORM_POSTGRES.getUsername(),
        "-d", database,
        "-v", variable + "=" + value, "-f", "/tmp/checkpoint-cleanup-probe.sql");
  }

  private Fixture insertFixture(String status) {
    String tenant = "progress-" + UUID.randomUUID();
    Long definition = jdbc.queryForObject("""
        insert into batch.job_definition (
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
        ) values (?, 'progress-job', 'Progress Job', 'IMPORT', 'MANUAL', 'Asia/Shanghai', 'NONE') returning id
        """, Long.class, tenant);
    Long job = jdbc.queryForObject("""
        insert into batch.job_instance (
          tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
          instance_status, dedup_key, finished_at, created_at
        ) values (?, ?, 'progress-job', ?, current_date, 'MANUAL', ?, ?,
          now() - interval '2 days', now() - interval '3 days') returning id
        """, Long.class, tenant, definition, "progress-" + UUID.randomUUID(), status, UUID.randomUUID().toString());
    Long pipeline = jdbc.queryForObject("""
        insert into batch.pipeline_instance (
          tenant_id, job_code, pipeline_type, system_pipeline_code, system_pipeline_version,
          related_job_instance_id, run_status
        ) values (?, 'progress-job', 'IMPORT', 'IMPORT', 1, ?, ?) returning id
        """, Long.class, tenant, job, status);
    Long progress = jdbc.queryForObject("""
        insert into batch.pipeline_progress (
          tenant_id, pipeline_instance_id, stage, position_marker, processed_count, completed, completed_at
        ) values (?, ?, 'LOAD', 'row:500', 500, ?, case when ? then now() else null end) returning id
        """, Long.class, tenant, pipeline, "SUCCESS".equals(status), "SUCCESS".equals(status));
    Fixture fixture = new Fixture(tenant, job, pipeline, progress);
    fixtures.add(fixture);
    return fixture;
  }

  private void assertForeignKeyFailure(Runnable action) {
    assertThatThrownBy(action::run).rootCause().isInstanceOfSatisfying(
        SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
  }

  private String snapshot(String table, Long id) {
    return jdbc.queryForObject(
        "select to_jsonb(row_data)::text from " + table + " row_data where id = ?", String.class, id);
  }

  private int count(String table, Long id) {
    return jdbc.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
  }

  @AfterEach
  void cleanOwnedRuntimeFixtures() {
    for (Fixture fixture : fixtures) {
      jdbc.update("delete from batch.pipeline_progress where pipeline_instance_id = ?", fixture.pipelineId());
      jdbc.update("delete from archive.pipeline_progress_archive where pipeline_instance_id = ?", fixture.pipelineId());
      jdbc.update("delete from batch.pipeline_instance where id = ?", fixture.pipelineId());
      jdbc.update("delete from archive.pipeline_instance_archive where id = ?", fixture.pipelineId());
      jdbc.update("delete from batch.job_instance where id = ?", fixture.jobId());
      jdbc.update("delete from archive.job_instance_archive where id = ?", fixture.jobId());
    }
  }

  private record Fixture(String tenant, Long jobId, Long pipelineId, Long progressId) {}
}
