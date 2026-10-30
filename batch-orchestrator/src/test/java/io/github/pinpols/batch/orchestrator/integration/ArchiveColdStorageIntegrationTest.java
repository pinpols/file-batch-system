package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.application.archive.ArchiveIntegrityAlertService;
import io.github.pinpols.batch.orchestrator.application.archive.OutboxArchiveService;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService;
import io.github.pinpols.batch.orchestrator.application.archive.WorkflowArchiveService;
import io.github.pinpols.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateCompensationStatusParam;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.ResultVersionRetentionScheduler;
import io.github.pinpols.batch.orchestrator.mapper.CompensationCommandMapper;
import io.github.pinpols.batch.orchestrator.mapper.DataQualityCheckMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobExecutionLogMapper;
import io.github.pinpols.batch.orchestrator.mapper.LineageEvidenceMapper;
import io.github.pinpols.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(
    properties = {
      "batch.outbox.archive.enabled=true",
      "batch.outbox.archive.published-retention-days=1",
      "batch.outbox.archive.batch-size=10",
      "batch.job-instance.archive.enabled=true",
      "batch.job-instance.archive.retention-days=1",
      "batch.job-instance.archive.batch-size=10",
      "batch.workflow.archive.enabled=true",
      "batch.workflow.archive.retention-days=1",
      "batch.workflow.archive.batch-size=10",
      "batch.result-version.retention.enabled=false",
      "batch.result-version.retention.superseded-days=1",
      "batch.result-version.retention.archived-days=1",
      "batch.result-version.retention.batch-size=10",
      "batch.replay.dry-run.retention-days=1"
    })
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("冷存储归档:出站事件,成功实例运行树与结果版本的归档顺序,以及保留期清理与引用保护边界")
class ArchiveColdStorageIntegrationTest extends AbstractIntegrationTest {

  private final JdbcTemplate jdbcTemplate;
  private final OutboxArchiveService outboxArchiveService;
  private final SuccessInstanceArchiveService successInstanceArchiveService;
  private final WorkflowArchiveService workflowArchiveService;
  private final ResultVersionRetentionScheduler resultVersionRetentionScheduler;
  private final JobExecutionLogMapper jobExecutionLogMapper;
  private final DataQualityCheckMapper dataQualityCheckMapper;
  private final PlatformTransactionManager transactionManager;

  @Autowired
  private SuccessInstanceArchiveMapper archiveMapper;

  @Autowired
  private CompensationCommandMapper compensationCommandMapper;

  @Autowired
  private LineageEvidenceMapper lineageEvidenceMapper;

  @Autowired
  private SqlSessionTemplate sqlSessionTemplate;

  @Autowired
  ArchiveColdStorageIntegrationTest(
      JdbcTemplate jdbcTemplate,
      OutboxArchiveService outboxArchiveService,
      SuccessInstanceArchiveService successInstanceArchiveService,
      WorkflowArchiveService workflowArchiveService,
      ResultVersionRetentionScheduler resultVersionRetentionScheduler,
      JobExecutionLogMapper jobExecutionLogMapper,
      DataQualityCheckMapper dataQualityCheckMapper,
      PlatformTransactionManager transactionManager) {
    this.jdbcTemplate = jdbcTemplate;
    this.outboxArchiveService = outboxArchiveService;
    this.successInstanceArchiveService = successInstanceArchiveService;
    this.workflowArchiveService = workflowArchiveService;
    this.resultVersionRetentionScheduler = resultVersionRetentionScheduler;
    this.jobExecutionLogMapper = jobExecutionLogMapper;
    this.dataQualityCheckMapper = dataQualityCheckMapper;
    this.transactionManager = transactionManager;
  }

