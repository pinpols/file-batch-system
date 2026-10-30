package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.orchestrator.domain.context.TriggerLaunchPersistenceContext;
import io.github.pinpols.batch.orchestrator.domain.query.TriggerLaunchContinuityMetrics;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link TriggerRequestMapper} 的 nullable 参数和状态 CAS 真实 PostgreSQL 回归测试。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("触发器请求持久层:可空关联参数的状态写入与最新一次尝试投影查询在真实数据库上的行为")
class TriggerRequestMapperIntegrationTest extends AbstractIntegrationTest {

  private final String tenantId = "trigger-mapper-" + Long.toUnsignedString(System.nanoTime());
  private final String foreignTenantId = tenantId + "-foreign";
  private final String requestId = "request-" + Long.toUnsignedString(System.nanoTime());

  private final TriggerRequestMapper triggerRequestMapper;
  private final JdbcTemplate jdbcTemplate;
  private final PlatformTransactionManager transactionManager;

  @Autowired
  TriggerRequestMapperIntegrationTest(
      TriggerRequestMapper triggerRequestMapper,
      JdbcTemplate jdbcTemplate,
      PlatformTransactionManager transactionManager) {
    this.triggerRequestMapper = triggerRequestMapper;
    this.jdbcTemplate = jdbcTemplate;
    this.transactionManager = transactionManager;
  }

  @AfterEach
  void cleanUp() {
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where tenant_id = ?
    """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where tenant_id = ?
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ?
    """;
    final String deleteJobDefinitionSql = """
        delete from batch.job_definition where tenant_id = ?
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ?
    """;

    jdbcTemplate.update(deleteJobInstanceSql, tenantId);
    jdbcTemplate.update(deleteJobInstanceSql, foreignTenantId);
    jdbcTemplate.update(deleteWorkflowRunSql, tenantId);
    jdbcTemplate.update(deleteWorkflowRunSql, foreignTenantId);
    jdbcTemplate.update(deleteTriggerRequestSql, tenantId);
    jdbcTemplate.update(deleteTriggerRequestSql, foreignTenantId);
    jdbcTemplate.update(deleteJobDefinitionSql, tenantId);
    jdbcTemplate.update(deleteJobDefinitionSql, foreignTenantId);
    jdbcTemplate.update(deleteWorkflowDefinitionSql, tenantId);
    jdbcTemplate.update(deleteWorkflowDefinitionSql, foreignTenantId);
  }

