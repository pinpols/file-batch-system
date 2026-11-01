package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eStatusLogger;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControlPayloads.TaskClaimCommand;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskControllerApplicationService;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.TransactionSystemException;

/**
 * 端到端测试：验证真实的 Outbox 轮询器会自动投递消息（不依赖测试手工 publish）。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>覆盖生产形态：由 {@code OutboxPollScheduler} 定时扫描 outbox 表并投递到 Kafka。
 *   <li>避免“测试里手动 publishAllPending()”掩盖 outbox forwarder 没跑/配置错误的风险。
 * </ul>
 *
 * <p>实现方式：把 {@code batch.outbox.poll-interval-millis} 调小到 500ms，并且<strong>刻意不调用</strong> {@code
 * E2eOutboxPublishSupport.publishAllPending()}，仅等待最终任务成功。
 */
@E2eSpringBootTest(
    classes = E2eImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.outbox.poll-interval-millis=500",
      "batch.outbox.min-poll-interval-millis=500",
      "batch.outbox.publishing-timeout-seconds=3"
    })
@E2eBusinessSchema
@Sql(
    scripts = {
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("critical")
@DisplayName("待发事件轮询投递链路:常驻轮询器自动扫描并投递,导入任务无需任何人工投递即达成功终态")
class OutboxForwarderE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  // 多个恢复场景共享业务日，避免后台结算提前关闭测试数据。
  private static final LocalDate BIZ_DATE = LocalDate.now(ZoneOffset.UTC).plusDays(1);

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private final DataSource businessDataSource;

  @MockitoSpyBean
  private TaskControllerApplicationService taskControllerApplicationService;

  @Autowired
  OutboxForwarderE2eIT(@Qualifier("importBusinessDataSource") DataSource businessDataSource) {
    this.businessDataSource = businessDataSource;
  }

  private enum LaunchCommitFaultPoint {
    ROOT,
    TASK_OUTBOX
  }

  @ParameterizedTest
  @EnumSource(LaunchCommitFaultPoint.class)
  @DisplayName("根运行或任务 Outbox 提交前数据库连接被终止,未提交部分回滚且同请求重试恢复唯一业务结果")
  void shouldRecoverSameRequest_whenDatabaseDisconnectsBeforeLaunchCommit(
      LaunchCommitFaultPoint faultPoint) {
    final String selectCountSql5 = """
            select count(*) from biz.customer_account where tenant_id = ? and customer_no = ?
        """;

    final String createFunctionSql = """
        create function batch.e2e_abort_launch_connection() returns trigger language plpgsql as $$
        begin
          perform pg_terminate_backend(pg_backend_pid());
          return new;
        end
        $$
        """;
    final String selectCountSql = """
              select count(*) from batch.workflow_run
              where tenant_id = ? and workflow_definition_id = ?
              """;
    final String selectCountSql2 = """
            select count(*) from batch.job_instance where tenant_id = ? and job_code = ?
        """;
    final String selectCountSql3 = """
              select count(*) from batch.outbox_event
              where tenant_id = ? and event_type = 'IMPORT' and payload_json ->> 'jobCode' = ?
              """;
    final String selectRequestStatusSql = """
            select request_status from batch.trigger_request where tenant_id = ? and request_id = ?
        """;
    final String dropFunctionSql = """
            drop function if exists batch.e2e_abort_launch_connection()
        """;
    final String selectRunStatusSql = """
              select run_status from batch.workflow_run
              where tenant_id = ? and workflow_definition_id = ?
              """;
    final String selectCountSql4 = """
            select count(*) from batch.outbox_event
            where tenant_id = ? and event_type = 'IMPORT' and payload_json ->> 'jobCode' = ?
            """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);
    String customerNo = "TXLOSS" + Long.toUnsignedString(System.nanoTime());
    LaunchRequest request = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        "e2e-launch-commit-loss",
        Map.of(
            "fileFormatType", "JSON",
            "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
            "bizType", "CUSTOMER",
            "content",
                JsonUtils.toJson(List.of(Map.of(
                    "customerNo", customerNo,
                    "customerName", "Launch recovery",
                    "customerType", "PERSONAL",
                    "certificateNo", customerNo,
                    "status", "ACTIVE")))));
    String table = faultPoint == LaunchCommitFaultPoint.ROOT ? "workflow_run" : "outbox_event";
    String predicate = faultPoint == LaunchCommitFaultPoint.ROOT
        ? "new.workflow_definition_id = " + seed.workflowDefinitionId()
        : "new.event_type = 'IMPORT' and new.payload_json ->> 'jobCode' = '" + seed.jobCode() + "'";
    jdbcTemplate.execute(createFunctionSql);
    try {
      final String createTriggerSql = """
          create trigger e2e_abort_launch_connection after insert on batch.%s
          for each row when (new.tenant_id = 't1' and (%s))
          execute function batch.e2e_abort_launch_connection()
          """.formatted(table, predicate);
      jdbcTemplate.execute(createTriggerSql);
      assertThatThrownBy(() -> launchService.launch(request))
          .isInstanceOfSatisfying(TransactionSystemException.class, failure -> {
            // 替代成员才能接收分区并确认旧记录。
            // A组连接已终止时，Spring 的回滚也会失败。检查已保存的
            assertThat(failure.getApplicationException())
                .hasRootCauseInstanceOf(PSQLException.class);
            assertThat(NestedExceptionUtils.getMostSpecificCause(failure.getApplicationException()))
                .isInstanceOfSatisfying(
                    PSQLException.class,
                    sqlFailure -> assertThat(sqlFailure.getSQLState()).isEqualTo("57P01"));
          });
      Integer selectedWorkflowRunCount = jdbcTemplate.queryForObject(
          selectCountSql, Integer.class, TENANT, seed.workflowDefinitionId());
      assertThat(selectedWorkflowRunCount)
          .isEqualTo(faultPoint == LaunchCommitFaultPoint.ROOT ? 0 : 1);
      Integer selectedJobInstanceCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, seed.jobCode());
      assertThat(selectedJobInstanceCount).isZero();
      Integer selectedOutboxEventCount =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, seed.jobCode());
      assertThat(selectedOutboxEventCount).isZero();
      String selectedRequestStatus = jdbcTemplate.queryForObject(
          selectRequestStatusSql, String.class, TENANT, seed.requestId());
      assertThat(selectedRequestStatus).isEqualTo("ACCEPTED");
      Integer selectedCustomerAccountCount = new JdbcTemplate(businessDataSource)
          .queryForObject(selectCountSql5, Integer.class, TENANT, customerNo);
      assertThat(selectedCustomerAccountCount).isZero();
    } finally {
      final String dropTriggerSql = """
          drop trigger if exists e2e_abort_launch_connection on batch.%s\
          """.formatted(table);
      executeCleanupSql(dropTriggerSql);
      executeCleanupSql(dropFunctionSql);
    }

    String recoveredRunId = launchService.launch(request).instanceNo();
    assertThat(launchService.launch(request).instanceNo()).isEqualTo(recoveredRunId);
    await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
      String selectedRunStatus = jdbcTemplate.queryForObject(
          selectRunStatusSql, String.class, TENANT, seed.workflowDefinitionId());
      assertThat(selectedRunStatus).isEqualTo("SUCCESS");
      Integer selectedCustomerAccountCountRead2 = new JdbcTemplate(businessDataSource)
          .queryForObject(selectCountSql5, Integer.class, TENANT, customerNo);
      assertThat(selectedCustomerAccountCountRead2).isEqualTo(1);
    });
    Integer selectedJobInstanceCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, seed.jobCode());
    assertThat(selectedJobInstanceCountRead2).isEqualTo(1);
    Integer selectedOutboxEventCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql4, Integer.class, TENANT, seed.jobCode());
    assertThat(selectedOutboxEventCountRead2).isEqualTo(1);
  }

  @Test
  @DisplayName("Kafka 已送达但发布状态回写连接被终止,轮询器自动重投且真实 Worker 业务结果只写一次")
  void shouldRecoverPublishedMessage_whenDatabaseConnectionDiesBeforeOutboxAcknowledgement()
      throws Exception {
    assertAutomaticRecovery(false);
  }

  @Test
  @DisplayName("运行与 Outbox 已提交但尚未投递时平台数据库崩溃恢复,轮询器与真实 Worker 自动完成且业务结果唯一")
  void shouldRecoverAcceptedWork_whenPlatformDatabaseCrashRecoversBeforeKafkaPublish()
      throws Exception {
    assertAutomaticRecovery(true);
  }

  private void assertAutomaticRecovery(boolean crashBeforePublish) throws Exception {
    final String selectPgBackendPidSql = """
        select pg_backend_pid()
    """;

    final String selectCountSql4 = """
        select count(*) from biz.customer_account where tenant_id = ? and customer_no = ?
    """;

    final String createFunctionSql = """
        create function batch.e2e_hold_outbox_ack() returns trigger language plpgsql as $$
        begin
          if new.tenant_id = TG_ARGV[0] and new.event_type = 'IMPORT'
              and new.payload_json ->> 'jobCode' = TG_ARGV[1] then
            perform pg_advisory_xact_lock(TG_ARGV[2]::bigint);
          end if;
          return new;
        end
        $$
        """;
    final String selectIdSql = """
            select t.id from batch.job_task t
            join batch.job_instance ji on ji.id = t.job_instance_id and ji.tenant_id = t.tenant_id
            where ji.tenant_id = ? and ji.dedup_key = ?
            """;
    final String selectIdSql2 = """
            select id, payload_json::text as payload from batch.outbox_event
            where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
            """;
    final String selectPublishStatusSql = """
        select publish_status from batch.outbox_event where tenant_id = ? and id = ?
    """;
    final String selectTaskStatusSql = """
        select task_status from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectPublishAttemptSql = """
        select publish_attempt from batch.outbox_event where tenant_id = ? and id = ?
    """;
    final String selectPgTerminateBackendSql = """
        select pg_terminate_backend(?)
    """;
    final String selectPublishStatusSql2 = """
                select publish_status, payload_json::text as payload from batch.outbox_event
                where tenant_id = ? and id = ?
                """;
    final String selectRunStatusSql = """
                select wr.run_status from batch.workflow_run wr
                join batch.trigger_request tr on tr.id = wr.trigger_request_id
                    and tr.tenant_id = wr.tenant_id
                where tr.tenant_id = ? and tr.request_id = ?
                """;
    final String selectCountSql = """
              select count(*) from batch.job_task t
              join batch.job_instance ji on ji.id = t.job_instance_id and ji.tenant_id = t.tenant_id
              where ji.tenant_id = ? and ji.dedup_key = ?
              """;
    final String selectCountSql2 = """
              select count(*) from batch.outbox_event
              where tenant_id = ? and aggregate_type = 'JOB_TASK' and aggregate_id = ?
              """;
    final String selectCountSql3 = """
              select count(*) from batch.workflow_run wr
              join batch.trigger_request tr on tr.id = wr.trigger_request_id
                  and tr.tenant_id = wr.tenant_id
              where tr.tenant_id = ? and tr.request_id = ? and wr.run_status = 'SUCCESS'
              """;
    final String dropTriggerSql = """
        drop trigger if exists e2e_hold_outbox_ack on batch.outbox_event
    """;
    final String dropFunctionSql = """
        drop function if exists batch.e2e_hold_outbox_ack()
    """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);
    String customerNo = "ACKLOSS" + Long.toUnsignedString(System.nanoTime());
    long gateKey = System.nanoTime();
    Map<Long, AtomicInteger> claimAttempts = new ConcurrentHashMap<>();
    Map<Long, AtomicInteger> successfulClaims = new ConcurrentHashMap<>();
    doAnswer(invocation -> {
          Long id = invocation.getArgument(0);
          claimAttempts.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
          Object claimed = invocation.callRealMethod();
          if (EmptyChecks.isNotNull(claimed)) {
            successfulClaims.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
          }
          return claimed;
        })
        .when(taskControllerApplicationService)
        .claim(any(Long.class), any(TaskClaimCommand.class));

    jdbcTemplate.execute(createFunctionSql);
    try {
      String blockedTransition = crashBeforePublish
          ? "old.publish_status in ('NEW', 'FAILED') and new.publish_status = 'PUBLISHING'"
          : "old.publish_status = 'PUBLISHING' and new.publish_status = 'PUBLISHED'";
      final String createTriggerSql =
          """
          create trigger e2e_hold_outbox_ack before update on batch.outbox_event
          for each row when (%s)
          execute function batch.e2e_hold_outbox_ack('%s', '%s', '%s')
          """.formatted(blockedTransition, TENANT, seed.jobCode(), gateKey);
      jdbcTemplate.execute(createTriggerSql);
      Long taskId;
      Long outboxId;
      String originalPayload;
      int attemptsBeforeDisconnect;
      int claimsBeforeDisconnect;
      try (Connection gate = DriverManager.getConnection(
              PLATFORM_POSTGRES.getJdbcUrl(),
              PLATFORM_POSTGRES.getUsername(),
              PLATFORM_POSTGRES.getPassword());
          var statement = gate.createStatement()) {
        final String selectPgAdvisoryLockSql = """
                select pg_advisory_lock(%s)\
                """.formatted(gateKey);
        statement.execute(selectPgAdvisoryLockSql);
        int gatePid;
        try (var rows = statement.executeQuery(selectPgBackendPidSql)) {
          assertThat(rows.next()).isTrue();
          gatePid = rows.getInt(1);
        }
        launchService.launch(new LaunchRequest(
            TENANT,
            seed.workflowDefinitionId(),
            BIZ_DATE,
            TriggerType.API,
            seed.requestId(),
            "e2e-outbox-ack-loss",
            Map.of(
                "fileFormatType", "JSON",
                "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
                "bizType", "CUSTOMER",
                "content",
                    JsonUtils.toJson(List.of(Map.of(
                        "customerNo", customerNo,
                        "customerName", "Outbox recovery",
                        "customerType", "PERSONAL",
                        "certificateNo", customerNo,
                        "status", "ACTIVE"))))));
        taskId = jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.dedupKey());
        Map<String, Object> event = jdbcTemplate.queryForMap(selectIdSql2, TENANT, taskId);
        outboxId = ((Number) event.get("id")).longValue();
        originalPayload = (String) event.get("payload");
        await()
            .atMost(Duration.ofSeconds(60))
            .untilAsserted(() -> assertThat(blockedOutboxUpdatePids(gatePid)).hasSize(1));
        if (crashBeforePublish) {
          // 应用异常，确保断言仍能证明注入的数据库故障。
          String selectedPublishStatus =
              jdbcTemplate.queryForObject(selectPublishStatusSql, String.class, TENANT, outboxId);
          assertThat(selectedPublishStatus).isEqualTo("NEW");
          assertThat(claimAttempts.getOrDefault(taskId, new AtomicInteger()).get())
              .isZero();
          Integer selectedCustomerAccountCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCountSql4, Integer.class, TENANT, customerNo);
          assertThat(selectedCustomerAccountCount).isZero();
        } else {
          // 首次 Kafka 发送前，已提交的 Task 和 Outbox 必须在数据库恢复后仍然存在。
          await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            String selectedTaskStatus =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
            assertThat(selectedTaskStatus).isEqualTo("SUCCESS");
            String selectedPublishStatusRead2 =
                jdbcTemplate.queryForObject(selectPublishStatusSql, String.class, TENANT, outboxId);
            assertThat(selectedPublishStatusRead2).isEqualTo("PUBLISHING");
          });
        }
        attemptsBeforeDisconnect =
            jdbcTemplate.queryForObject(selectPublishAttemptSql, Integer.class, TENANT, outboxId);
        claimsBeforeDisconnect =
            claimAttempts.getOrDefault(taskId, new AtomicInteger()).get();
        if (crashBeforePublish) {
          // 生产方的持久化确认仍被阻塞时，Kafka 消息已被消费。
          // 从 WAL 恢复。保持容器运行可保留随机映射的端点。
          int failedBackend = blockedOutboxUpdatePids(gatePid).getFirst();
          var killed =
              PLATFORM_POSTGRES.execInContainer("kill", "-KILL", String.valueOf(failedBackend));
          assertThat(killed.getExitCode()).isZero();
          await().atMost(Duration.ofSeconds(15)).until(() -> !gate.isValid(1));
        } else {
          int failedBackend = blockedOutboxUpdatePids(gatePid).getFirst();
          Boolean selectedPgTerminateBackend = jdbcTemplate.queryForObject(
              selectPgTerminateBackendSql, Boolean.class, failedBackend);
          assertThat(selectedPgTerminateBackend).isTrue();
        }
      }

      // 后端异常退出会使 PostgreSQL 终止其他会话并执行恢复。
      await()
          .atMost(Duration.ofSeconds(120))
          .ignoreExceptionsInstanceOf(DataAccessException.class)
          .untilAsserted(() -> {
            if (crashBeforePublish) {
              assertThat(PLATFORM_POSTGRES.getLogs())
                  .contains("terminating any other active server processes")
                  .contains("database system is ready to accept connections");
            }
            Map<String, Object> selectedPublishStatusRow =
                jdbcTemplate.queryForMap(selectPublishStatusSql2, TENANT, outboxId);
            assertThat(selectedPublishStatusRow)
                .containsEntry("publish_status", "PUBLISHED")
                .containsEntry("payload", originalPayload);
            Integer selectedPublishAttempt = jdbcTemplate.queryForObject(
                selectPublishAttemptSql, Integer.class, TENANT, outboxId);
            assertThat(selectedPublishAttempt).isGreaterThan(attemptsBeforeDisconnect);
            assertThat(claimAttempts.getOrDefault(taskId, new AtomicInteger()).get())
                .isGreaterThan(claimsBeforeDisconnect);
            String selectedTaskStatusRead2 =
                jdbcTemplate.queryForObject(selectTaskStatusSql, String.class, TENANT, taskId);
            assertThat(selectedTaskStatusRead2).isEqualTo("SUCCESS");
            String selectedRunStatus = jdbcTemplate.queryForObject(
                selectRunStatusSql, String.class, TENANT, seed.requestId());
            assertThat(selectedRunStatus).isEqualTo("SUCCESS");
          });
      assertThat(successfulClaims.get(taskId).get()).isEqualTo(1);
      Integer selectedCustomerAccountCountRead2 = new JdbcTemplate(businessDataSource)
          .queryForObject(selectCountSql4, Integer.class, TENANT, customerNo);
      assertThat(selectedCustomerAccountCountRead2).isEqualTo(1);
      Integer selectedJobTaskCount =
          jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.dedupKey());
      assertThat(selectedJobTaskCount).isEqualTo(1);
      Integer selectedOutboxEventCount =
          jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, taskId);
      assertThat(selectedOutboxEventCount).isEqualTo(1);
      Integer selectedWorkflowRunCount =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, seed.requestId());
      assertThat(selectedWorkflowRunCount).isEqualTo(1);
    } finally {
      executeCleanupSql(dropTriggerSql);
      executeCleanupSql(dropFunctionSql);
    }
  }

  /**
   * 清理用的 DROP TRIGGER 需要 AccessExclusiveLock，会和仍在写 outbox 的轮询器死锁（40P01）。
   * 只重试这次清理，不改变故障注入本身。
   */
  private void executeCleanupSql(String sql) {
    DataAccessException lastFailure = null;
    for (int attempt = 1; attempt <= 5; attempt++) {
      try {
        jdbcTemplate.execute(sql);
        return;
      } catch (DataAccessException exception) {
        lastFailure = exception;
        boolean retryableDeadlock = attempt < 5 && isDeadlock(exception);
        if (!retryableDeadlock) {
          throw exception;
        }
        try {
          Thread.sleep(200L * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw exception;
        }
      }
    }
    throw lastFailure;
  }

  private static boolean isDeadlock(Throwable failure) {
    Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
    return cause instanceof PSQLException sql && "40P01".equals(sql.getSQLState());
  }

  private List<Integer> blockedOutboxUpdatePids(int gatePid) {
    final String selectPidSql = """
        select pid from pg_stat_activity
        where wait_event_type = 'Lock' and ? = any(pg_blocking_pids(pid))
            and lower(query) like '%update batch.outbox_event%'
        """;

    return jdbcTemplate.queryForList(selectPidSql, Integer.class, gatePid);
  }

  @Test
  @DisplayName("不手工投递待发事件,仅由常驻轮询器自动投递,导入任务最终收敛到成功终态")
  void shouldAutoPublishAndReachSuccess_whenOutboxPollerRunsWithoutManualPublish() {
    final String selectTaskStatusSql = """
                      select t.task_status from batch.job_task t
                      join batch.job_instance ji on ji.id = t.job_instance_id
                      where ji.tenant_id = ? and ji.dedup_key = ?
                      """;

    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API, BIZ_DATE);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", "IMP-CUSTOMER-JSON-ARRAY");
    params.put("bizType", "CUSTOMER");
    params.put("content", """
            [{
              "customerNo": "E2E-FWD-001",
              "customerName": "Forwarder User",
              "customerType": "PERSONAL",
              "certificateNo": "ID-20260115-9001",
              "mobileNo": "13800009001",
              "email": "fwd@example.com",
              "status": "ACTIVE"
            }]
            """.stripTrailing());

    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        "e2e-tr-outbox-fwd",
        params));

    // OutboxPollScheduler 每 500 毫秒运行一次，并自动发布待处理的 Outbox 事件。
    await()
        .atMost(Duration.ofSeconds(120))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          E2eStatusLogger.logJobFlowSnapshot(
              jdbcTemplate, TENANT, seed.dedupKey(), "OutboxForwarderE2eIT");
          String status = jdbcTemplate.queryForObject(
              selectTaskStatusSql, String.class, TENANT, seed.dedupKey());
          assertThat(status).isEqualTo("SUCCESS");
        });
  }
}