  @Test
  @DisplayName("共享文件两次作业归档之间状态变化时回读当前冷副本")
  void shouldExposeFirstSharedFileSnapshotAcrossLaterArchive() {
    final String insertFileRecordSql1 = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'LOADED') returning id
        """;
    final String updateJobInstanceSql2 =
        "update batch.job_instance set related_file_id = ? where tenant_id = ? and id = ?";
    final String updateFileRecordSql3 =
        "update batch.file_record set file_status = 'FAILED' where id = ?";
    final String updateJobInstanceSql4 =
        "update batch.job_instance set related_file_id = ? where tenant_id = ? and id = ?";
    final String selectFileRecordSql5 = "select file_status from batch.file_record where id = ?";
    String tenantId = unique("shared-file-history");
    Long definitionId = insertJobDefinition(tenantId);
    Long fileId = jdbcTemplate.queryForObject(
        insertFileRecordSql1, Long.class, tenantId, unique("file"), unique("path"));
    Long firstId = insertOldSuccessInstance(tenantId, definitionId);
    jdbcTemplate.update(updateJobInstanceSql2, fileId, tenantId, firstId);
    successInstanceArchiveService.archiveOnce();
    assertThat(count("archive.job_instance_archive", firstId)).isEqualTo(1);
    assertThat(jdbcTemplate.update(updateFileRecordSql3, fileId)).isEqualTo(1);

    Long secondId = insertOldSuccessInstance(tenantId, definitionId);
    jdbcTemplate.update(updateJobInstanceSql4, fileId, tenantId, secondId);
    successInstanceArchiveService.archiveOnce();

    assertThat(count("archive.job_instance_archive", secondId)).isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(selectFileRecordSql5, String.class, fileId))
        .isEqualTo("FAILED");
    assertThat(lineageEvidenceMapper.selectArchivedFileRecords(tenantId, firstId, null))
        .singleElement()
        .extracting(row -> row.fileStatus())
        .isEqualTo("LOADED");
    assertThat(lineageEvidenceMapper.selectArchivedFileRecords(tenantId, secondId, null))
        .singleElement()
        .extracting(row -> row.fileStatus())
        .isEqualTo("LOADED");
  }

  @Test
  @DisplayName("DQ执行证据随Job原子搬运且归档后可按同租户完整回读，重复执行不复制")
  void shouldArchiveDataQualityEvidenceAndReadColdRowsWithoutDuplicates() {
    String tenantId = unique("dq-archive");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long checkId = insertDataQualityEvidence(tenantId, instanceId);
    String selectHotQualityEvidenceSql = """
        select to_jsonb(c)::text from batch.data_quality_check c where id=?\
        """;
    String original =
        jdbcTemplate.queryForObject(selectHotQualityEvidenceSql, String.class, checkId);
    assertThat(dataQualityCheckMapper.selectByJobInstance(tenantId, instanceId)).hasSize(1);

    successInstanceArchiveService.archiveOnce();
    successInstanceArchiveService.archiveOnce();

    assertThat(count("batch.data_quality_check", checkId)).isZero();
    assertThat(count("archive.data_quality_check_archive", checkId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", instanceId)).isEqualTo(1);
    String selectArchivedQualityEvidenceSql = """
        select to_jsonb(c)::text from archive.data_quality_check_archive c where id=?\
        """;
    String selectedArchivedQualityEvidence =
        jdbcTemplate.queryForObject(selectArchivedQualityEvidenceSql, String.class, checkId);
    assertThat(selectedArchivedQualityEvidence).isEqualTo(original);
    assertThat(dataQualityCheckMapper.selectByJobInstance(tenantId, instanceId)).hasSize(1);
    assertThat(dataQualityCheckMapper.selectByJobInstance(unique("other-tenant"), instanceId))
        .isEmpty();
  }

  @Test
  @DisplayName("归档期间补偿命令关联作业必须与父行删除串行化")
  void shouldSerializeCompensationAssociationWithArchive() throws Exception {
    final String insertCompensationCommandSql6 = """
        insert into batch.compensation_command
          (tenant_id, command_no, compensation_type, target_id, command_status)
        values (?, ?, 'JOB', ?, 'RUNNING') returning id
        """;
    final String selectStatementSql7 = "select pg_backend_pid()";
    final String selectStatementSql8 = "select coalesce(array_length(pg_blocking_pids(?), 1), 0)";
    String tenantId = unique("archive-compensation-race");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long commandId = jdbcTemplate.queryForObject(
        insertCompensationCommandSql6, Long.class, tenantId, unique("compensation"), instanceId);
    SuccessInstanceArchiveMapper intercepted =
        mock(SuccessInstanceArchiveMapper.class, delegatesTo(archiveMapper));
    SuccessInstanceArchiveProperties props = new SuccessInstanceArchiveProperties();
    props.setEnabled(true);
    props.setRetentionDays(1);
    props.setBatchSize(10);
    SuccessInstanceArchiveService service = new SuccessInstanceArchiveService(
        intercepted, props, mock(ArchiveIntegrityAlertService.class));
    AtomicReference<Future<Integer>> update = new AtomicReference<>();
    AtomicReference<Integer> backendPid = new AtomicReference<>();
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      doAnswer(invocation -> {
            long mismatches =
                archiveMapper.countRuntimeTreeArchiveMismatches(invocation.getArgument(0));
            Future<Integer> future = executor.submit(() -> {
              TransactionTemplate transaction = new TransactionTemplate(transactionManager);
              return transaction.execute(status -> {
                backendPid.set(jdbcTemplate.queryForObject(selectStatementSql7, Integer.class));
                return compensationCommandMapper.updateStatus(
                    UpdateCompensationStatusParam.builder()
                        .tenantId(tenantId)
                        .id(commandId)
                        .expectedStatus("RUNNING")
                        .commandStatus("SUCCESS")
                        .relatedJobInstanceId(instanceId)
                        .build());
              });
            });
            update.set(future);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline && !future.isDone()) {
              Integer pid = backendPid.get();
              Integer blockers = pid == null
                  ? 0
                  : jdbcTemplate.queryForObject(selectStatementSql8, Integer.class, pid);
              if (blockers != null && blockers > 0) {
                waiting = true;
                break;
              }
              Thread.sleep(10);
            }
            assertThat(waiting).as("compensation association waits for archive").isTrue();
            return mismatches;
          })
          .when(intercepted)
          .countRuntimeTreeArchiveMismatches(anyList());
      new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> service.archiveOnce());
      assertThat(update.get().get(10, TimeUnit.SECONDS)).isZero();
      assertThat(count("archive.job_instance_archive", instanceId)).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("补偿关联仅允许同租户热作业，空关联可记录失败")
  void shouldValidateCompensationAssociationAndAllowUnboundFailure() {
    final String insertCompensationCommandSql9 = """
        insert into batch.compensation_command
          (tenant_id, command_no, compensation_type, target_id, command_status)
        values (?, ?, 'JOB', ?, 'RUNNING') returning id
        """;
    final String insertCompensationCommandSql10 = """
        insert into batch.compensation_command
          (tenant_id, command_no, compensation_type, target_id, command_status)
        values (?, ?, 'JOB', ?, 'RUNNING') returning id
        """;
    final String selectCompensationCommandSql11 =
        "select command_status from batch.compensation_command where id = ?";
    String tenantId = unique("compensation-association");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long commandId = jdbcTemplate.queryForObject(
        insertCompensationCommandSql9, Long.class, tenantId, unique("compensation"), instanceId);
    UpdateCompensationStatusParam.UpdateCompensationStatusParamBuilder update =
        UpdateCompensationStatusParam.builder()
            .tenantId(tenantId)
            .id(commandId)
            .expectedStatus("RUNNING")
            .commandStatus("SUCCESS");
    assertThat(compensationCommandMapper.updateStatus(
            update.relatedJobInstanceId(instanceId + 999999999L).build()))
        .isZero();
    String otherTenant = unique("other");
    Long otherInstanceId = insertOldSuccessInstance(otherTenant, insertJobDefinition(otherTenant));
    assertThat(compensationCommandMapper.updateStatus(
            update.relatedJobInstanceId(otherInstanceId).build()))
        .isZero();
    assertThat(compensationCommandMapper.updateStatus(
            update.relatedJobInstanceId(instanceId).build()))
        .isEqualTo(1);

    Long failureId = jdbcTemplate.queryForObject(
        insertCompensationCommandSql10,
        Long.class,
        tenantId,
        unique("compensation-failure"),
        instanceId);
    assertThat(compensationCommandMapper.updateStatus(update
            .id(failureId)
            .commandStatus("FAILED")
            .relatedJobInstanceId(null)
            .build()))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(selectCompensationCommandSql11, String.class, failureId))
        .isEqualTo("FAILED");
  }

  @Test
  @DisplayName("归档核对后并发更新不能在热行删除时丢失")
  void shouldNotLoseCommittedStepUpdateDuringArchive() throws Exception {
    String tenantId = unique("archive-step-race");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long partitionId = insertJobPartition(tenantId, instanceId);
    Long taskId = insertJobTask(tenantId, instanceId, partitionId);
    Long stepId = insertJobStepInstance(tenantId, instanceId, partitionId, taskId);
    String concurrentUpdateSql = """
        update batch.job_step_instance set error_message = 'concurrent-update' where id = ?
        """;
    String selectArchivedSql = """
        select error_message from archive.job_step_instance_archive where id = ?
        """;
    String backendPidSql = "select pg_backend_pid()";
    String blockedSql = "select coalesce(array_length(pg_blocking_pids(?), 1), 0)";
    SuccessInstanceArchiveMapper intercepted =
        mock(SuccessInstanceArchiveMapper.class, delegatesTo(archiveMapper));
    SuccessInstanceArchiveProperties props = new SuccessInstanceArchiveProperties();
    props.setEnabled(true);
    props.setRetentionDays(1);
    props.setBatchSize(10);
    SuccessInstanceArchiveService service = new SuccessInstanceArchiveService(
        intercepted, props, mock(ArchiveIntegrityAlertService.class));
    AtomicReference<Future<Integer>> update = new AtomicReference<>();
    AtomicReference<Integer> updateBackendPid = new AtomicReference<>();
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      doAnswer(invocation -> {
            long mismatches =
                archiveMapper.countRuntimeTreeArchiveMismatches(invocation.getArgument(0));
            Future<Integer> future = executor.submit(() -> {
              TransactionTemplate updateTransaction = new TransactionTemplate(transactionManager);
              return updateTransaction.execute(status -> {
                updateBackendPid.set(jdbcTemplate.queryForObject(backendPidSql, Integer.class));
                return jdbcTemplate.update(concurrentUpdateSql, stepId);
              });
            });
            update.set(future);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (System.nanoTime() < deadline && !future.isDone()) {
              Integer pid = updateBackendPid.get();
              Integer blockers =
                  pid == null ? 0 : jdbcTemplate.queryForObject(blockedSql, Integer.class, pid);
              if (blockers != null && blockers > 0) {
                waiting = true;
                break;
              }
              Thread.sleep(10);
            }
            assertThat(waiting)
                .as("concurrent update waits on the archive row lock")
                .isTrue();
            return mismatches;
          })
          .when(intercepted)
          .countRuntimeTreeArchiveMismatches(anyList());
      TransactionTemplate transaction = new TransactionTemplate(transactionManager);
      transaction.executeWithoutResult(status -> service.archiveOnce());
      int changed = update.get().get(10, TimeUnit.SECONDS);
      if (changed == 1) {
        String archived = jdbcTemplate.queryForObject(selectArchivedSql, String.class, stepId);
        assertThat(archived).isEqualTo("concurrent-update");
      } else {
        assertThat(changed).isZero();
      }
    }
  }

  @Test
  @DisplayName("分区冷行内容冲突时归档必须保留热运行树")
  void shouldRetainHotTreeWhenArchivedPartitionConflicts() {
    String tenantId = unique("partition-archive-conflict");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long partitionId = insertJobPartition(tenantId, instanceId);
    String copySql = """
        insert into archive.job_partition_archive
          (id, tenant_id, job_instance_id, job_instance_biz_date, partition_no,
           partition_status, business_key, idempotency_key, finished_at)
        select id, tenant_id, job_instance_id, job_instance_biz_date, partition_no,
               partition_status, business_key, idempotency_key, finished_at
        from batch.job_partition where id = ?
        """;
    String corruptSql = """
        update archive.job_partition_archive set partition_status = 'FAILED' where id = ?
        """;
    String deleteColdSql = """
        delete from archive.job_partition_archive where tenant_id = ? and id = ?
        """;
    String deletePartitionSql = """
        delete from batch.job_partition where tenant_id = ? and id = ?
        """;
    String deleteInstanceSql = """
        delete from batch.job_instance where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(copySql, partitionId);
    jdbcTemplate.update(corruptSql, partitionId);
    try {
      assertThatThrownBy(() -> successInstanceArchiveService.archiveOnce())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Runtime tree archive copy verification failed");
      assertThat(count("batch.job_instance", instanceId)).isEqualTo(1);
      assertThat(count("batch.job_partition", partitionId)).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteColdSql, tenantId, partitionId);
      jdbcTemplate.update(deletePartitionSql, tenantId, partitionId);
      jdbcTemplate.update(deleteInstanceSql, tenantId, instanceId);
    }
  }

  @Test
  @DisplayName("任务冷行内容冲突时归档必须保留完整热运行树")
  void shouldRetainHotTreeWhenArchivedTaskConflicts() {
    String tenantId = unique("task-archive-conflict");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long partitionId = insertJobPartition(tenantId, instanceId);
    Long taskId = insertJobTask(tenantId, instanceId, partitionId);
    String copySql = """
        insert into archive.job_task_archive
          (id, tenant_id, job_instance_id, job_instance_biz_date, job_partition_id,
           task_type, task_seq, task_status, finished_at)
        select id, tenant_id, job_instance_id, job_instance_biz_date, job_partition_id,
               task_type, task_seq, task_status, finished_at
        from batch.job_task where id = ?
        """;
    String corruptSql = """
        update archive.job_task_archive set error_message = 'conflicting-cold-error' where id = ?
        """;
    String deleteColdSql = """
        delete from archive.job_task_archive where tenant_id = ? and id = ?
        """;
    String deleteTaskSql = """
        delete from batch.job_task where tenant_id = ? and id = ?
        """;
    String deletePartitionSql = """
        delete from batch.job_partition where tenant_id = ? and id = ?
        """;
    String deleteInstanceSql = """
        delete from batch.job_instance where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(copySql, taskId);
    jdbcTemplate.update(corruptSql, taskId);
    try {
      assertThatThrownBy(() -> successInstanceArchiveService.archiveOnce())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Job task archive copy verification failed");
      assertThat(count("batch.job_instance", instanceId)).isEqualTo(1);
      assertThat(count("batch.job_partition", partitionId)).isEqualTo(1);
      assertThat(count("batch.job_task", taskId)).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteColdSql, tenantId, taskId);
      jdbcTemplate.update(deleteTaskSql, tenantId, taskId);
      jdbcTemplate.update(deletePartitionSql, tenantId, partitionId);
      jdbcTemplate.update(deleteInstanceSql, tenantId, instanceId);
    }
  }

  @Test
  @DisplayName("Job 冷行内容冲突时归档必须保留热实例")
  void shouldRetainHotInstanceWhenArchivedJobConflicts() {
    String tenantId = unique("job-archive-conflict");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    String copySql = """
        insert into archive.job_instance_archive select * from batch.job_instance where id = ?
        """;
    String corruptSql = """
        update archive.job_instance_archive set trace_id = 'conflicting-cold-trace' where id = ?
        """;
    String deleteColdSql = """
        delete from archive.job_instance_archive where tenant_id = ? and id = ?
        """;
    String deleteHotSql = """
        delete from batch.job_instance where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(copySql, instanceId);
    jdbcTemplate.update(corruptSql, instanceId);
    try {
      assertThatThrownBy(() -> successInstanceArchiveService.archiveOnce())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Job instance archive copy verification failed");
      assertThat(count("batch.job_instance", instanceId)).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteColdSql, tenantId, instanceId);
      jdbcTemplate.update(deleteHotSql, tenantId, instanceId);
    }
  }

  @Test
  @DisplayName("DQ冷行身份或内容冲突时整批归档回滚并保留热Job及检查证据")
  void shouldRetainHotDataWhenQualityArchiveCopyConflicts() {
    String tenantId = unique("dq-conflict");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long checkId = insertDataQualityEvidence(tenantId, instanceId);
    String copyQualityEvidenceToArchiveSql = """
        insert into archive.data_quality_check_archive
        select * from batch.data_quality_check where id=?
        """;
    jdbcTemplate.update(copyQualityEvidenceToArchiveSql, checkId);
    String corruptArchivedQualityStatusSql = """
        update archive.data_quality_check_archive set status='FAIL' where id=?\
        """;
    jdbcTemplate.update(corruptArchivedQualityStatusSql, checkId);

    try {
      assertThatThrownBy(() -> successInstanceArchiveService.archiveOnce())
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("Data quality check archive copy verification failed");

      assertThat(count("batch.job_instance", instanceId)).isEqualTo(1);
      assertThat(count("batch.data_quality_check", checkId)).isEqualTo(1);
      assertThat(count("archive.job_instance_archive", instanceId)).isZero();
      String selectArchivedQualityStatusSql = """
          select status from archive.data_quality_check_archive where id=?\
          """;
      String selectedStatus =
          jdbcTemplate.queryForObject(selectArchivedQualityStatusSql, String.class, checkId);
      assertThat(selectedStatus).isEqualTo("FAIL");
    } finally {
      String deleteArchivedQualityEvidenceSql = """
          delete from archive.data_quality_check_archive where tenant_id=? and id=?\
          """;
      jdbcTemplate.update(deleteArchivedQualityEvidenceSql, tenantId, checkId);
      String deleteHotQualityEvidenceSql = """
          delete from batch.data_quality_check where tenant_id=? and id=?\
          """;
      jdbcTemplate.update(deleteHotQualityEvidenceSql, tenantId, checkId);
      String deleteHotJobInstanceSql = """
          delete from batch.job_instance where tenant_id=? and id=?\
          """;
      jdbcTemplate.update(deleteHotJobInstanceSql, tenantId, instanceId);
    }
  }

  private Long insertDataQualityEvidence(String tenantId, Long instanceId) {
    String insertQualityEvidenceSql = """
        insert into batch.data_quality_check(
          tenant_id,job_instance_id,rule_code,rule_type,severity,status,metrics_json,failure_sample,error_message,checked_at)
        values (?,?,'archive-rule','TABLE_LEVEL','WARN','PASS','{"checked":7}'::jsonb,
          '[{"row":2}]'::jsonb,'sample evidence',now()-interval '2 days') returning id
        """;
    return jdbcTemplate.queryForObject(insertQualityEvidenceSql, Long.class, tenantId, instanceId);
  }

  @Test
  @DisplayName("应用日志写口只接受同租户现存实例并保留生成主键")
  void shouldGuardExecutionLogInstanceReferenceAtApplicationWriteBoundary() {
    String tenantId = unique("tenant");
    String otherTenantId = unique("other-tenant");
    Long instanceId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    JobExecutionLogEntity validLog = buildExecutionLog(tenantId, instanceId);

    assertThat(jobExecutionLogMapper.insert(validLog)).isEqualTo(1);
    assertThat(validLog.getId()).isPositive();

    JobExecutionLogEntity crossTenantLog = buildExecutionLog(otherTenantId, instanceId);
    JobExecutionLogEntity missingInstanceLog = buildExecutionLog(tenantId, Long.MAX_VALUE);
    assertThatThrownBy(() -> jobExecutionLogMapper.insert(crossTenantLog))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different-tenant JobInstance");
    assertThatThrownBy(() -> jobExecutionLogMapper.insert(missingInstanceLog))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("missing");

    String insertedLogCountSql = """
        select count(*) from batch.job_execution_log where tenant_id in (?, ?)
        """;
    Integer insertedLogCount =
        jdbcTemplate.queryForObject(insertedLogCountSql, Integer.class, tenantId, otherTenantId);
    assertThat(insertedLogCount).isEqualTo(1);
  }

  @Test
  @DisplayName("日志引用行锁与归档删除串行化，父实例先删除时拒绝追加日志")
  void shouldSerializeGuardedLogInsertWithInstanceDeletion() throws Exception {
    String tenantId = unique("tenant");
    Long instanceId =
        insertOldTerminalInstance(tenantId, insertJobDefinition(tenantId), "RUNNING", false);
    CountDownLatch instanceLocked = new CountDownLatch(1);
    CountDownLatch allowInstanceDelete = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    String lockJobInstanceSql = """
        select id from batch.job_instance where tenant_id=? and id=? for update
        """;
    String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id=? and id=?
        """;
    String remainingExecutionLogCountSql = """
        select count(*) from batch.job_execution_log
        where tenant_id=? and job_instance_id=?
        """;

    try {
      Future<?> deleteInstance = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            jdbcTemplate.queryForObject(lockJobInstanceSql, Long.class, tenantId, instanceId);
            instanceLocked.countDown();
            awaitLatch(allowInstanceDelete);
            jdbcTemplate.update(deleteJobInstanceSql, tenantId, instanceId);
          }));
      assertThat(instanceLocked.await(5, TimeUnit.SECONDS)).isTrue();

      Future<Integer> insertLog = executor.submit(
          () -> jobExecutionLogMapper.insert(buildExecutionLog(tenantId, instanceId)));
      awaitExecutionLogInsertLockWait();
      allowInstanceDelete.countDown();
      deleteInstance.get(5, TimeUnit.SECONDS);

      assertThatThrownBy(() -> insertLog.get(5, TimeUnit.SECONDS))
          .rootCause()
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("missing");
      assertThat(count("batch.job_instance", instanceId)).isZero();
      Integer remainingLogCount = jdbcTemplate.queryForObject(
          remainingExecutionLogCountSql, Integer.class, tenantId, instanceId);
      assertThat(remainingLogCount).isZero();
    } finally {
      allowInstanceDelete.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  @DisplayName("冷归档表与热表接受相同任务和 Pipeline 类型")
  void shouldKeepArchivedExecutionTypeConstraintsInSyncWithHotTables() {
    String hotConstraint = readConstraintDefinition("batch", "job_task", "ck_job_task_type");
    String coldConstraint =
        readConstraintDefinition("archive", "job_task_archive", "ck_job_task_type");
    String hotPipelineConstraint =
        readConstraintDefinition("batch", "pipeline_instance", "ck_pipeline_instance_type");
    String coldPipelineConstraint = readConstraintDefinition(
        "archive", "pipeline_instance_archive", "ck_pipeline_instance_type");

    assertThat(coldConstraint).isEqualTo(hotConstraint);
    assertThat(coldConstraint).contains("PROCESS", "ATOMIC");
    assertThat(coldPipelineConstraint).isEqualTo(hotPipelineConstraint);
    assertThat(coldPipelineConstraint).contains("PROCESS");
  }

  @Test
  @DisplayName("独立 Workflow 的热 JOB 节点仍引用实例时延后 Job 归档，Workflow 冷归档后再完成 Job 归档")
  void shouldDeferJobArchiveUntilIndependentWorkflowReferenceIsArchived() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long instanceId = insertOldSuccessInstance(tenantId, definitionId);
    String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition(
          tenant_id, workflow_code, workflow_name, calendar_code
        ) values (?, ?, 'Independent archive workflow', ?) returning id
        """;
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        unique("archive-workflow"),
        unique("archive-calendar"));
    String insertTriggerRequestSql = """
        insert into batch.trigger_request(
          tenant_id, request_id, trigger_type, job_code, dedup_key, request_status,
          workflow_definition_id, biz_date
        ) values (?, ?, 'API', null, ?, 'ACCEPTED', ?, current_date - 3)
        returning id
        """;
    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        unique("archive-trigger"),
        unique("archive-trigger-dedup"),
        workflowDefinitionId);
    String insertWorkflowRunSql = """
        insert into batch.workflow_run(
          tenant_id, workflow_definition_id, related_job_instance_id, trigger_request_id,
          workflow_version_snapshot, data_interval_start, data_interval_end, execution_snapshot,
          run_status, finished_at
        ) values (
          ?, ?, null, ?, 1, now() - interval '3 days', now() - interval '2 days',
          '{"nodes":["child-job"]}'::jsonb, 'SUCCESS', now() - interval '3 days'
        )
        returning id
        """;
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql, Long.class, tenantId, workflowDefinitionId, triggerRequestId);
    String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run(
          workflow_run_id, node_code, node_type, node_status, job_instance_id, finished_at
        ) values (?, 'child-job', 'JOB', 'SUCCESS', ?, now() - interval '3 days')
        returning id
        """;
    Long nodeRunId = jdbcTemplate.queryForObject(
        insertWorkflowNodeRunSql, Long.class, workflowRunId, instanceId);

    successInstanceArchiveService.archiveOnce();

    assertThat(count("batch.job_instance", instanceId)).isEqualTo(1);
    assertThat(count("batch.workflow_node_run", nodeRunId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", instanceId)).isZero();

    workflowArchiveService.archiveOnce();
    assertThat(count("batch.workflow_run", workflowRunId)).isZero();
    assertThat(count("batch.workflow_node_run", nodeRunId)).isZero();
    String archivedNodeJobInstanceIdSql = """
        select job_instance_id from archive.workflow_node_run_archive where id = ?
        """;
    Long archivedNodeJobInstanceId =
        jdbcTemplate.queryForObject(archivedNodeJobInstanceIdSql, Long.class, nodeRunId);
    assertThat(archivedNodeJobInstanceId).isEqualTo(instanceId);

    successInstanceArchiveService.archiveOnce();

    assertThat(count("batch.job_instance", instanceId)).isZero();
    assertThat(count("archive.job_instance_archive", instanceId)).isEqualTo(1);
    String retainedNodeJobInstanceIdSql = """
        select job_instance_id from archive.workflow_node_run_archive where id = ?
        """;
    Long retainedNodeJobInstanceId =
        jdbcTemplate.queryForObject(retainedNodeJobInstanceIdSql, Long.class, nodeRunId);
    assertThat(retainedNodeJobInstanceId).isEqualTo(instanceId);
  }

  @Test
  @DisplayName("已发布出站事件归档时先把事件行与投递日志写入冷表,再删除热表行")
  void shouldCopyOutboxRowsToColdTablesBeforeDeletingHotRows() {
    String tenantId = unique("tenant");
    Long outboxId = insertOldPublishedOutbox(tenantId);
    Long deliveryLogId = insertDeliveryLog(tenantId, outboxId);

    OutboxArchiveService.ArchiveBatchResult result = outboxArchiveService.archivePublished();

    assertThat(result.outboxDeleted()).isEqualTo(1);
    assertThat(count("batch.outbox_event", outboxId)).isZero();
    assertThat(count("batch.event_delivery_log", deliveryLogId)).isZero();
    assertThat(count("archive.outbox_event_archive", outboxId)).isEqualTo(1);
    assertThat(count("archive.event_delivery_log_archive", deliveryLogId)).isEqualTo(1);
  }

  @Test
  @DisplayName("成功实例归档时先把实例,分区,任务与步骤整棵运行树写入冷表,再清理热表行")
  void shouldCopySuccessInstanceRuntimeTreeToColdTablesBeforeHotCleanup() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long instanceId = insertOldSuccessInstance(tenantId, definitionId);
    String setExecutionStartSql = """
        update batch.job_instance set execution_started_at = now() - interval '4 days'
        where tenant_id = ? and id = ?
        """;
    int preparedInstances = jdbcTemplate.update(setExecutionStartSql, tenantId, instanceId);
    assertThat(preparedInstances).isEqualTo(1);
    String insertSourceFileSql = """
        insert into batch.file_record(
          tenant_id, file_category, file_name, file_format_type, storage_type,
          storage_path, source_type, file_status
        ) values (?, 'INPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED')
        returning id
        """;
    Long sourceFileId = jdbcTemplate.queryForObject(
        insertSourceFileSql,
        Long.class,
        tenantId,
        unique("partition-source-file"),
        unique("partition-source-file-path"));
    Long partitionId = insertJobPartition(tenantId, instanceId);
    String updatePartitionSourceSql = """
        update batch.job_partition
        set source_file_id = ?, template_code = ?, target_ref = ?
        where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(
        updatePartitionSourceSql,
        sourceFileId,
        "archive-template-v1",
        "biz.archive_source",
        tenantId,
        partitionId);
    Long taskId = insertJobTask(tenantId, instanceId, partitionId);
    UUID stagingGenerationId = UUID.randomUUID();
    String stagingBatchKey = "process-" + taskId;
    String updateTaskStagingIdentitySql = """
        update batch.job_task
        set process_staging_generation_id = ?, process_staging_batch_key = ?
        where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(
        updateTaskStagingIdentitySql, stagingGenerationId, stagingBatchKey, tenantId, taskId);
    Long stepId = insertJobStepInstance(tenantId, instanceId, partitionId, taskId);
    String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition(
          tenant_id, workflow_code, workflow_name, calendar_code
        ) values (?, ?, 'Archive workflow', ?) returning id
        """;
    Long workflowDefinitionId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        unique("archive-workflow"),
        unique("archive-calendar"));
    String insertTriggerRequestSql = """
        insert into batch.trigger_request(
          tenant_id, request_id, trigger_type, job_code, dedup_key, request_status,
          workflow_definition_id, biz_date
        ) values (?, ?, 'API', null, ?, 'ACCEPTED', ?, current_date)
        returning id
        """;
    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        tenantId,
        unique("archive-trigger"),
        unique("archive-trigger-dedup"),
        workflowDefinitionId);
    String updateTaskExternalOutcomeResolutionSql = """
        update batch.job_task
        set external_outcome_resolution_version = version,
            external_outcome_resolution_trigger_request_id = ?,
            external_outcome_resolution_consumed_at = current_timestamp
        where tenant_id = ? and id = ?
        """;
    jdbcTemplate.update(updateTaskExternalOutcomeResolutionSql, triggerRequestId, tenantId, taskId);
    String insertWorkflowRunSql = """
        insert into batch.workflow_run(
          tenant_id, workflow_definition_id, related_job_instance_id, trigger_request_id,
          workflow_version_snapshot, data_interval_start, data_interval_end, execution_snapshot,
          run_status, finished_at
        ) values (
          ?, ?, ?, ?, 7, now() - interval '1 day', now(), '{"nodes":["child-job"]}'::jsonb,
          'SUCCESS', now() - interval '2 days'
        )
        returning id
        """;
    Long workflowRunId = jdbcTemplate.queryForObject(
        insertWorkflowRunSql,
        Long.class,
        tenantId,
        workflowDefinitionId,
        instanceId,
        triggerRequestId);
    String insertWorkflowNodeRunSql = """
        insert into batch.workflow_node_run(
          workflow_run_id, node_code, node_type, node_status, job_instance_id, finished_at
        ) values (?, 'child-job', 'JOB', 'SUCCESS', ?, now() - interval '2 days')
        returning id
        """;
    Long workflowNodeRunId = jdbcTemplate.queryForObject(
        insertWorkflowNodeRunSql, Long.class, workflowRunId, instanceId);
    String insertStepFileSql = """
        insert into batch.file_record(
          tenant_id, file_category, file_name, file_format_type, storage_type,
          storage_path, source_type, file_status
        ) values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED')
        returning id
        """;
    Long stepFileId = jdbcTemplate.queryForObject(
        insertStepFileSql, Long.class, tenantId, unique("step-file"), unique("step-file-path"));
    String updateStepFileReferenceSql = """
        update batch.job_step_instance set related_file_id = ? where id = ?
        """;
    jdbcTemplate.update(updateStepFileReferenceSql, stepFileId, stepId);
    String pipelineJobCode = unique("archive-pipeline-job");
    String insertPipelineInstanceSql = """
        insert into batch.pipeline_instance(
          tenant_id, system_pipeline_code, system_pipeline_version,
          job_code, pipeline_type, file_id, related_job_instance_id, run_status
        ) values (?, 'DISPATCH', 1, ?, 'DISPATCH', ?, ?, 'SUCCESS')
        returning id
        """;
    Long pipelineInstanceId = jdbcTemplate.queryForObject(
        insertPipelineInstanceSql, Long.class, tenantId, pipelineJobCode, stepFileId, instanceId);
    String insertPipelineStepRunSql = """
        insert into batch.pipeline_step_run(
          pipeline_instance_id, step_code, stage_code, step_status, output_summary, finished_at
        ) values (?, 'send-file', 'DISPATCH', 'SUCCESS', '{"sent":true}'::jsonb, now() - interval '2 days')
        returning id
        """;
    Long pipelineStepRunId =
        jdbcTemplate.queryForObject(insertPipelineStepRunSql, Long.class, pipelineInstanceId);
    String insertFileDispatchRecordSql = """
        insert into batch.file_dispatch_record(
          tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status
        ) values (?, ?, ?, 'archive-channel', 'SENT')
        returning id
        """;
    Long fileDispatchRecordId = jdbcTemplate.queryForObject(
        insertFileDispatchRecordSql, Long.class, tenantId, stepFileId, pipelineInstanceId);
    String insertExecutionLogSql = """
        insert into batch.job_execution_log(
          tenant_id, job_instance_id, job_partition_id, log_level, log_type, message
        ) values (?, ?, ?, 'INFO', 'SYSTEM', 'archive fixture')
        returning id
        """;
    Long executionLogId = jdbcTemplate.queryForObject(
        insertExecutionLogSql, Long.class, tenantId, instanceId, partitionId);
    String insertCompensationFileSql = """
        insert into batch.file_record(
          tenant_id, file_category, file_name, file_format_type, storage_type,
          storage_path, source_type, file_status
        ) values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED')
        returning id
        """;
    Long compensationFileId = jdbcTemplate.queryForObject(
        insertCompensationFileSql,
        Long.class,
        tenantId,
        unique("compensation-file"),
        unique("compensation-file-path"));
    String insertCompensationCommandSql = """
        insert into batch.compensation_command(
          tenant_id, command_no, compensation_type, related_job_instance_id,
          related_file_id, command_status
        ) values (?, ?, 'FILE', ?, ?, 'SUCCESS')
        returning id
        """;
    Long compensationCommandId = jdbcTemplate.queryForObject(
        insertCompensationCommandSql,
        Long.class,
        tenantId,
        unique("archive-compensation"),
        instanceId,
        compensationFileId);
    String insertMonitoringClaimSql = """
        insert into batch.job_monitoring_alert_claim
            (tenant_id, job_instance_id, violation_type)
        values (?, ?, 'COMPLETED_LATE')
        """;
    jdbcTemplate.update(insertMonitoringClaimSql, tenantId, instanceId);

    String jobInstanceSnapshot = rowSnapshot("batch", "job_instance", instanceId);
    String partitionSnapshot = rowSnapshot("batch", "job_partition", partitionId);
    String taskSnapshot = rowSnapshot("batch", "job_task", taskId);
    String stepSnapshot = rowSnapshot("batch", "job_step_instance", stepId);
    String workflowRunSnapshot = rowSnapshot("batch", "workflow_run", workflowRunId);
    String workflowNodeRunSnapshot = rowSnapshot("batch", "workflow_node_run", workflowNodeRunId);
    String pipelineInstanceSnapshot = rowSnapshot("batch", "pipeline_instance", pipelineInstanceId);
    String pipelineStepRunSnapshot = rowSnapshot("batch", "pipeline_step_run", pipelineStepRunId);
    String fileDispatchRecordSnapshot =
        rowSnapshot("batch", "file_dispatch_record", fileDispatchRecordId);
    String executionLogSnapshot = rowSnapshot("batch", "job_execution_log", executionLogId);
    String stepFileSnapshot = rowSnapshot("batch", "file_record", stepFileId);
    String compensationCommandSnapshot =
        rowSnapshot("batch", "compensation_command", compensationCommandId);
    String compensationFileSnapshot = rowSnapshot("batch", "file_record", compensationFileId);

    List<ArchiveConflictProbe> conflictProbes = List.of(
        new ArchiveConflictProbe(
            "update archive.job_partition_archive set business_key = 'conflict' where id = ?",
            partitionId),
        new ArchiveConflictProbe(
            "update archive.job_step_instance_archive set error_message = 'conflict' where id = ?",
            stepId),
        new ArchiveConflictProbe(
            "update archive.pipeline_instance_archive set trace_id = 'conflict' where id = ?",
            pipelineInstanceId),
        new ArchiveConflictProbe(
            "update archive.pipeline_step_run_archive set error_message = 'conflict' where id = ?",
            pipelineStepRunId),
        new ArchiveConflictProbe(
            "update archive.file_dispatch_record_archive set error_message = 'conflict' where id = ?",
            fileDispatchRecordId),
        new ArchiveConflictProbe(
            "update archive.workflow_run_archive set trace_id = 'conflict' where id = ?",
            workflowRunId),
        new ArchiveConflictProbe(
            "update archive.workflow_node_run_archive set error_message = 'conflict' where id = ?",
            workflowNodeRunId),
        new ArchiveConflictProbe(
            "update archive.job_execution_log_archive set message = 'conflict' where id = ?",
            executionLogId),
        new ArchiveConflictProbe(
            "update archive.compensation_command_archive set error_message = 'conflict' where id = ?",
            compensationCommandId));
    assertRuntimeTreeConflictDetection(instanceId, conflictProbes);
    List<ArchiveConflictProbe> lockProbes = new ArrayList<>(conflictProbes);
    lockProbes.add(new ArchiveConflictProbe(
        "update archive.job_task_archive set error_message = 'conflict' where id = ?", taskId));
    assertRuntimeTreeRowLocks(instanceId, lockProbes);

    SuccessInstanceArchiveService.ArchiveBatchResult result =
        successInstanceArchiveService.archiveOnce();

    // 本地集成数据库可能保留上一次运行留下的其他过期 fixture。
    // 断言当前测试实例的契约，不要让结果依赖全局状态。
    assertThat(result.instancesDeleted()).isGreaterThanOrEqualTo(1);
    assertThat(count("batch.job_instance", instanceId)).isZero();
    assertThat(count("batch.job_partition", partitionId)).isZero();
    assertThat(count("batch.job_task", taskId)).isZero();
    assertThat(count("batch.job_step_instance", stepId)).isZero();
    assertThat(count("batch.workflow_run", workflowRunId)).isZero();
    assertThat(count("batch.workflow_node_run", workflowNodeRunId)).isZero();
    assertThat(count("batch.pipeline_instance", pipelineInstanceId)).isZero();
    assertThat(count("batch.pipeline_step_run", pipelineStepRunId)).isZero();
    assertThat(count("batch.file_dispatch_record", fileDispatchRecordId)).isZero();
    assertThat(count("batch.job_execution_log", executionLogId)).isZero();
    assertThat(count("batch.file_record", stepFileId)).isEqualTo(1);
    String remainingMonitoringClaimCountSql = """
        select count(*) from batch.job_monitoring_alert_claim where job_instance_id = ?
        """;
    Integer remainingMonitoringClaimCount =
        jdbcTemplate.queryForObject(remainingMonitoringClaimCountSql, Integer.class, instanceId);
    assertThat(remainingMonitoringClaimCount).isZero();
    assertThat(count("archive.job_instance_archive", instanceId)).isEqualTo(1);
    assertThat(count("archive.job_partition_archive", partitionId)).isEqualTo(1);
    String archivedSourceFileIdSql = """
        select source_file_id from archive.job_partition_archive where id = ?
        """;
    Long archivedSourceFileId =
        jdbcTemplate.queryForObject(archivedSourceFileIdSql, Long.class, partitionId);
    assertThat(archivedSourceFileId).isEqualTo(sourceFileId);
    assertThat(count("archive.job_task_archive", taskId)).isEqualTo(1);
    String archivedStagingGenerationIdSql = """
        select process_staging_generation_id from archive.job_task_archive where id = ?
        """;
    UUID archivedStagingGenerationId =
        jdbcTemplate.queryForObject(archivedStagingGenerationIdSql, UUID.class, taskId);
    assertThat(archivedStagingGenerationId).isEqualTo(stagingGenerationId);
    String archivedStagingBatchKeySql = """
        select process_staging_batch_key from archive.job_task_archive where id = ?
        """;
    String archivedStagingBatchKey =
        jdbcTemplate.queryForObject(archivedStagingBatchKeySql, String.class, taskId);
    assertThat(archivedStagingBatchKey).isEqualTo(stagingBatchKey);
    assertThat(count("archive.job_step_instance_archive", stepId)).isEqualTo(1);
    assertThat(count("archive.workflow_run_archive", workflowRunId)).isEqualTo(1);
    String archivedTriggerRequestIdSql = """
        select trigger_request_id from archive.workflow_run_archive where id = ?
        """;
    Long archivedTriggerRequestId =
        jdbcTemplate.queryForObject(archivedTriggerRequestIdSql, Long.class, workflowRunId);
    assertThat(archivedTriggerRequestId).isEqualTo(triggerRequestId);
    String archivedWorkflowVersionSql = """
        select workflow_version_snapshot from archive.workflow_run_archive where id = ?
        """;
    Integer archivedWorkflowVersion =
        jdbcTemplate.queryForObject(archivedWorkflowVersionSql, Integer.class, workflowRunId);
    assertThat(archivedWorkflowVersion).isEqualTo(7);
    String hasArchivedDataIntervalSql = """
        select data_interval_start is not null and data_interval_end is not null
        from archive.workflow_run_archive where id = ?
        """;
    Boolean hasArchivedDataInterval =
        jdbcTemplate.queryForObject(hasArchivedDataIntervalSql, Boolean.class, workflowRunId);
    assertThat(hasArchivedDataInterval).isTrue();
    String archivedFirstNodeCodeSql = """
        select execution_snapshot -> 'nodes' ->> 0
        from archive.workflow_run_archive where id = ?
        """;
    String archivedFirstNodeCode =
        jdbcTemplate.queryForObject(archivedFirstNodeCodeSql, String.class, workflowRunId);
    assertThat(archivedFirstNodeCode).isEqualTo("child-job");
    assertThat(count("archive.workflow_node_run_archive", workflowNodeRunId)).isEqualTo(1);
    assertThat(count("archive.pipeline_instance_archive", pipelineInstanceId)).isEqualTo(1);
    assertThat(count("archive.pipeline_step_run_archive", pipelineStepRunId)).isEqualTo(1);
    assertThat(count("archive.file_dispatch_record_archive", fileDispatchRecordId))
        .isEqualTo(1);
    assertThat(count("archive.job_execution_log_archive", executionLogId)).isEqualTo(1);
    String archivedWorkflowNodeJobInstanceIdSql = """
        select job_instance_id from archive.workflow_node_run_archive where id = ?
        """;
    Long archivedWorkflowNodeJobInstanceId = jdbcTemplate.queryForObject(
        archivedWorkflowNodeJobInstanceIdSql, Long.class, workflowNodeRunId);
    assertThat(archivedWorkflowNodeJobInstanceId).isEqualTo(instanceId);
    assertThat(count("archive.file_record_archive", stepFileId)).isEqualTo(1);
    assertThat(count("archive.compensation_command_archive", compensationCommandId))
        .isEqualTo(1);
    assertThat(count("archive.file_record_archive", compensationFileId)).isEqualTo(1);
    assertThat(rowSnapshot("archive", "job_instance_archive", instanceId))
        .isEqualTo(jobInstanceSnapshot);
    assertThat(rowSnapshot("archive", "job_partition_archive", partitionId))
        .isEqualTo(partitionSnapshot);
    assertThat(rowSnapshot("archive", "job_task_archive", taskId)).isEqualTo(taskSnapshot);
    assertThat(rowSnapshot("archive", "job_step_instance_archive", stepId)).isEqualTo(stepSnapshot);
    assertThat(rowSnapshot("archive", "workflow_run_archive", workflowRunId))
        .isEqualTo(workflowRunSnapshot);
    assertThat(rowSnapshot("archive", "workflow_node_run_archive", workflowNodeRunId))
        .isEqualTo(workflowNodeRunSnapshot);
    assertThat(rowSnapshot("archive", "pipeline_instance_archive", pipelineInstanceId))
        .isEqualTo(pipelineInstanceSnapshot);
    assertThat(rowSnapshot("archive", "pipeline_step_run_archive", pipelineStepRunId))
        .isEqualTo(pipelineStepRunSnapshot);
    assertThat(rowSnapshot("archive", "file_dispatch_record_archive", fileDispatchRecordId))
        .isEqualTo(fileDispatchRecordSnapshot);
    assertThat(rowSnapshot("archive", "job_execution_log_archive", executionLogId))
        .isEqualTo(executionLogSnapshot);
    assertThat(rowSnapshot("archive", "file_record_archive", stepFileId))
        .isEqualTo(stepFileSnapshot);
    assertThat(rowSnapshot("archive", "compensation_command_archive", compensationCommandId))
        .isEqualTo(compensationCommandSnapshot);
    assertThat(rowSnapshot("archive", "file_record_archive", compensationFileId))
        .isEqualTo(compensationFileSnapshot);
  }

  @Test
  @DisplayName("成功实例归档遇到跨租户实例日志时保留热数据并拒绝归档")
  void shouldRetainHotTree_whenLogReferencesForeignTenantInstance() {
    String instanceTenant = unique("instance-tenant");
    String logTenant = unique("log-tenant");
    Long definitionId = insertJobDefinition(instanceTenant);
    Long instanceId = insertOldSuccessInstance(instanceTenant, definitionId);
    insertJobPartition(instanceTenant, instanceId);
    Long logId = insertCrossTenantInstanceExecutionLog(logTenant, instanceId);

    successInstanceArchiveService.archiveOnce();
    successInstanceArchiveService.archiveOnce();

    // 归档扫描覆盖整个租户；应断言此损坏的根记录，而非无关夹具。
    assertThat(count("batch.job_instance", instanceId)).isEqualTo(1);
    assertThat(count("batch.job_execution_log", logId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", instanceId)).isZero();
    assertThat(count("archive.job_execution_log_archive", logId)).isZero();
    assertThat(countArchiveIntegrityAlerts(instanceTenant)).isEqualTo(1);
    assertThat(countArchiveIntegrityAlerts(logTenant)).isZero();
    assertThat(archiveIntegrityAlertOccurrences(instanceTenant)).isEqualTo(2);
    assertThat(archiveIntegrityAlertSampleInstanceId(instanceTenant)).isEqualTo(instanceId);
  }

  @Test
  @DisplayName("归档批次回滚时仍保留独立提交的完整性告警")
  void shouldKeepIntegrityAlertWhenArchiveBatchRollsBack() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long invalidInstanceId = insertOldSuccessInstance(tenantId, definitionId);
    String wrongTenant = unique("wrong-tenant");
    Long invalidLogId = insertCrossTenantInstanceExecutionLog(wrongTenant, invalidInstanceId);
    Long archivableInstanceId = insertOldSuccessInstance(tenantId, definitionId);
    String triggerName = unique("fail_archive_insert").replace('-', '_');
    String functionName = triggerName + "_fn";

    String createFailureFunctionSql = """
        create function archive.%s() returns trigger language plpgsql as $$
        begin
          raise exception 'injected archive batch failure';
        end
        $$
        """.formatted(functionName);
    jdbcTemplate.execute(createFailureFunctionSql);
    String createFailureTriggerSql = """
        create trigger %s before insert on archive.job_instance_archive
        for each row execute function archive.%s()
        """.formatted(triggerName, functionName);
    jdbcTemplate.execute(createFailureTriggerSql);
    try {
      assertThatThrownBy(successInstanceArchiveService::archiveOnce)
          .hasMessageContaining("injected archive batch failure");
    } finally {
      String dropFailureTriggerSql = """
          drop trigger if exists %s on archive.job_instance_archive
          """.formatted(triggerName);
      jdbcTemplate.execute(dropFailureTriggerSql);
      String dropFailureFunctionSql = """
          drop function if exists archive.%s()
          """.formatted(functionName);
      jdbcTemplate.execute(dropFailureFunctionSql);
      String markArchiveFixtureRunningSql = """
          update batch.job_instance
          set instance_status = 'RUNNING', finished_at = null where id = ?
          """;
      jdbcTemplate.update(markArchiveFixtureRunningSql, archivableInstanceId);
    }

    assertThat(countArchiveIntegrityAlerts(tenantId)).isEqualTo(1);
    assertThat(archiveIntegrityAlertSampleInstanceId(tenantId)).isEqualTo(invalidInstanceId);
    assertThat(count("batch.job_instance", invalidInstanceId)).isEqualTo(1);
    assertThat(count("batch.job_execution_log", invalidLogId)).isEqualTo(1);
    assertThat(count("batch.job_instance", archivableInstanceId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", archivableInstanceId)).isZero();
  }

  @Test
  @DisplayName("运行树错绑由数据库拒绝, 归档仍拦截跨租户实例日志")
  void shouldRejectStepCorruptionBeforeArchiving_whenLogHasCrossTreeReference(
      CapturedOutput output) {
    String tenantId = unique("tenant");

    Long stepOwnerId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long stepOwnerPartitionId = insertJobPartition(tenantId, stepOwnerId);
    Long foreignTaskOwnerId =
        insertOldTerminalInstance(tenantId, insertJobDefinition(tenantId), "RUNNING", false);
    Long foreignTaskOwnerPartitionId = insertJobPartition(tenantId, foreignTaskOwnerId);
    Long foreignTaskId = insertJobTask(tenantId, foreignTaskOwnerId, foreignTaskOwnerPartitionId);
    assertThatThrownBy(
            () -> insertJobStepInstance(tenantId, stepOwnerId, stepOwnerPartitionId, foreignTaskId))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));

    Long stepPartitionOwnerId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    Long stepPartitionOwnerPartitionId = insertJobPartition(tenantId, stepPartitionOwnerId);
    Long stepPartitionForeignOwnerId =
        insertOldTerminalInstance(tenantId, insertJobDefinition(tenantId), "RUNNING", false);
    Long stepPartitionForeignId = insertJobPartition(tenantId, stepPartitionForeignOwnerId);
    Long stepPartitionOwnerTaskId =
        insertJobTask(tenantId, stepPartitionOwnerId, stepPartitionOwnerPartitionId);
    assertThatThrownBy(() -> insertJobStepInstance(
            tenantId, stepPartitionOwnerId, stepPartitionForeignId, stepPartitionOwnerTaskId))
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, failure -> assertThat(failure.getSQLState()).isEqualTo("23503"));

    Long logOwnerId = insertOldSuccessInstance(tenantId, insertJobDefinition(tenantId));
    String foreignLogTenant = unique("foreign-log-tenant");
    final String insertMismatchedExecutionLogSql = """
    insert into batch.job_execution_log(
    tenant_id, job_instance_id, job_partition_id, log_level, log_type, message
    ) values (?, ?, null, 'INFO', 'AUDIT', 'cross-tenant instance reference') returning id
    """;
    Long mismatchedInstanceLogId = jdbcTemplate.queryForObject(
        insertMismatchedExecutionLogSql, Long.class, foreignLogTenant, logOwnerId);

    successInstanceArchiveService.archiveOnce();

    assertThat(output).contains("integrity issue persisted");
    assertThat(count("batch.job_instance", stepOwnerId)).isZero();
    assertThat(count("batch.job_instance", stepPartitionOwnerId)).isZero();
    assertThat(count("batch.job_instance", logOwnerId)).isEqualTo(1);
    assertThat(count("batch.job_execution_log", mismatchedInstanceLogId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", stepOwnerId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", stepPartitionOwnerId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", logOwnerId)).isZero();
  }

  @Test
  @DisplayName("成功实例归档遇到另一租户 rerun 子实例时不清理其 lineage 引用")
  void shouldNotArchiveSuccessInstanceReferencedByAnotherTenantsRerun() {
    String parentTenant = unique("parent-tenant");
    String childTenant = unique("child-tenant");
    Long parentId = insertOldSuccessInstance(parentTenant, insertJobDefinition(parentTenant));
    Long childId =
        insertOldTerminalInstance(childTenant, insertJobDefinition(childTenant), "RUNNING", false);
    String updateChildParentInstanceSql = """
        update batch.job_instance set parent_instance_id = ? where id = ?
        """;
    jdbcTemplate.update(updateChildParentInstanceSql, parentId, childId);

    String foreignParentTenant = unique("foreign-parent-tenant");
    String rerunTenant = unique("rerun-tenant");
    Long foreignParentId = insertOldTerminalInstance(
        foreignParentTenant, insertJobDefinition(foreignParentTenant), "RUNNING", false);
    Long rerunId = insertOldSuccessInstance(rerunTenant, insertJobDefinition(rerunTenant));
    String updateRerunParentInstanceSql = """
        update batch.job_instance set parent_instance_id = ? where id = ?
        """;
    jdbcTemplate.update(updateRerunParentInstanceSql, foreignParentId, rerunId);

    successInstanceArchiveService.archiveOnce();

    assertThat(count("batch.job_instance", parentId)).isEqualTo(1);
    assertThat(count("batch.job_instance", childId)).isEqualTo(1);
    assertThat(count("batch.job_instance", rerunId)).isEqualTo(1);
    String childParentInstanceIdSql = """
        select parent_instance_id from batch.job_instance where id = ?
        """;
    Long childParentInstanceId =
        jdbcTemplate.queryForObject(childParentInstanceIdSql, Long.class, childId);
    assertThat(childParentInstanceId).isEqualTo(parentId);
    String rerunParentInstanceIdSql = """
        select parent_instance_id from batch.job_instance where id = ?
        """;
    Long rerunParentInstanceId =
        jdbcTemplate.queryForObject(rerunParentInstanceIdSql, Long.class, rerunId);
    assertThat(rerunParentInstanceId).isEqualTo(foreignParentId);
    assertThat(count("archive.job_instance_archive", parentId)).isZero();
    assertThat(count("archive.job_instance_archive", rerunId)).isZero();
  }

  @Test
  @DisplayName("成功实例归档遇到跨租户文件或定义引用时保留运行树")
  void shouldNotArchiveSuccessInstanceWithPipelineFileFromAnotherTenant() {
    String instanceTenant = unique("instance-tenant");
    String fileTenant = unique("file-tenant");
    Long definitionId = insertJobDefinition(instanceTenant);
    Long instanceId = insertOldSuccessInstance(instanceTenant, definitionId);
    String insertForeignFileSql = """
        insert into batch.file_record(
          tenant_id, file_category, file_name, file_format_type, storage_type,
          storage_path, source_type, file_status
        ) values (?, 'INPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED')
        returning id
        """;
    Long foreignFileId = jdbcTemplate.queryForObject(
        insertForeignFileSql,
        Long.class,
        fileTenant,
        unique("foreign-file"),
        unique("foreign-path"));
    String insertPipelineInstanceSql = """
        insert into batch.pipeline_instance(
          tenant_id, job_code, pipeline_type, system_pipeline_code,
          file_id, related_job_instance_id, run_status
        ) values (?, ?, 'DISPATCH', 'DISPATCH', ?, ?, 'SUCCESS')
        returning id
        """;
    Long pipelineInstanceId = jdbcTemplate.queryForObject(
        insertPipelineInstanceSql,
        Long.class,
        instanceTenant,
        unique("archive-job"),
        foreignFileId,
        instanceId);

    Long dispatchInstanceId = insertOldSuccessInstance(instanceTenant, definitionId);
    String insertDispatchPipelineInstanceSql = """
        insert into batch.pipeline_instance(
          tenant_id, job_code, pipeline_type, system_pipeline_code,
          related_job_instance_id, run_status
        ) values (?, ?, 'DISPATCH', 'DISPATCH', ?, 'SUCCESS')
        returning id
        """;
    Long dispatchPipelineInstanceId = jdbcTemplate.queryForObject(
        insertDispatchPipelineInstanceSql,
        Long.class,
        instanceTenant,
        unique("archive-dispatch-job"),
        dispatchInstanceId);
    String insertDispatchRecordSql = """
        insert into batch.file_dispatch_record(
          tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_status
        ) values (?, ?, ?, 'archive-tenant-guard', 'SENT')
        returning id
        """;
    Long dispatchRecordId = jdbcTemplate.queryForObject(
        insertDispatchRecordSql,
        Long.class,
        instanceTenant,
        foreignFileId,
        dispatchPipelineInstanceId);

    Long stepFileMismatchInstanceId = insertOldSuccessInstance(instanceTenant, definitionId);
    Long stepFileMismatchPartitionId =
        insertJobPartition(instanceTenant, stepFileMismatchInstanceId);
    Long stepFileMismatchTaskId =
        insertJobTask(instanceTenant, stepFileMismatchInstanceId, stepFileMismatchPartitionId);
    Long stepFileMismatchStepId = insertJobStepInstance(
        instanceTenant,
        stepFileMismatchInstanceId,
        stepFileMismatchPartitionId,
        stepFileMismatchTaskId);
    String updateMismatchedStepFileSql = """
        update batch.job_step_instance set related_file_id = ? where id = ?
        """;
    jdbcTemplate.update(updateMismatchedStepFileSql, foreignFileId, stepFileMismatchStepId);

    Long instanceFileMismatchInstanceId = insertOldSuccessInstance(instanceTenant, definitionId);
    String updateForeignInstanceFileSql = """
        update batch.job_instance set related_file_id = ? where id = ?
        """;
    jdbcTemplate.update(
        updateForeignInstanceFileSql, foreignFileId, instanceFileMismatchInstanceId);

    Long compensationFileMismatchInstanceId =
        insertOldSuccessInstance(instanceTenant, definitionId);
    String insertCompensationFileMismatchCommandSql = """
        insert into batch.compensation_command(
          tenant_id, command_no, compensation_type, related_job_instance_id,
          related_file_id, command_status
        ) values (?, ?, 'FILE', ?, ?, 'SUCCESS')
        returning id
        """;
    Long compensationFileMismatchCommandId = jdbcTemplate.queryForObject(
        insertCompensationFileMismatchCommandSql,
        Long.class,
        instanceTenant,
        unique("cross-tenant-compensation"),
        compensationFileMismatchInstanceId,
        foreignFileId);

    successInstanceArchiveService.archiveOnce();

    assertThat(count("batch.job_instance", instanceId)).isEqualTo(1);
    assertThat(count("batch.job_instance", dispatchInstanceId)).isEqualTo(1);
    assertThat(count("batch.job_instance", stepFileMismatchInstanceId)).isEqualTo(1);
    assertThat(count("batch.job_step_instance", stepFileMismatchStepId)).isEqualTo(1);
    assertThat(count("batch.job_instance", instanceFileMismatchInstanceId)).isEqualTo(1);
    assertThat(count("batch.job_instance", compensationFileMismatchInstanceId)).isEqualTo(1);
    assertThat(count("batch.compensation_command", compensationFileMismatchCommandId))
        .isEqualTo(1);
    assertThat(count("batch.pipeline_instance", pipelineInstanceId)).isEqualTo(1);
    assertThat(count("batch.pipeline_instance", dispatchPipelineInstanceId)).isEqualTo(1);
    assertThat(count("batch.file_dispatch_record", dispatchRecordId)).isEqualTo(1);
    assertThat(count("batch.file_record", foreignFileId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", instanceId)).isZero();
    assertThat(count("archive.job_instance_archive", dispatchInstanceId)).isZero();
    assertThat(count("archive.job_instance_archive", stepFileMismatchInstanceId))
        .isZero();
    assertThat(count("archive.job_instance_archive", instanceFileMismatchInstanceId))
        .isZero();
    assertThat(count("archive.job_instance_archive", compensationFileMismatchInstanceId))
        .isZero();
    assertThat(count("archive.compensation_command_archive", compensationFileMismatchCommandId))
        .isZero();
    assertThat(count("archive.file_dispatch_record_archive", dispatchRecordId)).isZero();
    assertThat(count("archive.file_record_archive", foreignFileId)).isZero();
  }

  @Test
  @DisplayName("成功实例归档同时覆盖试运行成功与试运行失败的终态实例")
  void shouldArchiveDryRunTerminalStatesAlongWithSuccessInstances() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long successId = insertOldTerminalInstance(tenantId, definitionId, "SUCCESS_DRY_RUN", true);
    Long failedId = insertOldTerminalInstance(tenantId, definitionId, "FAILED_DRY_RUN", true);

    successInstanceArchiveService.archiveOnce();

    assertThat(count("batch.job_instance", successId)).isZero();
    assertThat(count("batch.job_instance", failedId)).isZero();
    assertThat(count("archive.job_instance_archive", successId)).isEqualTo(1);
    assertThat(count("archive.job_instance_archive", failedId)).isEqualTo(1);
  }

  @Test
  @DisplayName("被取代的结果版本先标记归档并保留归档副本,热表行待宽限期结束后再清理")
  void shouldArchiveSupersededResultVersionsBeforeHotCleanupAndKeepReferencedRows() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long instanceId = insertOldSuccessInstance(tenantId, definitionId);
    Long resultVersionId = insertOldSupersededResultVersion(tenantId, instanceId, "rv-main");

    int archived = resultVersionRetentionScheduler.demoteSupersededBatch(Instant.now());

    assertThat(archived).isEqualTo(1);
    assertThat(count("batch.result_version", resultVersionId)).isEqualTo(1);
    assertThat(status("batch.result_version", resultVersionId)).isEqualTo("ARCHIVED");
    assertThat(count("archive.result_version_archive", resultVersionId)).isEqualTo(1);
    assertThat(payload("archive.result_version_archive", resultVersionId)).contains("rv-main");

    String ageResultVersionSql = """
        update batch.result_version set updated_at = now() - interval '2 days' where id = ?
        """;
    jdbcTemplate.update(ageResultVersionSql, resultVersionId);
    int deleted = resultVersionRetentionScheduler.purgeArchivedBatch(Instant.now());

    assertThat(deleted).isEqualTo(1);
    assertThat(count("batch.result_version", resultVersionId)).isZero();
    assertThat(count("archive.result_version_archive", resultVersionId)).isEqualTo(1);
  }

  @Test
  @DisplayName("已归档结果版本仍被读模型分区引用时跳过清理,热表行保持不变")
  void shouldNotPurgeArchivedResultVersionsStillReferencedByReadModel() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long instanceId = insertOldSuccessInstance(tenantId, definitionId);
    Long resultVersionId = insertOldSupersededResultVersion(tenantId, instanceId, "rv-referenced");
    String archiveResultVersionSql = """
        update batch.result_version
        set status = 'ARCHIVED', updated_at = now() - interval '2 days' where id = ?
        """;
    jdbcTemplate.update(archiveResultVersionSql, resultVersionId);
    String insertDataAssetSql = """
        insert into batch.data_asset(tenant_id, asset_code, asset_type)
        values (?, ?, 'JOB') returning id
        """;
    Long assetId =
        jdbcTemplate.queryForObject(insertDataAssetSql, Long.class, tenantId, unique("asset"));
    String insertAssetPartitionSql = """
        insert into batch.asset_partition(
          tenant_id, asset_id, asset_code, partition_key, biz_date, freshness_status,
          result_version_id, business_key
        ) values (?, ?, ?, '2026-09-07', current_date, 'EFFECTIVE', ?, ?)
        """;
    jdbcTemplate.update(
        insertAssetPartitionSql,
        tenantId,
        assetId,
        unique("asset-code"),
        resultVersionId,
        unique("business-key"));

    int deleted = resultVersionRetentionScheduler.purgeArchivedBatch(Instant.now());

    assertThat(deleted).isZero();
    assertThat(count("batch.result_version", resultVersionId)).isEqualTo(1);
  }

  @Test
  @DisplayName("试运行结果版本先移入归档表并删除热表行,归档记录保留试运行状态与负载")
  void shouldArchiveDryRunResultVersionsBeforeDeletingHotRow() {
    String tenantId = unique("tenant");
    Long definitionId = insertJobDefinition(tenantId);
    Long instanceId = insertOldSuccessInstance(tenantId, definitionId);
    Long resultVersionId = insertOldDryRunResultVersion(tenantId, instanceId, "dry-run-rv");

    int archived = resultVersionRetentionScheduler.archiveDryRunBatch(Instant.now());

    assertThat(archived).isEqualTo(1);
    assertThat(count("batch.result_version", resultVersionId)).isZero();
    assertThat(count("archive.result_version_archive", resultVersionId)).isEqualTo(1);
    assertThat(status("archive.result_version_archive", resultVersionId)).isEqualTo("DRY_RUN");
    assertThat(payload("archive.result_version_archive", resultVersionId)).contains("dry-run-rv");
  }

  private Long insertOldSupersededResultVersion(String tenantId, Long instanceId, String marker) {
    String insertOldSupersededResultVersionSql = """
        insert into batch.result_version(
          tenant_id, business_key, version_no, job_instance_id, status,
          deactivated_at, payload_storage, payload_json, generated_at, created_at, updated_at
        ) values (?, ?, 1, ?, 'SUPERSEDED', now() - interval '2 days', 'INLINE_JSON', ?::jsonb,
                  now() - interval '2 days', now() - interval '2 days', now() - interval '2 days')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertOldSupersededResultVersionSql,
        Long.class,
        tenantId,
        unique("business-key"),
        instanceId,
        "{\"marker\":\"" + marker + "\"}");
  }

  private Long insertOldDryRunResultVersion(String tenantId, Long instanceId, String marker) {
    String insertOldDryRunResultVersionSql = """
        insert into batch.result_version(
          tenant_id, business_key, version_no, job_instance_id, status,
          payload_storage, payload_json, generated_at, created_at, updated_at
        ) values (?, ?, 1, ?, 'DRY_RUN', 'INLINE_JSON', ?::jsonb,
                  now() - interval '2 days', now() - interval '2 days', now() - interval '2 days')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertOldDryRunResultVersionSql,
        Long.class,
        tenantId,
        unique("business-key"),
        instanceId,
        "{\"marker\":\"" + marker + "\"}");
  }

  private String status(String table, Long id) {
    String statusSql = """
        select status from %s where id = ?
        """.formatted(table);
    return jdbcTemplate.queryForObject(statusSql, String.class, id);
  }

  private String payload(String table, Long id) {
    String payloadSql = """
        select payload_json::text from %s where id = ?
        """.formatted(table);
    return jdbcTemplate.queryForObject(payloadSql, String.class, id);
  }

  private Long insertOldPublishedOutbox(String tenantId) {
    String insertOldPublishedOutboxSql = """
        insert into batch.outbox_event(
          tenant_id, aggregate_type, aggregate_id, event_type, event_key, payload_json,
          publish_status, publish_attempt, trace_id, created_at, updated_at
        ) values (?, 'JOB_PARTITION', 1, 'IMPORT', ?, '{}'::jsonb, 'PUBLISHED', 1, ?, now() - interval '3 days',\s\
        now() - interval '3 days')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertOldPublishedOutboxSql, Long.class, tenantId, unique("event"), unique("trace"));
  }

  private Long insertDeliveryLog(String tenantId, Long outboxId) {
    Instant outboxCreatedAt = outboxCreatedAt(outboxId);
    Timestamp persistedOutboxCreatedAt = Timestamp.from(outboxCreatedAt);
    String eventKey = unique("event");
    String traceId = unique("trace");
    String insertDeliveryLogSql = """
        insert into batch.event_delivery_log(
          tenant_id, outbox_event_id, outbox_event_created_at, event_type, event_key, target_topic,
          delivery_status, delivery_attempt, trace_id, created_at, updated_at
        ) values (?, ?, ?, 'IMPORT', ?, 'batch.task.dispatch.import', 'PUBLISHED', 1, ?, now() - interval\s\
        '3 days', now() - interval '3 days')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertDeliveryLogSql,
        Long.class,
        tenantId,
        outboxId,
        persistedOutboxCreatedAt,
        eventKey,
        traceId);
  }

  private Long insertOutboxRetry(String tenantId, Long outboxId) {
    Instant outboxCreatedAt = outboxCreatedAt(outboxId);
    Timestamp persistedOutboxCreatedAt = Timestamp.from(outboxCreatedAt);
    String retryEventKey = unique("retry-event");
    String insertOutboxRetrySql = """
        insert into batch.event_outbox_retry(
          tenant_id, outbox_event_id, outbox_event_created_at, event_key, retry_attempt, retry_status,
          created_at, updated_at
        ) values (?, ?, ?, ?, 1, 'FAILED', now() - interval '3 days', now() - interval '3 days')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertOutboxRetrySql,
        Long.class,
        tenantId,
        outboxId,
        persistedOutboxCreatedAt,
        retryEventKey);
  }

  private Instant outboxCreatedAt(Long outboxId) {
    String outboxCreatedAtSql = """
        select created_at from batch.outbox_event where id = ?
        """;
    return jdbcTemplate.queryForObject(outboxCreatedAtSql, Instant.class, outboxId);
  }

  private Long insertJobDefinition(String tenantId) {
    String insertJobDefinitionSql = """
        insert into batch.job_definition(
          tenant_id, job_code, job_name, job_type, schedule_type, timezone, retry_policy
        ) values (?, ?, 'Archive Test Job', 'GENERAL', 'MANUAL', 'Asia/Shanghai', 'NONE')
        returning id
        """;
    return jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId, unique("job"));
  }

  private record ArchiveConflictProbe(String updateSql, Long id) {}

  private void assertRuntimeTreeRowLocks(Long instanceId, List<ArchiveConflictProbe> probes) {
    String pidSql = "select pg_backend_pid()";
    String blockedBySql = "select ? = any(pg_blocking_pids(?))";
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      for (ArchiveConflictProbe probe : probes) {
        AtomicReference<Integer> writerPid = new AtomicReference<>();
        AtomicReference<Future<Integer>> update = new AtomicReference<>();
        TransactionTemplate archiveTransaction = new TransactionTemplate(transactionManager);
        archiveTransaction.executeWithoutResult(status -> {
          assertThat(archiveMapper.selectArchivableInstanceIds(Instant.now(), 10000))
              .contains(instanceId);
          archiveMapper.selectPipelineInstanceIdsForUpdate(List.of(instanceId));
          archiveMapper.lockRuntimeTreeRows(List.of(instanceId));
          Integer archivePid = jdbcTemplate.queryForObject(pidSql, Integer.class);
          String updateHotSql =
              probe.updateSql().replace("archive.", "batch.").replace("_archive", "");
          update.set(executor.submit(() -> {
            TransactionTemplate writerTransaction = new TransactionTemplate(transactionManager);
            return writerTransaction.execute(writerStatus -> {
              writerPid.set(jdbcTemplate.queryForObject(pidSql, Integer.class));
              int changed = jdbcTemplate.update(updateHotSql, probe.id());
              writerStatus.setRollbackOnly();
              return changed;
            });
          }));
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
          boolean blocked = false;
          try {
            while (System.nanoTime() < deadline && !update.get().isDone()) {
              Integer pid = writerPid.get();
              Boolean isBlocked = pid == null
                  ? false
                  : jdbcTemplate.queryForObject(blockedBySql, Boolean.class, archivePid, pid);
              if (Boolean.TRUE.equals(isBlocked)) {
                blocked = true;
                break;
              }
              Thread.sleep(10);
            }
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("lock verification interrupted", ex);
          }
          assertThat(blocked).as(updateHotSql).isTrue();
        });
        try {
          assertThat(update.get().get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } catch (Exception ex) {
          throw new AssertionError("writer did not complete after archive lock release", ex);
        }
      }
    }
  }

  private void assertRuntimeTreeConflictDetection(
      Long instanceId, List<ArchiveConflictProbe> probes) {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(status -> {
      List<Long> ids = List.of(instanceId);
      archiveMapper.archiveJobPartitionsByInstanceIds(ids);
      archiveMapper.archiveJobStepInstancesByInstanceIds(ids);
      archiveMapper.archivePipelineInstancesByInstanceIds(ids);
      archiveMapper.archivePipelineStepRunsByInstanceIds(ids);
      archiveMapper.archiveFileDispatchRecordsByInstanceIds(ids);
      archiveMapper.archiveWorkflowRunsByInstanceIds(ids);
      archiveMapper.archiveWorkflowNodeRunsByInstanceIds(ids);
      archiveMapper.archiveJobExecutionLogsByInstanceIds(ids);
      archiveMapper.archiveCompensationCommandsByInstanceIds(ids);
      assertThat(archiveMapper.countRuntimeTreeArchiveMismatches(ids)).isZero();
      for (ArchiveConflictProbe probe : probes) {
        Object savepoint = status.createSavepoint();
        assertThat(jdbcTemplate.update(probe.updateSql(), probe.id())).isEqualTo(1);
        sqlSessionTemplate.clearCache();
        assertThat(archiveMapper.countRuntimeTreeArchiveMismatches(ids))
            .as(probe.updateSql())
            .isEqualTo(1);
        status.rollbackToSavepoint(savepoint);
        status.releaseSavepoint(savepoint);
      }
      status.setRollbackOnly();
    });
  }

  private Long insertOldSuccessInstance(String tenantId, Long definitionId) {
    return insertOldTerminalInstance(tenantId, definitionId, "SUCCESS", false);
  }

  private Long insertOldTerminalInstance(
      String tenantId, Long definitionId, String status, boolean dryRun) {
    final String insertOldTerminalInstanceSql = """
    insert into batch.job_instance(
    tenant_id, job_definition_id, job_code, instance_no, biz_date, trigger_type,
    instance_status, priority, dedup_key, expected_partition_count,
    success_partition_count, failed_partition_count, trace_id, finished_at, dry_run
    ) values (?, ?, 'ARCHIVE_JOB', ?, current_date - 3, 'MANUAL', ?, 5, ?, 1, 1, 0, ?,
    now() - interval '3 days', ?)
    returning id
    """;
    return jdbcTemplate.queryForObject(
        insertOldTerminalInstanceSql,
        Long.class,
        tenantId,
        definitionId,
        unique("inst"),
        status,
        unique("dedup"),
        unique("trace"),
        dryRun);
  }

  private Long insertJobPartition(String tenantId, Long instanceId) {
    String insertJobPartitionSql = """
        insert into batch.job_partition(
          tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_status, business_key,
          idempotency_key, finished_at
        ) values (?, ?, (select biz_date from batch.job_instance where tenant_id=?\s\
        and id=?), 1, 'SUCCESS', ?, ?, now() - interval '3 days')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertJobPartitionSql,
        Long.class,
        tenantId,
        instanceId,
        tenantId,
        instanceId,
        unique("biz"),
        unique("idem"));
  }

  private Long insertJobTask(String tenantId, Long instanceId, Long partitionId) {
    String insertJobTaskSql = """
        insert into batch.job_task(
          tenant_id, job_instance_id, job_instance_biz_date, job_partition_id, task_type, task_seq, task_status,
          finished_at
        ) values (?, ?, (select biz_date from batch.job_instance where tenant_id=?\s\
        and id=?), ?, 'EXECUTION', 1, 'SUCCESS', now() - interval '3 days')
        returning id
        """;
    return jdbcTemplate.queryForObject(
        insertJobTaskSql, Long.class, tenantId, instanceId, tenantId, instanceId, partitionId);
  }

  private Long insertJobStepInstance(
      String tenantId, Long instanceId, Long partitionId, Long taskId) {
    final String insertJobStepInstanceSql = """
    insert into batch.job_step_instance(
    tenant_id, job_instance_id, job_partition_id, job_task_id, step_code, step_type,
    step_status, finished_at
    ) values (?, ?, ?, ?, 'STEP_1', 'TASK', 'SUCCESS', now() - interval '3 days')
    returning id
    """;
    return jdbcTemplate.queryForObject(
        insertJobStepInstanceSql, Long.class, tenantId, instanceId, partitionId, taskId);
  }

  private JobExecutionLogEntity buildExecutionLog(String tenantId, Long instanceId) {
    JobExecutionLogEntity log = new JobExecutionLogEntity();
    log.setTenantId(tenantId);
    log.setJobInstanceId(instanceId);
    log.setLogLevel("INFO");
    log.setLogType("AUDIT");
    log.setMessage("application reference guard test");
    return log;
  }

  private void awaitExecutionLogInsertLockWait() throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    String waitingExecutionLogInsertSql = """
        select min(pid)
          from pg_stat_activity
         where wait_event_type='Lock'
           and lower(query) like 'with matching_instance as materialized%'
        """;
    while (System.nanoTime() < deadline) {
      Long waiting = jdbcTemplate.queryForObject(waitingExecutionLogInsertSql, Long.class);
      if (waiting != null) {
        return;
      }
      Thread.sleep(10);
    }
    throw new AssertionError("execution log insert did not wait on the locked JobInstance");
  }

  private void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting to release JobInstance delete");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while waiting to release JobInstance delete", interrupted);
    }
  }

  private Long insertCrossTenantInstanceExecutionLog(String logTenantId, Long instanceId) {
    String insertCrossTenantInstanceExecutionLogSql = """
        insert into batch.job_execution_log(
          tenant_id,job_instance_id,job_partition_id,log_level,log_type,message
        ) values (?, ?, null, 'INFO', 'AUDIT', 'cross-tenant instance reference') returning id
        """;
    return jdbcTemplate.queryForObject(
        insertCrossTenantInstanceExecutionLogSql, Long.class, logTenantId, instanceId);
  }

  private int count(String tableName, Long id) {
    String countSql = """
        select count(*) from %s where id = ?
        """.formatted(tableName);
    return jdbcTemplate.queryForObject(countSql, Integer.class, id);
  }

  private String readConstraintDefinition(String schema, String table, String constraint) {
    String constraintDefinitionSql = """
        select pg_get_constraintdef(c.oid)
        from pg_constraint c
        join pg_class t on t.oid = c.conrelid
        join pg_namespace n on n.oid = t.relnamespace
        where n.nspname = ? and t.relname = ? and c.conname = ?
        """;
    return jdbcTemplate.queryForObject(
        constraintDefinitionSql, String.class, schema, table, constraint);
  }

  private long countArchiveIntegrityAlerts(String tenantId) {
    String countArchiveIntegrityAlertsSql = """
        select count(*) from batch.alert_event
        where tenant_id = ? and alert_type = 'SUCCESS_INSTANCE_ARCHIVE_INTEGRITY'
        """;
    return jdbcTemplate.queryForObject(countArchiveIntegrityAlertsSql, Long.class, tenantId);
  }

  private int archiveIntegrityAlertOccurrences(String tenantId) {
    String archiveIntegrityAlertOccurrencesSql = """
        select occurrence_count from batch.alert_event
        where tenant_id = ? and alert_type = 'SUCCESS_INSTANCE_ARCHIVE_INTEGRITY'
        """;
    return jdbcTemplate.queryForObject(
        archiveIntegrityAlertOccurrencesSql, Integer.class, tenantId);
  }

  private Long archiveIntegrityAlertSampleInstanceId(String tenantId) {
    String archiveIntegrityAlertSampleInstanceIdSql = """
        select (detail_json ->> 'sampleInstanceId')::bigint from batch.alert_event
        where tenant_id = ? and alert_type = 'SUCCESS_INSTANCE_ARCHIVE_INTEGRITY'
        """;
    return jdbcTemplate.queryForObject(
        archiveIntegrityAlertSampleInstanceIdSql, Long.class, tenantId);
  }

  private String rowSnapshot(String schema, String table, Long id) {
    String rowSnapshotSql = """
        select to_jsonb(row_data)::text from %s.%s row_data where id = ?
        """.formatted(schema, table);
    return jdbcTemplate.queryForObject(rowSnapshotSql, String.class, id);
  }

  private String unique(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }
}