  @Test
  @DisplayName("关联作业实例标识为空时状态写入仍成功,请求状态被更新且关联实例为空,上下文无既有实例")
  void shouldAcceptNullRelatedJobInstanceId_whenUpdatingAcceptance() {
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
              dedup_key, request_status, trace_id
          ) values (?, ?, 'MANUAL', ?, current_date, ?, 'ACCEPTED', 'trace-mapper-null')
          """;

    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_NULL_LINK");
    jdbcTemplate.update(
        insertTriggerRequestSql, tenantId, requestId, workflowDefinitionId, "dedup-" + requestId);

    int updated = triggerRequestMapper.updateAcceptance(
        tenantId, requestId, TriggerRequestStatus.REJECTED.code(), null);

    TriggerRequestEntity request =
        triggerRequestMapper.selectByTenantAndRequestId(tenantId, requestId);
    TriggerLaunchPersistenceContext context =
        triggerRequestMapper.selectLaunchPersistenceContext(tenantId, requestId);
    assertThat(updated).isOne();
    assertThat(request.getRequestStatus()).isEqualTo(TriggerRequestStatus.REJECTED.code());
    assertThat(request.getRelatedJobInstanceId()).isNull();
    assertThat(context.getTriggerRequest().getId()).isEqualTo(request.getId());
    assertThat(context.getExistingInstance()).isNull();
  }

  @Test
  @DisplayName("状态写回拒绝关联其他租户的 JobInstance")
  void shouldRejectForeignJobInstance_whenUpdatingAcceptanceOrReconcilingStatus() {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone,\s\
        version, enabled) values (?, 'foreign-link-job', 'Foreign link job', 'GENERAL', 'MANUAL', 'UTC', 1,\s\
        true) returning id\
        """;
    final String insertJobInstanceSql = """
        insert into batch.job_instance (tenant_id, job_definition_id, job_code, instance_no, biz_date,\s\
        trigger_type, instance_status, worker_group, dedup_key, run_attempt)\s\
        values (?, ?, 'foreign-link-job', ?, current_date, 'MANUAL', 'SUCCESS', 'default', ?, 1) returning id\
        """;

    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_TENANT_GUARD");
    Long foreignJobDefinitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, foreignTenantId);
    Long foreignJobInstanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        foreignTenantId,
        foreignJobDefinitionId,
        "foreign-link-instance-" + requestId,
        "foreign-link-key-" + requestId);
    String acceptanceRequestId = requestId + "-acceptance-foreign";
    String reconcileRequestId = requestId + "-reconcile-foreign";
    insertAcceptedWorkflowRequest(workflowDefinitionId, acceptanceRequestId);
    insertAcceptedWorkflowRequest(workflowDefinitionId, reconcileRequestId);

    assertThat(triggerRequestMapper.updateAcceptance(
            tenantId,
            acceptanceRequestId,
            TriggerRequestStatus.LAUNCHED.code(),
            foreignJobInstanceId))
        .isZero();
    assertThat(triggerRequestMapper.reconcileLaunched(
            tenantId, reconcileRequestId, foreignJobInstanceId))
        .isZero();
    for (String request : List.of(acceptanceRequestId, reconcileRequestId)) {
      TriggerRequestEntity persisted =
          triggerRequestMapper.selectByTenantAndRequestId(tenantId, request);
      assertThat(persisted.getRequestStatus()).isEqualTo(TriggerRequestStatus.ACCEPTED.code());
      assertThat(persisted.getRelatedJobInstanceId()).isNull();
    }
  }

  @Test
  @DisplayName("TriggerRequest 关联 JobInstance 与父实例删除串行化,不会提交悬空历史引用")
  void shouldSerializeTriggerRequestAttachmentAgainstJobInstanceDelete() throws Exception {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone,\s\
        version, enabled) values (?, 'reference-race-job', 'Reference race job', 'GENERAL', 'MANUAL', 'UTC', 1,\s\
        true) returning id\
        """;
    final String selectIdSql = """
        select id from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where id = ?
    """;
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;
    final String selectCountSql = """
        select count(*) from pg_stat_activity where pid = ? and wait_event_type = 'Lock'
    """;
    final String selectRequestStatusSql = """
        select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
    """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.trigger_request where tenant_id = ? and request_id = ?\
        """;

    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_REFERENCE_RACE");
    Long jobDefinitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId);
    String raceRequestId = requestId + "-reference-race";
    insertAcceptedWorkflowRequest(workflowDefinitionId, raceRequestId);
    Long triggerRequestRowId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, tenantId, raceRequestId);
    Long jobInstanceId = insertJobInstance(
        jobDefinitionId,
        triggerRequestRowId,
        "dedup-" + raceRequestId,
        1,
        "instance-" + raceRequestId,
        "trace-" + raceRequestId);

    CountDownLatch parentDeleted = new CountDownLatch(1);
    CountDownLatch allowParentCommit = new CountDownLatch(1);
    CountDownLatch linkStarted = new CountDownLatch(1);
    AtomicInteger linkPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> delete = null;
    Future<?> attach = null;
    try {
      delete = executor.submit(
          () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update(deleteJobInstanceSql, jobInstanceId);
            parentDeleted.countDown();
            awaitLatch(allowParentCommit);
          }));
      assertThat(parentDeleted.await(5, TimeUnit.SECONDS)).isTrue();
      attach = executor.submit(() -> new TransactionTemplate(transactionManager)
          .executeWithoutResult(status -> {
            linkPid.set(jdbcTemplate.queryForObject(selectPgBackendPidSql, Integer.class));
            linkStarted.countDown();
            triggerRequestMapper.updateAcceptance(
                tenantId, raceRequestId, TriggerRequestStatus.LAUNCHED.code(), jobInstanceId);
          }));
      assertThat(linkStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(
              () -> jdbcTemplate.queryForObject(selectCountSql, Integer.class, linkPid.get()) == 1);

      allowParentCommit.countDown();
      delete.get(10, TimeUnit.SECONDS);
      Future<?> attachFuture = attach;
      assertThatThrownBy(() -> attachFuture.get(10, TimeUnit.SECONDS))
          .hasCauseInstanceOf(DataIntegrityViolationException.class);
      String selectedRequestStatus = jdbcTemplate.queryForObject(
          selectRequestStatusSql, String.class, tenantId, raceRequestId);
      assertThat(selectedRequestStatus).isEqualTo(TriggerRequestStatus.ACCEPTED.code());
      Long selectedRelatedJobInstanceId = jdbcTemplate.queryForObject(
          selectRelatedJobInstanceIdSql, Long.class, tenantId, raceRequestId);
      assertThat(selectedRelatedJobInstanceId).isNull();
    } finally {
      allowParentCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("TriggerRequest 历史弱引用拒绝关联其他租户的 JobInstance")
  void shouldRejectCrossTenantTriggerRequestJobInstanceReference() {
    final String updateTriggerRequestSql = """
        update batch.trigger_request set related_job_instance_id = ? where tenant_id = ? and id = ?\
        """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.trigger_request where tenant_id = ? and id = ?
    """;

    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_REFERENCE_TENANT");
    String localRequestId = requestId + "-local-reference";
    Long localTriggerRequestId =
        insertAcceptedWorkflowRequest(tenantId, workflowDefinitionId, localRequestId);
    Long foreignWorkflowDefinitionId =
        insertWorkflowDefinition(foreignTenantId, "CAL_REFERENCE_TENANT_FOREIGN");
    Long foreignTriggerRequestId = insertAcceptedWorkflowRequest(
        foreignTenantId, foreignWorkflowDefinitionId, requestId + "-foreign-reference");
    Long foreignJobDefinitionId = insertJobDefinition(foreignTenantId);
    Long foreignJobInstanceId = insertJobInstance(
        foreignTenantId,
        foreignJobDefinitionId,
        foreignTriggerRequestId,
        "foreign-dedup-" + requestId,
        1,
        "foreign-instance-" + requestId,
        "foreign-trace-" + requestId);

    assertReferenceConstraintViolation(
        () -> jdbcTemplate.update(
            updateTriggerRequestSql, foreignJobInstanceId, tenantId, localTriggerRequestId),
        "fk_trigger_request_related_job_instance_reference");
    Long selectedRelatedJobInstanceId = jdbcTemplate.queryForObject(
        selectRelatedJobInstanceIdSql, Long.class, tenantId, localTriggerRequestId);
    assertThat(selectedRelatedJobInstanceId).isNull();
  }

  @Test
  @DisplayName("TriggerRequest 对父 WorkflowRun 的历史引用阻止父身份改写,但允许归档删除")
  void shouldProtectTriggerRequestParentWorkflowRunIdentityAndKeepWeakDeleteSemantics() {
    final String updateWorkflowRunSql = """
      update batch.workflow_run set id = id + 1000000000 where id = ?
  """;
    final String updateWorkflowRunSql2 = """
      update batch.workflow_run set tenant_id = ? where id = ?
  """;
    final String updateWorkflowRunSql3 = """
      update batch.workflow_run set biz_date = biz_date + 1 where id = ?
  """;
    final String updateTriggerRequestSql = """
      update batch.trigger_request set parent_workflow_run_id = ? where id = ?
  """;
    final String updateTriggerRequestSql2 = """
      update batch.trigger_request set parent_node_code = 'OTHER_NODE' where id = ?
  """;
    final String deleteWorkflowRunSql = """
        delete from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectParentWorkflowRunIdSql = """
        select parent_workflow_run_id from batch.trigger_request where tenant_id = ? and request_id = ?\
        """;

    LocalDate bizDate = LocalDate.of(2026, 10, 19);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_PARENT_RUN_IDENTITY");
    Long parentWorkflowRunId = insertWorkflowRun(tenantId, workflowDefinitionId, bizDate);
    Long otherWorkflowRunId =
        insertWorkflowRun(tenantId, workflowDefinitionId, bizDate.plusDays(1));
    String childRequestId = requestId + "-parent-run-identity";
    Long childRequestRowId =
        insertChildWorkflowRequest(tenantId, childRequestId, parentWorkflowRunId, bizDate);

    assertDatabaseConstraintViolation(
        () -> jdbcTemplate.update(updateWorkflowRunSql, parentWorkflowRunId),
        "23503",
        "fk_trigger_request_parent_workflow_run_reference");
    assertDatabaseConstraintViolation(
        () -> jdbcTemplate.update(updateWorkflowRunSql2, foreignTenantId, parentWorkflowRunId),
        "23503",
        "fk_trigger_request_parent_workflow_run_reference");
    assertDatabaseConstraintViolation(
        () -> jdbcTemplate.update(updateWorkflowRunSql3, parentWorkflowRunId),
        "23503",
        "fk_trigger_request_parent_workflow_run_reference");
    assertDatabaseConstraintViolation(
        () -> jdbcTemplate.update(updateTriggerRequestSql, otherWorkflowRunId, childRequestRowId),
        "23514",
        "ck_trigger_request_parent_workflow_run_immutable");
    assertDatabaseConstraintViolation(
        () -> jdbcTemplate.update(updateTriggerRequestSql2, childRequestRowId),
        "23514",
        "ck_trigger_request_parent_workflow_run_immutable");

    int affectedWorkflowRunRows =
        jdbcTemplate.update(deleteWorkflowRunSql, tenantId, parentWorkflowRunId);
    assertThat(affectedWorkflowRunRows).isOne();
    Long selectedParentWorkflowRunId = jdbcTemplate.queryForObject(
        selectParentWorkflowRunIdSql, Long.class, tenantId, childRequestId);
    assertThat(selectedParentWorkflowRunId).isEqualTo(parentWorkflowRunId);
  }

  @Test
  @DisplayName("创建子 TriggerRequest 时拒绝关联其他租户的 WorkflowRun")
  void shouldRejectCrossTenantTriggerRequestParentWorkflowRun() {
    LocalDate bizDate = LocalDate.of(2026, 10, 19);
    Long foreignWorkflowDefinitionId =
        insertWorkflowDefinition(foreignTenantId, "CAL_FOREIGN_PARENT_RUN");
    Long foreignParentWorkflowRunId =
        insertWorkflowRun(foreignTenantId, foreignWorkflowDefinitionId, bizDate);
    String childRequestId = requestId + "-foreign-parent-run";

    Throwable failure = catchThrowable(() ->
        insertChildWorkflowRequest(tenantId, childRequestId, foreignParentWorkflowRunId, bizDate));

    assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(PSQLException.class);
    assertThat(((PSQLException) cause).getSQLState()).isEqualTo("23514");
    assertThat(triggerRequestMapper.selectByTenantAndRequestId(tenantId, childRequestId))
        .isNull();
  }

  @Test
  @DisplayName("创建子 TriggerRequest 与父 WorkflowRun 删除串行化,不会提交悬空引用")
  void shouldSerializeTriggerRequestParentWorkflowAttachmentAgainstWorkflowRunDelete()
      throws Exception {
    final String selectPgBackendPidSql = """
            select pg_backend_pid()
        """;
    final String insertTriggerRequestSql = """
            insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code, biz_date,\s\
            dedup_key, request_status, parent_workflow_run_id, parent_node_code)\s\
            values (?, ?, 'EVENT', 'child-job', ?, ?, 'ACCEPTED', ?, 'CHILD_JOB')\
            """;

    final String deleteWorkflowRunSql = """
            delete from batch.workflow_run where tenant_id = ? and id = ?
        """;
    final String selectCountSql = """
            select count(*) from pg_stat_activity where pid = ? and wait_event_type = 'Lock'
        """;
    final String selectCountSql2 = """
      select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
  """;

    LocalDate bizDate = LocalDate.of(2026, 10, 19);
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_PARENT_RUN_RACE");
    Long parentWorkflowRunId = insertWorkflowRun(tenantId, workflowDefinitionId, bizDate);
    String childRequestId = requestId + "-parent-run-race";
    CountDownLatch parentDeleted = new CountDownLatch(1);
    CountDownLatch allowParentCommit = new CountDownLatch(1);
    CountDownLatch attachmentStarted = new CountDownLatch(1);
    AtomicInteger attachmentPid = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> delete = null;
    Future<String> attach = null;
    try {
      delete = executor.submit(
          () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.update(deleteWorkflowRunSql, tenantId, parentWorkflowRunId);
            parentDeleted.countDown();
            awaitLatch(allowParentCommit);
          }));
      assertThat(parentDeleted.await(5, TimeUnit.SECONDS)).isTrue();
      attach = executor.submit(() -> {
        try (var connection = jdbcTemplate.getDataSource().getConnection()) {
          connection.setAutoCommit(false);
          try (var pidQuery = connection.prepareStatement(selectPgBackendPidSql)) {
            try (var result = pidQuery.executeQuery()) {
              assertThat(result.next()).isTrue();
              attachmentPid.set(result.getInt(1));
            }
          }
          attachmentStarted.countDown();
          try (var statement = connection.prepareStatement(insertTriggerRequestSql)) {
            statement.setString(1, tenantId);
            statement.setString(2, childRequestId);
            statement.setObject(3, bizDate);
            statement.setString(4, "dedup-" + childRequestId);
            statement.setLong(5, parentWorkflowRunId);
            statement.executeUpdate();
          }
          connection.commit();
          return "committed";
        } catch (SQLException failure) {
          return failure.getSQLState();
        }
      });
      assertThat(attachmentStarted.await(5, TimeUnit.SECONDS)).isTrue();
      Awaitility.await()
          .atMost(Duration.ofSeconds(5))
          .until(() ->
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, attachmentPid.get()) == 1);

      allowParentCommit.countDown();
      delete.get(10, TimeUnit.SECONDS);
      assertThat(attach.get(10, TimeUnit.SECONDS)).isEqualTo("23514");
      Integer selectedTriggerRequestCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, tenantId, childRequestId);
      assertThat(selectedTriggerRequestCount).isZero();
    } finally {
      allowParentCommit.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("TriggerRequest 新建时也拒绝写入其他租户的 JobInstance 弱引用")
  void shouldRejectCrossTenantTriggerRequestJobInstanceReferenceOnInsert() {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status, trace_id, related_job_instance_id)\s\
        values (?, ?, 'API', ?, current_date, ?, 'LAUNCHED', ?, ?)\
        """;

    Long localWorkflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_REFERENCE_INSERT");
    Long foreignWorkflowDefinitionId =
        insertWorkflowDefinition(foreignTenantId, "CAL_REFERENCE_INSERT_FOREIGN");
    Long foreignTriggerRequestId = insertAcceptedWorkflowRequest(
        foreignTenantId, foreignWorkflowDefinitionId, requestId + "-foreign-insert-reference");
    Long foreignJobDefinitionId = insertJobDefinition(foreignTenantId);
    Long foreignJobInstanceId = insertJobInstance(
        foreignTenantId,
        foreignJobDefinitionId,
        foreignTriggerRequestId,
        "foreign-insert-dedup-" + requestId,
        1,
        "foreign-insert-instance-" + requestId,
        "foreign-insert-trace-" + requestId);
    String localRequestId = requestId + "-insert-reference";

    assertReferenceConstraintViolation(
        () -> jdbcTemplate.update(
            insertTriggerRequestSql,
            tenantId,
            localRequestId,
            localWorkflowDefinitionId,
            "dedup-" + localRequestId,
            "trace-" + localRequestId,
            foreignJobInstanceId),
        "fk_trigger_request_related_job_instance_reference");
    assertThat(triggerRequestMapper.selectByTenantAndRequestId(tenantId, localRequestId))
        .isNull();
  }

  @Test
  @DisplayName("TriggerRequest 已建立的歷史關聯阻止 JobInstance 身份改寫")
  void shouldRejectJobInstanceRekeyWhileTriggerRequestHistoryReferencesIt() {
    final String selectBizDateSql = """
      select biz_date from batch.job_instance where id = ?
  """;
    final String updateJobInstanceSql = """
      update batch.job_instance set id = id + 1000000000000 where id = ?
  """;
    final String updateJobInstanceSql2 = """
      update batch.job_instance set biz_date = biz_date + 1 where id = ?
  """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.trigger_request where tenant_id = ? and request_id = ?
    """;

    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_REFERENCE_REKEY");
    String linkedRequestId = requestId + "-reference-rekey";
    Long triggerRequestRowId =
        insertAcceptedWorkflowRequest(tenantId, workflowDefinitionId, linkedRequestId);
    Long jobDefinitionId = insertJobDefinition(tenantId);
    Long jobInstanceId = insertJobInstance(
        tenantId,
        jobDefinitionId,
        triggerRequestRowId,
        "dedup-" + linkedRequestId,
        1,
        "instance-" + linkedRequestId,
        "trace-" + linkedRequestId);
    assertThat(triggerRequestMapper.updateAcceptance(
            tenantId, linkedRequestId, TriggerRequestStatus.LAUNCHED.code(), jobInstanceId))
        .isOne();
    LocalDate originalBizDate =
        jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, jobInstanceId);

    assertReferenceConstraintViolation(
        () -> jdbcTemplate.update(updateJobInstanceSql, jobInstanceId),
        "fk_trigger_request_related_job_instance_reference");
    assertReferenceConstraintViolation(
        () -> jdbcTemplate.update(updateJobInstanceSql2, jobInstanceId),
        "fk_trigger_request_related_job_instance_reference");
    LocalDate selectedBizDate =
        jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, jobInstanceId);
    assertThat(selectedBizDate).isEqualTo(originalBizDate);
    Long selectedRelatedJobInstanceId = jdbcTemplate.queryForObject(
        selectRelatedJobInstanceIdSql, Long.class, tenantId, linkedRequestId);
    assertThat(selectedRelatedJobInstanceId).isEqualTo(jobInstanceId);
  }

  @Test
  @DisplayName("TriggerRequest 历史弱引用不阻止实例归档删除并保留原实例标识")
  void shouldPreserveTriggerRequestHistoricalLinkAfterJobInstanceDeletion() {
    final String deleteJobInstanceSql = """
        delete from batch.job_instance where id = ?
    """;
    final String selectRelatedJobInstanceIdSql = """
        select related_job_instance_id from batch.trigger_request where tenant_id = ? and request_id = ?
    """;

    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_REFERENCE_ARCHIVE");
    String linkedRequestId = requestId + "-reference-archive";
    Long triggerRequestRowId =
        insertAcceptedWorkflowRequest(tenantId, workflowDefinitionId, linkedRequestId);
    Long jobDefinitionId = insertJobDefinition(tenantId);
    Long jobInstanceId = insertJobInstance(
        tenantId,
        jobDefinitionId,
        triggerRequestRowId,
        "dedup-" + linkedRequestId,
        1,
        "instance-" + linkedRequestId,
        "trace-" + linkedRequestId);
    assertThat(triggerRequestMapper.updateAcceptance(
            tenantId, linkedRequestId, TriggerRequestStatus.LAUNCHED.code(), jobInstanceId))
        .isOne();

    int affectedJobInstanceRows = jdbcTemplate.update(deleteJobInstanceSql, jobInstanceId);
    assertThat(affectedJobInstanceRows).isOne();
    Long selectedRelatedJobInstanceId = jdbcTemplate.queryForObject(
        selectRelatedJobInstanceIdSql, Long.class, tenantId, linkedRequestId);
    assertThat(selectedRelatedJobInstanceId).isEqualTo(jobInstanceId);
  }

  private Long insertAcceptedWorkflowRequest(Long workflowDefinitionId, String id) {
    return insertAcceptedWorkflowRequest(tenantId, workflowDefinitionId, id);
  }

  private Long insertAcceptedWorkflowRequest(
      String requestTenant, Long workflowDefinitionId, String id) {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, workflow_definition_id,\s\
        biz_date, dedup_key, request_status, trace_id) values (?, ?, 'API', ?, current_date, ?, 'ACCEPTED', ?)\s\
        returning id\
        """;

    return jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        requestTenant,
        id,
        workflowDefinitionId,
        "dedup-" + id,
        "trace-" + id);
  }

  private Long insertWorkflowRun(String runTenant, Long workflowDefinitionId, LocalDate bizDate) {
    final String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id, workflow_definition_id, biz_date, run_status)\s\
        values (?, ?, ?, 'RUNNING') returning id\
        """;

    return jdbcTemplate.queryForObject(
        insertWorkflowRunSql, Long.class, runTenant, workflowDefinitionId, bizDate);
  }

  private Long insertChildWorkflowRequest(
      String requestTenant, String id, Long parentWorkflowRunId, LocalDate bizDate) {
    final String insertTriggerRequestSql = """
        insert into batch.trigger_request (tenant_id, request_id, trigger_type, job_code, biz_date, dedup_key,\s\
        request_status, parent_workflow_run_id, parent_node_code)\s\
        values (?, ?, 'EVENT', 'child-job', ?, ?, 'ACCEPTED', ?, 'CHILD_JOB') returning id\
        """;

    return jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        requestTenant,
        id,
        bizDate,
        "dedup-" + id,
        parentWorkflowRunId);
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out waiting for test synchronization latch");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(
          "interrupted while waiting for test synchronization", interrupted);
    }
  }

  private static void assertReferenceConstraintViolation(
      Runnable write, String expectedConstraint) {
    assertDatabaseConstraintViolation(write, "23503", expectedConstraint);
  }

  private static void assertDatabaseConstraintViolation(
      Runnable write, String expectedSqlState, String expectedConstraint) {
    Throwable failure = catchThrowable(write::run);
    assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);
    Throwable cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    assertThat(cause).isInstanceOf(PSQLException.class);
    PSQLException postgresFailure = (PSQLException) cause;
    assertThat(postgresFailure.getSQLState()).isEqualTo(expectedSqlState);
    assertThat(postgresFailure.getServerErrorMessage().getConstraint())
        .isEqualTo(expectedConstraint);
  }

  @Test
  @DisplayName("空日结算查询仅统计同租户同日历同业务日的未派发请求")
  void shouldCountUnlaunchedRequestsForCalendarBizDate() {
    Long workflowId = insertWorkflowDefinition(tenantId, "CAL_A");
    Long otherCalendarWorkflowId = insertWorkflowDefinition(tenantId, "CAL_B");
    LocalDate bizDate = LocalDate.of(2026, 5, 4);
    insertBatchDayTriggerRequest(workflowId, "pending", LocalDate.of(2026, 5, 4), "PENDING");
    insertBatchDayTriggerRequest(workflowId, "accepted", LocalDate.of(2026, 5, 4), "ACCEPTED");
    insertBatchDayTriggerRequest(workflowId, "launched", LocalDate.of(2026, 5, 4), "LAUNCHED");
    insertBatchDayTriggerRequest(workflowId, "other-date", LocalDate.of(2026, 5, 5), "PENDING");
    insertBatchDayTriggerRequest(
        otherCalendarWorkflowId, "other-calendar", LocalDate.of(2026, 5, 4), "PENDING");
    Long parentRun = insertWorkflowRun(tenantId, workflowId, bizDate);
    insertChildWorkflowRequest(tenantId, requestId + "-child-pending", parentRun, bizDate);
    Long otherCalendarParentRun = insertWorkflowRun(tenantId, otherCalendarWorkflowId, bizDate);
    insertChildWorkflowRequest(
        tenantId, requestId + "-child-other-calendar", otherCalendarParentRun, bizDate);

    int count = triggerRequestMapper.countUnlaunchedForCalendarBizDate(
        tenantId, "CAL_A", bizDate, List.of("PENDING", "PROCESSING", "ACCEPTED", "WAITING"));

    assertThat(count).isEqualTo(3);
  }

  private void insertBatchDayTriggerRequest(
      Long workflowDefinitionId, String suffix, LocalDate bizDate, String status) {
    final String insertTriggerRequestSql = """
    insert into batch.trigger_request (
    tenant_id, request_id, trigger_type, workflow_definition_id, biz_date, dedup_key,
    request_status, trace_id
    ) values (?, ?, 'SCHEDULED', ?, ?, ?, ?, ?)
    """;

    String id = requestId + "-" + suffix;
    jdbcTemplate.update(
        insertTriggerRequestSql,
        tenantId,
        id,
        workflowDefinitionId,
        bizDate,
        "dedup-" + id,
        status,
        "trace-" + id);
  }

  @Test
  @DisplayName("多次重试后查询启动上下文时返回最新一次尝试的实例,并带出所属请求与去重键")
  void shouldReturnLatestAttemptProjection_whenSelectingLaunchContext() {
    final String insertJobDefinitionSql = """
          insert into batch.job_definition (
              tenant_id, job_code, job_name, job_type, schedule_type, timezone, version, enabled
          ) values (?, 'launch-context-job', 'launch context job', 'GENERAL', 'MANUAL', 'UTC', 1, true)
          returning id
          """;
    final String insertTriggerRequestSql = """
          insert into batch.trigger_request (
              tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
              dedup_key, request_status, trace_id
          ) values (?, ?, 'MANUAL', ?, current_date, ?, 'ACCEPTED', 'trigger-trace')
          returning id
          """;

    String dedupKey = "dedup-" + requestId;
    Long workflowDefinitionId = insertWorkflowDefinition(tenantId, "CAL_LAUNCH_CONTEXT");
    Long jobDefinitionId =
        jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, tenantId);
    Long triggerRequestId = jdbcTemplate.queryForObject(
        insertTriggerRequestSql, Long.class, tenantId, requestId, workflowDefinitionId, dedupKey);
    insertJobInstance(jobDefinitionId, triggerRequestId, dedupKey, 1, "instance-1", "trace-1");
    Long latestId =
        insertJobInstance(jobDefinitionId, triggerRequestId, dedupKey, 2, "instance-2", "trace-2");

    TriggerLaunchPersistenceContext context =
        triggerRequestMapper.selectLaunchPersistenceContext(tenantId, requestId);

    assertThat(context).isNotNull();
    assertThat(context.getTriggerRequest().getId()).isEqualTo(triggerRequestId);
    assertThat(context.getTriggerRequest().getDedupKey()).isEqualTo(dedupKey);
    assertThat(context.getExistingInstance().getId()).isEqualTo(latestId);
    assertThat(context.getExistingInstance().getTriggerRequestId()).isEqualTo(triggerRequestId);
    assertThat(context.getExistingInstance().getInstanceNo()).isEqualTo("instance-2");
    assertThat(context.getExistingInstance().getTraceId()).isEqualTo("trace-2");
  }

  @Test
  @DisplayName("连续性按根 Workflow 计量:处理中无根告警,人工审批排除,无 Job 的根创建后解除告警")
  void shouldMeasureRootLaunchBacklogAndLatency_withoutDependingOnChildJobs() {
    String updateRequestStatusSql = """
        update batch.trigger_request set request_status = ? where tenant_id = ? and id = ?
        """;
    String insertPendingApprovalSql = """
        insert into batch.trigger_misfire_pending (
            tenant_id, workflow_definition_id, scheduled_fire_time, catch_up_request_id, expires_at)
        values (?, ?, ?, ?, current_timestamp + interval '1 day')
        """;
    String deletePendingApprovalSql = """
        delete from batch.trigger_misfire_pending where tenant_id = ?
        """;
    String insertRootRunSql = """
        insert into batch.workflow_run (
            tenant_id, workflow_definition_id,
            trigger_request_id, biz_date, run_status, created_at, updated_at)
        values (?, ?, ?, current_date, ?, ?, ?)
        returning id
        """;
    String countChildJobsSql = """
        select count(*) from batch.job_instance where tenant_id = ?
        """;
    Instant requestTime = Instant.parse("9999-12-30T00:00:00Z");
    Instant recentWindow = requestTime.minusSeconds(1);
    Instant staleBefore = Instant.now().minusSeconds(900);
    TriggerLaunchContinuityMetrics baseline =
        triggerRequestMapper.selectLaunchContinuityMetrics(staleBefore, recentWindow);
    Long workflowId = insertWorkflowDefinition(tenantId, "CAL_ROOT_METRICS");
    Long queuedRequest = insertAcceptedRequest(
        tenantId, workflowId, "processing", "root-processing", Instant.now().minusSeconds(7_200));
    jdbcTemplate.update(updateRequestStatusSql, "PROCESSING", tenantId, queuedRequest);
    Long pendingRequest = insertAcceptedRequest(
        tenantId, workflowId, "approval", "root-approval", Instant.now().minusSeconds(7_200));
    Long recentRequest =
        insertAcceptedRequest(tenantId, workflowId, "recent", "root-recent", requestTime);
    Timestamp scheduledFireTime = Timestamp.from(Instant.now().minusSeconds(7_200));
    try {
      jdbcTemplate.update(
          insertPendingApprovalSql, tenantId, workflowId, scheduledFireTime, pendingRequest);
      TriggerLaunchContinuityMetrics beforeRoot =
          triggerRequestMapper.selectLaunchContinuityMetrics(staleBefore, recentWindow);
      assertThat(beforeRoot.staleAcceptedCount()).isEqualTo(baseline.staleAcceptedCount() + 1);
      assertThat(beforeRoot.recentLaunchedCount()).isEqualTo(baseline.recentLaunchedCount());

      Instant oldRootTime = Instant.now().minusSeconds(3_600);
      Timestamp oldRootCreatedAt = Timestamp.from(oldRootTime);
      Long queuedRoot = jdbcTemplate.queryForObject(
          insertRootRunSql,
          Long.class,
          tenantId,
          workflowId,
          queuedRequest,
          "SUCCESS",
          oldRootCreatedAt,
          oldRootCreatedAt);
      Instant recentRootTime = requestTime.plusSeconds(12);
      Timestamp recentRootCreatedAt = Timestamp.from(recentRootTime);
      Long recentRoot = jdbcTemplate.queryForObject(
          insertRootRunSql,
          Long.class,
          tenantId,
          workflowId,
          recentRequest,
          "RUNNING",
          recentRootCreatedAt,
          recentRootCreatedAt);
      assertThat(queuedRoot).isNotNull();
      assertThat(recentRoot).isNotNull();
      TriggerLaunchContinuityMetrics recovered =
          triggerRequestMapper.selectLaunchContinuityMetrics(staleBefore, recentWindow);
      assertThat(recovered.staleAcceptedCount()).isEqualTo(baseline.staleAcceptedCount());
      assertThat(recovered.recentLaunchedCount()).isEqualTo(baseline.recentLaunchedCount() + 1);
      assertThat(recovered.requestToInstanceP95Seconds()).isEqualTo(12.0);
      Long childJobCount = jdbcTemplate.queryForObject(countChildJobsSql, Long.class, tenantId);
      assertThat(childJobCount).isZero();
      TriggerLaunchContinuityMetrics repeated =
          triggerRequestMapper.selectLaunchContinuityMetrics(staleBefore, recentWindow);
      assertThat(repeated.staleAcceptedCount()).isEqualTo(recovered.staleAcceptedCount());
      assertThat(repeated.recentLaunchedCount()).isEqualTo(recovered.recentLaunchedCount());
      assertThat(repeated.requestToInstanceP95Seconds())
          .isEqualTo(recovered.requestToInstanceP95Seconds());
    } finally {
      jdbcTemplate.update(deletePendingApprovalSql, tenantId);
    }
  }

  private Long insertAcceptedRequest(
      String metricsTenant,
      Long workflowDefinitionId,
      String key,
      String dedupKey,
      Instant createdAt) {
    final String insertTriggerRequestSql = """
    insert into batch.trigger_request (
    tenant_id, request_id, trigger_type, workflow_definition_id, biz_date,
    dedup_key, request_status, created_at, updated_at
    ) values (?, ?, 'MANUAL', ?, current_date, ?, 'ACCEPTED', ?, ?)
    returning id
    """;

    return jdbcTemplate.queryForObject(
        insertTriggerRequestSql,
        Long.class,
        metricsTenant,
        "metrics-" + key,
        workflowDefinitionId,
        dedupKey,
        Timestamp.from(createdAt),
        Timestamp.from(createdAt));
  }

  private Long insertWorkflowDefinition(String workflowTenant, String calendarCode) {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')\s\
        on conflict (tenant_id) do nothing\
        """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, calendar_code)\s\
        values (?, ?, ?, ?) returning id\
        """;

    jdbcTemplate.update(insertTenantSql, workflowTenant, workflowTenant);
    return jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        workflowTenant,
        "workflow-" + requestId + "-" + calendarCode,
        "Mapper integration workflow",
        calendarCode);
  }

  private Long insertJobInstance(
      Long jobDefinitionId,
      Long triggerRequestId,
      String dedupKey,
      int runAttempt,
      String instanceNo,
      String traceId) {
    return insertJobInstance(
        tenantId, jobDefinitionId, triggerRequestId, dedupKey, runAttempt, instanceNo, traceId);
  }

  private Long insertJobInstance(
      String instanceTenant,
      Long jobDefinitionId,
      Long triggerRequestId,
      String dedupKey,
      int runAttempt,
      String instanceNo,
      String traceId) {
    final String insertJobInstanceSql = """
    insert into batch.job_instance (
    tenant_id, job_definition_id, trigger_request_id, job_code, instance_no,
    biz_date, trigger_type, instance_status, worker_group, dedup_key,
    run_attempt, trace_id
    ) values (?, ?, ?, 'launch-context-job', ?, current_date, 'MANUAL',
    'SUCCESS', 'default', ?, ?, ?)
    returning id
    """;

    return jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        instanceTenant,
        jobDefinitionId,
        triggerRequestId,
        instanceNo,
        dedupKey,
        runAttempt,
        traceId);
  }

  private Long insertJobDefinition(String definitionTenant) {
    final String insertJobDefinitionSql = """
        insert into batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone,\s\
        version, enabled) values (?, 'launch-context-job', 'Launch context job', 'GENERAL', 'MANUAL', 'UTC', 1,\s\
        true) returning id\
        """;

    return jdbcTemplate.queryForObject(insertJobDefinitionSql, Long.class, definitionTenant);
  }
}
