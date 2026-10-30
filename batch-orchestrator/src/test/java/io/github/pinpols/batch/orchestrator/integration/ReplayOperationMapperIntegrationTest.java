package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayReplaySessionUpdateCountsParam;
import io.github.pinpols.batch.orchestrator.domain.param.ReplayOperationStartParam;
import io.github.pinpols.batch.orchestrator.domain.param.ReplayOperationWorkflowOutcomeParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplaySessionStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationPreviewTokenMapper;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Replay operation PostgreSQL mapper: 父子身份和终态汇总")
class ReplayOperationMapperIntegrationTest {

  private static final String RLS_ROLE = "replay_operation_rls_test";

  @Container
  @SuppressWarnings("resource")
  private static final PostgreSQLContainer POSTGRES = TestPostgresContainers.platform();

  @Test
  @DisplayName("operation mapper 能创建子 session 并从 PostgreSQL 聚合终态")
  void shouldPersistParentLinkAndAggregateChildTerminalState() throws Exception {
    Flyway flyway = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load();
    flyway.migrate();

    try (SingleConnectionDataSource adminDataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      JdbcTemplate adminJdbc = new JdbcTemplate(adminDataSource);
      final String createRoleSql = """
          DO $$ BEGIN
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'replay_operation_rls_test') THEN
              CREATE ROLE replay_operation_rls_test NOSUPERUSER NOBYPASSRLS;
            END IF;
          END $$
          """;
      adminJdbc.execute(createRoleSql);
      final String grantSchemaSql = "GRANT USAGE ON SCHEMA batch TO " + RLS_ROLE;
      adminJdbc.execute(grantSchemaSql);
      final String grantTablesSql =
          "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA batch TO " + RLS_ROLE;
      adminJdbc.execute(grantTablesSql);
      final String grantSequencesSql =
          "GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA batch TO " + RLS_ROLE;
      adminJdbc.execute(grantSequencesSql);
    }

    try (SingleConnectionDataSource dataSource = new SingleConnectionDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), true)) {
      String tenantId = "replay-mapper-test";
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      final String setRoleSql = "SET ROLE " + RLS_ROLE;
      jdbc.execute(setRoleSql);
      final String updateChildStatusSql = """
          update batch.batch_day_replay_session
          set status = 'SUCCEEDED'
          where tenant_id = ? and id = ?
          """;
      SqlSessionFactory sessionFactory = sqlSessionFactory(dataSource);
      try (SqlSession sqlSession = sessionFactory.openSession()) {
        ReplayOperationMapper operationMapper = sqlSession.getMapper(ReplayOperationMapper.class);
        String appliedTenantContext = operationMapper.setTenantContext(tenantId);
        assertThat(appliedTenantContext).isEqualTo(tenantId);
        BatchDayReplaySessionMapper childMapper =
            sqlSession.getMapper(BatchDayReplaySessionMapper.class);
        Instant now = Instant.parse("2026-10-22T12:00:00Z");
        String operationKey = UUID.randomUUID().toString();
        ReplayOperationEntity operation = ReplayOperationEntity.builder()
            .tenantId(tenantId)
            .operationKey(operationKey)
            .operationType("BATCH_DAY_RANGE")
            .status("PENDING_APPROVAL")
            .calendarCode("CAL-TEST")
            .fromDate(LocalDate.of(2026, 10, 20))
            .throughDate(LocalDate.of(2026, 10, 22))
            .scope("ALL_FAILED")
            .executionMode("REPLAY")
            .candidateSource("EXISTING_INSTANCES")
            .scopePayload("{}")
            .requestHash("a".repeat(64))
            .snapshotHash("b".repeat(64))
            .snapshotPayload("[]")
            .reason("integration")
            .requestedBy("operator")
            .totalCount(1)
            .succeededCount(0)
            .failedCount(0)
            .inFlightCount(1)
            .createdAt(now)
            .updatedAt(now)
            .build();
        operationMapper.insert(operation);
        ReplayOperationEntity persistedOperation =
            operationMapper.selectByIdentity(tenantId, operationKey);
        assertThat(persistedOperation).isNotNull();
        List<ReplayOperationEntity> recentOperations =
            operationMapper.selectRecentByTenant(tenantId, 10);
        assertThat(recentOperations)
            .extracting(ReplayOperationEntity::id)
            .containsExactly(persistedOperation.id());

        String otherTenantId = "replay-mapper-other-tenant";
        operationMapper.setTenantContext(otherTenantId);
        ReplayOperationEntity crossTenantOperation =
            operationMapper.selectById(tenantId, persistedOperation.id());
        assertThat(crossTenantOperation).isNull();
        operationMapper.setTenantContext(tenantId);

        BatchDayReplaySessionEntity child = BatchDayReplaySessionEntity.builder()
            .tenantId(tenantId)
            .replayOperationId(persistedOperation.id())
            .calendarCode("CAL-TEST")
            .bizDate(LocalDate.of(2026, 10, 20))
            .scope("ALL_FAILED")
            .executionMode("REPLAY")
            .candidateSource("EXISTING_INSTANCES")
            .scopePayload("{}")
            .resultPolicy("CREATE_NEW_VERSION")
            .configVersionPolicy("USE_ORIGINAL_CONFIG")
            .reason("integration")
            .status("PENDING_APPROVAL")
            .totalCount(1)
            .succeededCount(0)
            .failedCount(0)
            .inFlightCount(1)
            .requestedBy("operator")
            .createdAt(now)
            .updatedAt(now)
            .build();
        childMapper.insert(child);
        BatchDayReplaySessionEntity persistedChild = childMapper.selectActiveByCalendarBizDate(
            tenantId, "CAL-TEST", LocalDate.of(2026, 10, 20));
        assertThat(persistedChild).isNotNull();
        assertThat(persistedChild.replayOperationId()).isEqualTo(persistedOperation.id());
        List<BatchDayReplaySessionEntity> childSessions =
            childMapper.selectByReplayOperation(tenantId, persistedOperation.id());
        assertThat(childSessions)
            .extracting(BatchDayReplaySessionEntity::id)
            .containsExactly(persistedChild.id());
        List<Long> childSessionIds =
            operationMapper.selectChildSessionIds(tenantId, persistedOperation.id());
        assertThat(childSessionIds).containsExactly(persistedChild.id());

        int updatedChildCount = jdbc.update(updateChildStatusSql, tenantId, persistedChild.id());
        assertThat(updatedChildCount).isEqualTo(1);
        int refreshedOperationCount = operationMapper.refreshFromChildSessions(
            tenantId, persistedOperation.id(), now.plusSeconds(1));
        assertThat(refreshedOperationCount).isEqualTo(1);
        ReplayOperationEntity completedOperation =
            operationMapper.selectById(tenantId, persistedOperation.id());
        assertThat(completedOperation.status()).isEqualTo("SUCCEEDED");
        assertThat(completedOperation.totalCount()).isEqualTo(1);
        assertThat(completedOperation.succeededCount()).isEqualTo(1);
        assertThat(completedOperation.inFlightCount()).isZero();

        String nodeOperationKey = UUID.randomUUID().toString();
        String targetRequestId = "node-replay-request";
        ReplayOperationEntity nodeOperation = ReplayOperationEntity.builder()
            .tenantId(tenantId)
            .operationKey(nodeOperationKey)
            .operationType("WORKFLOW_NODE")
            .status("RUNNING")
            .scope("FAILED_NODE")
            .executionMode("REPLAY")
            .candidateSource("EXACT_SOURCE_NODE")
            .scopePayload("{}")
            .sourceWorkflowRunId(700L)
            .sourceWorkflowNodeRunId(701L)
            .sourceJobInstanceId(702L)
            .targetRequestId(targetRequestId)
            .requestHash("c".repeat(64))
            .snapshotHash("d".repeat(64))
            .snapshotPayload("{}")
            .reason("exact node replay")
            .requestedBy("operator")
            .totalCount(1)
            .succeededCount(0)
            .failedCount(0)
            .inFlightCount(1)
            .startedAt(now)
            .createdAt(now)
            .updatedAt(now)
            .build();
        operationMapper.insert(nodeOperation);
        ReplayOperationEntity persistedNodeOperation =
            operationMapper.selectByIdentity(tenantId, nodeOperationKey);
        ReplayOperationEntity operationByTargetRequest =
            operationMapper.selectByTargetRequest(tenantId, targetRequestId);
        assertThat(operationByTargetRequest.id()).isEqualTo(persistedNodeOperation.id());

        int attachedRunCount = operationMapper.attachTargetWorkflowRun(
            tenantId, targetRequestId, 900L, now.plusSeconds(2));
        assertThat(attachedRunCount).isEqualTo(1);
        int attachedSameRunCount = operationMapper.attachTargetWorkflowRun(
            tenantId, targetRequestId, 900L, now.plusSeconds(3));
        assertThat(attachedSameRunCount).isEqualTo(1);

        ReplayOperationWorkflowOutcomeParam terminalOutcome =
            ReplayOperationWorkflowOutcomeParam.builder()
                .tenantId(tenantId)
                .workflowRunId(900L)
                .status("SUCCEEDED")
                .succeededCount(1)
                .failedCount(0)
                .inFlightCount(0)
                .now(now.plusSeconds(4))
                .build();
        int completedTargetCount = operationMapper.updateWorkflowTargetOutcome(terminalOutcome);
        assertThat(completedTargetCount).isEqualTo(1);
        int duplicateTerminalCount = operationMapper.updateWorkflowTargetOutcome(terminalOutcome);
        assertThat(duplicateTerminalCount).isZero();
        ReplayOperationEntity completedNodeOperation =
            operationMapper.selectById(tenantId, persistedNodeOperation.id());
        assertThat(completedNodeOperation.status()).isEqualTo("SUCCEEDED");
        assertThat(completedNodeOperation.targetWorkflowRunId()).isEqualTo(900L);
        assertThat(completedNodeOperation.succeededCount()).isEqualTo(1);
        assertThat(completedNodeOperation.inFlightCount()).isZero();

        String pendingOperationKey = UUID.randomUUID().toString();
        ReplayOperationEntity pendingNodeOperation = ReplayOperationEntity.builder()
            .tenantId(tenantId)
            .operationKey(pendingOperationKey)
            .operationType("WORKFLOW_NODE")
            .status("PENDING_APPROVAL")
            .scope("FAILED_NODE")
            .executionMode("REPLAY")
            .candidateSource("EXACT_SOURCE_NODE")
            .scopePayload("{}")
            .sourceWorkflowRunId(800L)
            .sourceWorkflowNodeRunId(801L)
            .sourceJobInstanceId(802L)
            .requestHash("e".repeat(64))
            .snapshotHash("f".repeat(64))
            .snapshotPayload("{}")
            .reason("approval rejected")
            .requestedBy("operator")
            .totalCount(1)
            .succeededCount(0)
            .failedCount(0)
            .inFlightCount(1)
            .createdAt(now)
            .updatedAt(now)
            .build();
        operationMapper.insert(pendingNodeOperation);
        ReplayOperationEntity persistedPendingOperation =
            operationMapper.selectByIdentity(tenantId, pendingOperationKey);
        assertThat(operationMapper.rejectPendingApproval(
                tenantId, persistedPendingOperation.id(), now.plusSeconds(5)))
            .isEqualTo(1);
        assertThat(operationMapper.rejectPendingApproval(
                tenantId, persistedPendingOperation.id(), now.plusSeconds(6)))
            .isZero();
        ReplayOperationEntity rejectedOperation =
            operationMapper.selectById(tenantId, persistedPendingOperation.id());
        assertThat(rejectedOperation.status()).isEqualTo("REJECTED");
        assertThat(rejectedOperation.inFlightCount()).isZero();
        assertThat(rejectedOperation.completedAt()).isEqualTo(now.plusSeconds(5));

        String startOperationKey = UUID.randomUUID().toString();
        ReplayOperationEntity startNodeOperation = pendingNodeOperation.toBuilder()
            .operationKey(startOperationKey)
            .sourceWorkflowRunId(810L)
            .sourceWorkflowNodeRunId(811L)
            .sourceJobInstanceId(812L)
            .createdAt(now)
            .updatedAt(now)
            .build();
        operationMapper.insert(startNodeOperation);
        ReplayOperationEntity persistedStartOperation =
            operationMapper.selectByIdentity(tenantId, startOperationKey);
        ReplayOperationStartParam startParam = ReplayOperationStartParam.builder()
            .tenantId(tenantId)
            .operationId(persistedStartOperation.id())
            .targetRequestId("approved-node-replay-request")
            .approver("approver")
            .now(now.plusSeconds(7))
            .build();
        int startedCount = operationMapper.startApprovedWorkflowNodeReplay(startParam);
        int duplicateStartedCount = operationMapper.startApprovedWorkflowNodeReplay(startParam);
        assertThat(startedCount).isEqualTo(1);
        assertThat(duplicateStartedCount).isZero();
        ReplayOperationEntity startedOperation =
            operationMapper.selectById(tenantId, persistedStartOperation.id());
        assertThat(startedOperation.status()).isEqualTo("RUNNING");
        assertThat(startedOperation.targetRequestId()).isEqualTo("approved-node-replay-request");
        assertThat(startedOperation.approvedBy()).isEqualTo("approver");

        String startWorkflowRunOperationKey = UUID.randomUUID().toString();
        ReplayOperationEntity pendingWorkflowRun = pendingNodeOperation.toBuilder()
            .operationKey(startWorkflowRunOperationKey)
            .operationType("WORKFLOW_RUN")
            .scope("FULL_WORKFLOW")
            .sourceWorkflowRunId(820L)
            .sourceWorkflowNodeRunId(null)
            .sourceJobInstanceId(null)
            .createdAt(now)
            .updatedAt(now)
            .build();
        operationMapper.insert(pendingWorkflowRun);
        ReplayOperationEntity persistedWorkflowRun =
            operationMapper.selectByIdentity(tenantId, startWorkflowRunOperationKey);
        ReplayOperationStartParam workflowStartParam = ReplayOperationStartParam.builder()
            .tenantId(tenantId)
            .operationId(persistedWorkflowRun.id())
            .targetRequestId("approved-workflow-replay-request")
            .approver("workflow-approver")
            .now(now.plusSeconds(8))
            .build();
        int startedWorkflowCount =
            operationMapper.startApprovedWorkflowRunReplay(workflowStartParam);
        int duplicateStartedWorkflowCount =
            operationMapper.startApprovedWorkflowRunReplay(workflowStartParam);
        assertThat(startedWorkflowCount).isEqualTo(1);
        assertThat(duplicateStartedWorkflowCount).isZero();
        ReplayOperationEntity startedWorkflowOperation =
            operationMapper.selectById(tenantId, persistedWorkflowRun.id());
        assertThat(startedWorkflowOperation.status()).isEqualTo("RUNNING");
        assertThat(startedWorkflowOperation.targetRequestId())
            .isEqualTo("approved-workflow-replay-request");
        assertThat(startedWorkflowOperation.approvedBy()).isEqualTo("workflow-approver");
      }
    }
  }

  @Test
  @DisplayName("日期区间父操作汇总多日部分失败、重试恢复和最终成功")
  void shouldAggregateMultiDayPartialFailureAndRecovery() throws Exception {
    Flyway flyway = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load();
    flyway.migrate();

    String tenantId = "replay-range-aggregation-test";
    String operationKey = UUID.randomUUID().toString();
    String calendarCode = "CAL-RANGE-AGGREGATION";
    LocalDate firstBizDate = LocalDate.of(2026, 10, 20);
    LocalDate secondBizDate = firstBizDate.plusDays(1);
    Instant startedAt = Instant.parse("2026-10-23T12:00:00Z");
    Instant partialFailedAt = startedAt.plusSeconds(1);
    Instant retryStartedAt = startedAt.plusSeconds(2);
    Instant retryCompletedAt = startedAt.plusSeconds(3);
    DataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    SqlSessionFactory sessionFactory = sqlSessionFactory(dataSource);

    try (SqlSession session = sessionFactory.openSession(false)) {
      ReplayOperationMapper operationMapper = session.getMapper(ReplayOperationMapper.class);
      BatchDayReplaySessionMapper childMapper =
          session.getMapper(BatchDayReplaySessionMapper.class);
      String appliedTenantContext = operationMapper.setTenantContext(tenantId);
      assertThat(appliedTenantContext).isEqualTo(tenantId);

      ReplayOperationEntity operation =
          rangeOperation(tenantId, operationKey, startedAt).toBuilder()
              .calendarCode(calendarCode)
              .fromDate(firstBizDate)
              .throughDate(secondBizDate)
              .status("RUNNING")
              .totalCount(2)
              .inFlightCount(2)
              .startedAt(startedAt)
              .build();
      operationMapper.insert(operation);
      ReplayOperationEntity persistedOperation =
          operationMapper.selectByIdentity(tenantId, operationKey);
      assertThat(persistedOperation).isNotNull();

      BatchDayReplaySessionEntity succeededChild = BatchDayReplaySessionEntity.builder()
          .tenantId(tenantId)
          .replayOperationId(persistedOperation.id())
          .calendarCode(calendarCode)
          .bizDate(firstBizDate)
          .scope("ALL_FAILED")
          .executionMode("REPLAY")
          .candidateSource("EXISTING_INSTANCES")
          .scopePayload("{}")
          .resultPolicy("CREATE_NEW_VERSION")
          .configVersionPolicy("USE_ORIGINAL_CONFIG")
          .reason("integration")
          .status("SUCCEEDED")
          .totalCount(1)
          .succeededCount(1)
          .failedCount(0)
          .inFlightCount(0)
          .requestedBy("operator")
          .startedAt(startedAt)
          .completedAt(partialFailedAt)
          .createdAt(startedAt)
          .updatedAt(partialFailedAt)
          .build();
      BatchDayReplaySessionEntity partialFailedChild = BatchDayReplaySessionEntity.builder()
          .tenantId(tenantId)
          .replayOperationId(persistedOperation.id())
          .calendarCode(calendarCode)
          .bizDate(secondBizDate)
          .scope("ALL_FAILED")
          .executionMode("REPLAY")
          .candidateSource("EXISTING_INSTANCES")
          .scopePayload("{}")
          .resultPolicy("CREATE_NEW_VERSION")
          .configVersionPolicy("USE_ORIGINAL_CONFIG")
          .reason("integration")
          .status("PARTIAL_FAILED")
          .totalCount(1)
          .succeededCount(0)
          .failedCount(1)
          .inFlightCount(0)
          .requestedBy("operator")
          .startedAt(startedAt)
          .completedAt(partialFailedAt)
          .createdAt(startedAt)
          .updatedAt(partialFailedAt)
          .build();
      childMapper.insert(succeededChild);
      childMapper.insert(partialFailedChild);
      session.commit();

      operationMapper.setTenantContext(tenantId);
      int partialRefreshCount = operationMapper.refreshFromChildSessions(
          tenantId, persistedOperation.id(), partialFailedAt);
      assertThat(partialRefreshCount).isEqualTo(1);
      ReplayOperationEntity partialFailedOperation =
          operationMapper.selectById(tenantId, persistedOperation.id());
      assertThat(partialFailedOperation.status()).isEqualTo("PARTIAL_FAILED");
      assertThat(partialFailedOperation.totalCount()).isEqualTo(2);
      assertThat(partialFailedOperation.succeededCount()).isEqualTo(1);
      assertThat(partialFailedOperation.failedCount()).isEqualTo(1);
      assertThat(partialFailedOperation.inFlightCount()).isZero();
      assertThat(partialFailedOperation.completedAt()).isEqualTo(partialFailedAt);
      session.commit();

      operationMapper.setTenantContext(tenantId);
      List<BatchDayReplaySessionEntity> childSessions =
          childMapper.selectByReplayOperation(tenantId, persistedOperation.id());
      assertThat(childSessions)
          .extracting(BatchDayReplaySessionEntity::bizDate)
          .containsExactly(firstBizDate, secondBizDate);
      BatchDayReplaySessionEntity failedSessionForRetry = childSessions.get(1);
      int reopenedChildCount = childMapper.reopenPartialFailedForRetry(
          tenantId, failedSessionForRetry.id(), retryStartedAt);
      assertThat(reopenedChildCount).isEqualTo(1);
      int resetChildCounts =
          childMapper.updateCounts(BatchDayReplaySessionUpdateCountsParam.builder()
              .tenantId(tenantId)
              .id(failedSessionForRetry.id())
              .succeededCount(0)
              .failedCount(0)
              .inFlightCount(1)
              .totalCount(1)
              .now(retryStartedAt)
              .build());
      assertThat(resetChildCounts).isEqualTo(1);
      int runningRefreshCount = operationMapper.refreshFromChildSessions(
          tenantId, persistedOperation.id(), retryStartedAt);
      assertThat(runningRefreshCount).isEqualTo(1);
      ReplayOperationEntity runningOperation =
          operationMapper.selectById(tenantId, persistedOperation.id());
      assertThat(runningOperation.status()).isEqualTo("RUNNING");
      assertThat(runningOperation.totalCount()).isEqualTo(2);
      assertThat(runningOperation.succeededCount()).isEqualTo(1);
      assertThat(runningOperation.failedCount()).isZero();
      assertThat(runningOperation.inFlightCount()).isEqualTo(1);
      assertThat(runningOperation.completedAt()).isNull();
      session.commit();

      operationMapper.setTenantContext(tenantId);
      int completedChildCounts =
          childMapper.updateCounts(BatchDayReplaySessionUpdateCountsParam.builder()
              .tenantId(tenantId)
              .id(failedSessionForRetry.id())
              .succeededCount(1)
              .failedCount(0)
              .inFlightCount(0)
              .totalCount(1)
              .now(retryCompletedAt)
              .build());
      assertThat(completedChildCounts).isEqualTo(1);
      UpdateBatchDayReplaySessionStatusParam completedChildStatus =
          UpdateBatchDayReplaySessionStatusParam.completed(
              tenantId,
              failedSessionForRetry.id(),
              "SUCCEEDED",
              List.of("RUNNING"),
              retryCompletedAt);
      int completedChildStatusCount = childMapper.updateStatus(completedChildStatus);
      assertThat(completedChildStatusCount).isEqualTo(1);
      int succeededRefreshCount = operationMapper.refreshFromChildSessions(
          tenantId, persistedOperation.id(), retryCompletedAt);
      assertThat(succeededRefreshCount).isEqualTo(1);
      ReplayOperationEntity succeededOperation =
          operationMapper.selectById(tenantId, persistedOperation.id());
      assertThat(succeededOperation.status()).isEqualTo("SUCCEEDED");
      assertThat(succeededOperation.totalCount()).isEqualTo(2);
      assertThat(succeededOperation.succeededCount()).isEqualTo(2);
      assertThat(succeededOperation.failedCount()).isZero();
      assertThat(succeededOperation.inFlightCount()).isZero();
      assertThat(succeededOperation.completedAt()).isEqualTo(retryCompletedAt);
      session.commit();
    }
  }

  @Test
  @DisplayName("并发提交同一预览按 token 行锁收敛，事务失败回滚父操作与凭证消费")
  void shouldSerializeConcurrentPreviewConsumptionAndRollbackAtomically() throws Exception {
    Flyway flyway = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("batch", "quartz")
        .defaultSchema("batch")
        .locations("classpath:db/migration")
        .configuration(Map.of("flyway.postgresql.transactional.lock", "false"))
        .load();
    flyway.migrate();

    DriverManagerDataSource dataSource = new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    SqlSessionFactory sessionFactory = sqlSessionFactory(dataSource);
    String tenantId = "replay-concurrent-test";
    String operationKey = UUID.randomUUID().toString();
    String tokenHash = "e".repeat(64);
    Instant now = Instant.parse("2026-10-23T12:00:00Z");
    ReplayOperationPreviewTokenEntity token = previewToken(tenantId, tokenHash, operationKey, now);
    try (SqlSession session = sessionFactory.openSession(false)) {
      ReplayOperationPreviewTokenMapper tokenMapper =
          session.getMapper(ReplayOperationPreviewTokenMapper.class);
      tokenMapper.setTenantContext(tenantId);
      tokenMapper.insert(token);
      session.commit();
    }

    CountDownLatch firstTransactionLockedToken = new CountDownLatch(1);
    CountDownLatch allowFirstTransactionCommit = new CountDownLatch(1);
    CountDownLatch secondTransactionStarted = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Long> firstOperationId = executor.submit(() -> {
        try (SqlSession session = sessionFactory.openSession(false)) {
          ReplayOperationMapper operationMapper = session.getMapper(ReplayOperationMapper.class);
          ReplayOperationPreviewTokenMapper tokenMapper =
              session.getMapper(ReplayOperationPreviewTokenMapper.class);
          operationMapper.setTenantContext(tenantId);
          tokenMapper.setTenantContext(tenantId);
          ReplayOperationPreviewTokenEntity lockedToken =
              tokenMapper.selectForUpdate(tenantId, tokenHash);
          assertThat(lockedToken).isNotNull();
          firstTransactionLockedToken.countDown();
          if (!allowFirstTransactionCommit.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("timed out waiting to commit first replay transaction");
          }
          int consumedCount = tokenMapper.consume(tenantId, tokenHash, now);
          assertThat(consumedCount).isEqualTo(1);
          operationMapper.insert(rangeOperation(tenantId, operationKey, now));
          ReplayOperationEntity persisted =
              operationMapper.selectByIdentity(tenantId, operationKey);
          tokenMapper.linkOperation(tenantId, tokenHash, persisted.id());
          session.commit();
          return persisted.id();
        }
      });
      assertThat(firstTransactionLockedToken.await(5, TimeUnit.SECONDS)).isTrue();

      Future<Long> secondOperationId = executor.submit(() -> {
        try (SqlSession session = sessionFactory.openSession(false)) {
          ReplayOperationMapper operationMapper = session.getMapper(ReplayOperationMapper.class);
          ReplayOperationPreviewTokenMapper tokenMapper =
              session.getMapper(ReplayOperationPreviewTokenMapper.class);
          operationMapper.setTenantContext(tenantId);
          tokenMapper.setTenantContext(tenantId);
          ReplayOperationEntity existing = operationMapper.selectByIdentity(tenantId, operationKey);
          if (existing != null) {
            session.commit();
            return existing.id();
          }
          secondTransactionStarted.countDown();
          ReplayOperationPreviewTokenEntity consumedToken =
              tokenMapper.selectForUpdate(tenantId, tokenHash);
          assertThat(consumedToken.consumedAt()).isEqualTo(now);
          ReplayOperationEntity persisted =
              operationMapper.selectById(tenantId, consumedToken.operationId());
          session.commit();
          return persisted.id();
        }
      });
      assertThat(secondTransactionStarted.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(secondOperationId.isDone()).isFalse();
      allowFirstTransactionCommit.countDown();
      Long firstId = firstOperationId.get(5, TimeUnit.SECONDS);
      Long secondId = secondOperationId.get(5, TimeUnit.SECONDS);
      assertThat(secondId).isEqualTo(firstId);
      final String countOperationsByIdentitySql = """
          select count(*) from batch.replay_operation where tenant_id = ? and operation_key = ?::uuid
          """;
      JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
      Integer operationCount = jdbcTemplate.queryForObject(
          countOperationsByIdentitySql, Integer.class, tenantId, operationKey);
      assertThat(operationCount).isEqualTo(1);

      String rollbackOperationKey = UUID.randomUUID().toString();
      String rollbackTokenHash = "f".repeat(64);
      ReplayOperationPreviewTokenEntity rollbackToken =
          previewToken(tenantId, rollbackTokenHash, rollbackOperationKey, now);
      try (SqlSession session = sessionFactory.openSession(false)) {
        ReplayOperationMapper operationMapper = session.getMapper(ReplayOperationMapper.class);
        ReplayOperationPreviewTokenMapper tokenMapper =
            session.getMapper(ReplayOperationPreviewTokenMapper.class);
        operationMapper.setTenantContext(tenantId);
        tokenMapper.setTenantContext(tenantId);
        tokenMapper.insert(rollbackToken);
        tokenMapper.consume(tenantId, rollbackTokenHash, now);
        operationMapper.insert(rangeOperation(tenantId, rollbackOperationKey, now));
        session.rollback();
      }
      try (SqlSession session = sessionFactory.openSession(false)) {
        ReplayOperationMapper operationMapper = session.getMapper(ReplayOperationMapper.class);
        ReplayOperationPreviewTokenMapper tokenMapper =
            session.getMapper(ReplayOperationPreviewTokenMapper.class);
        operationMapper.setTenantContext(tenantId);
        tokenMapper.setTenantContext(tenantId);
        ReplayOperationPreviewTokenEntity uncommittedToken =
            tokenMapper.selectForUpdate(tenantId, rollbackTokenHash);
        ReplayOperationEntity rolledBackOperation =
            operationMapper.selectByIdentity(tenantId, rollbackOperationKey);
        assertThat(uncommittedToken).isNull();
        assertThat(rolledBackOperation).isNull();
        session.rollback();
      }
    } finally {
      allowFirstTransactionCommit.countDown();
      executor.shutdownNow();
    }
  }

  private static ReplayOperationPreviewTokenEntity previewToken(
      String tenantId, String tokenHash, String operationKey, Instant now) {
    return ReplayOperationPreviewTokenEntity.builder()
        .tenantId(tenantId)
        .tokenHash(tokenHash)
        .operationKey(operationKey)
        .requestHash("a".repeat(64))
        .snapshotHash("b".repeat(64))
        .snapshotPayload("{}")
        .expiresAt(now.plusSeconds(300))
        .createdAt(now)
        .build();
  }

  private static ReplayOperationEntity rangeOperation(
      String tenantId, String operationKey, Instant now) {
    return ReplayOperationEntity.builder()
        .tenantId(tenantId)
        .operationKey(operationKey)
        .operationType("BATCH_DAY_RANGE")
        .status("PENDING_APPROVAL")
        .calendarCode("CAL-TEST")
        .fromDate(LocalDate.of(2026, 10, 20))
        .throughDate(LocalDate.of(2026, 10, 20))
        .scope("ALL_FAILED")
        .executionMode("REPLAY")
        .candidateSource("EXISTING_INSTANCES")
        .scopePayload("{}")
        .requestHash("a".repeat(64))
        .snapshotHash("b".repeat(64))
        .snapshotPayload("[]")
        .reason("integration")
        .requestedBy("operator")
        .totalCount(0)
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(0)
        .createdAt(now)
        .updatedAt(now)
        .build();
  }

  private static SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
    Configuration configuration =
        new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
    ClassLoader previousDefaultClassLoader = Resources.getDefaultClassLoader();
    try {
      Resources.setDefaultClassLoader(ReplayOperationMapperIntegrationTest.class.getClassLoader());
      parse(configuration, "/mapper/ReplayOperationMapper.xml");
      parse(configuration, "/mapper/ReplayOperationPreviewTokenMapper.xml");
      parse(configuration, "/mapper/BatchDayReplaySessionMapper.xml");
    } finally {
      Resources.setDefaultClassLoader(previousDefaultClassLoader);
    }
    return new SqlSessionFactoryBuilder().build(configuration);
  }

  private static void parse(Configuration configuration, String resource) throws Exception {
    try (InputStream input =
        ReplayOperationMapperIntegrationTest.class.getResourceAsStream(resource)) {
      XMLMapperBuilder parser =
          new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments());
      parser.parse();
    }
  }
}
