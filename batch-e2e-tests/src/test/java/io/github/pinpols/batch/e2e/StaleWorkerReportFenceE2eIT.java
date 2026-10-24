package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.e2e.apps.E2eOrchestratorApplication;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.orchestrator.application.service.task.TaskExecutionService;
import io.github.pinpols.batch.orchestrator.application.service.task.WorkflowRunResumeProgressor;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionReclaimUnit;
import io.github.pinpols.batch.orchestrator.infrastructure.lease.ReclaimRetryableException;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 直接获取生产 JDBC ShedLock，并一直持有，直到父进程终止此 JVM。 */
@E2eSpringBootTest(
    classes = E2eOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.security.bypass-mode=true",
      "batch.outbox.poll-interval-millis=600000",
      "batch.partition-lease.reclaim-interval-millis=600000",
      "batch.resource-scheduler.waiting-dispatch-interval-millis=600000",
      "batch.workflow.stuck-reconcile.enabled=false",
      "batch.workflow.stuck-reconcile.poll-interval-millis=1000"
    })
@DisplayName("过期 Worker attempt 经 HTTP 上报被拒绝,新 attempt 的真实结果正常落库")
class StaleWorkerReportFenceE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String WORKER = "r15-stale-report-worker";
  private static final String TEST_PRESIGN_SECRET = "test-only-" + "x".repeat(48);
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final int R15_CONCURRENT_REPORT_COUNT = 16;

  @LocalServerPort
  private int localServerPort;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private E2eOutboxPublishSupport outboxPublishSupport;

  @Autowired
  private TaskExecutionService taskExecutionService;

  @Autowired
  private JobInstanceMapper jobInstanceMapper;

  @Autowired
  private JobPartitionMapper jobPartitionMapper;

  @Autowired
  private JobTaskMapper jobTaskMapper;

  @Autowired
  private PartitionReclaimUnit partitionReclaimUnit;

  @MockitoSpyBean
  private WorkflowRunResumeProgressor workflowRunResumeProgressor;

  @Value("${batch.security.internal-secret}")
  private String internalSecret;

  @Test
  @DisplayName("reclaim 后取消对 READY task 为 no-op,旧 invocation REPORT 冲突且新 attempt 成功")
  void staleInvocationHttpReport_isRejectedAfterReclaim_andCurrentAttemptCompletes()
      throws Exception {
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;

    ClaimedTask claimed = launchAndClaim("stale-report");
    Long taskId = claimed.taskId();
    Long partitionId = claimed.partitionId();
    String oldInvocation = claimed.invocationId();
    assertThat(oldInvocation).isNotBlank();

        jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        partitionId);
    partitionReclaimUnit.reclaim(jobPartitionMapper.selectById(TENANT, partitionId));
    assertThat(jobTaskMapper.selectById(TENANT, taskId).getTaskStatus())
        .isEqualTo(TaskStatus.READY.code());
    assertThat(jobPartitionMapper.selectById(TENANT, partitionId).getPartitionStatus())
        .isEqualTo(PartitionStatus.READY.code());

    HttpResponse<String> cancelAfterReclaim = postJson(
        "/internal/tasks/" + taskId + "/cancel",
        Map.of("tenantId", TENANT, "reason", "R15 cancel after reclaim no-op contract"));
    assertThat(cancelAfterReclaim.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, taskId).getCancelRequested()).isFalse();

    JobTaskEntity secondClaim = awaitClaim(taskId);
    assertThat(secondClaim.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(secondClaim.getCancelRequested()).isFalse();
    String currentInvocation =
        jobPartitionMapper.selectById(TENANT, partitionId).getCurrentInvocationId();
    assertThat(currentInvocation).isNotBlank().isNotEqualTo(oldInvocation);

    HttpResponse<String> renewResponse = postJson(
        "/internal/tasks/" + taskId + "/renew",
        Map.of(
            "tenantId", TENANT,
            "workerId", WORKER,
            "partitionInvocationId", currentInvocation));
    assertThat(renewResponse.statusCode()).isEqualTo(200);
    assertThat(renewResponse.body()).contains("\"cancelRequested\":false");

    HttpResponse<String> staleResponse = postReport(taskId, oldInvocation, "stale I1");
    assertThat(staleResponse.statusCode()).isEqualTo(409);
    assertThat(staleResponse.body()).contains("\"code\":\"CONFLICT\"");
    assertThat(jobTaskMapper.selectById(TENANT, taskId).getTaskStatus())
        .isEqualTo(TaskStatus.RUNNING.code());
    JobPartitionEntity afterStaleReport = jobPartitionMapper.selectById(TENANT, partitionId);
    assertThat(afterStaleReport.getPartitionStatus()).isEqualTo(PartitionStatus.RUNNING.code());
    assertThat(afterStaleReport.getCurrentInvocationId()).isEqualTo(currentInvocation);

    HttpResponse<String> validResponse = postReport(taskId, currentInvocation, "current I2");
    assertThat(validResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, taskId).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobPartitionMapper.selectById(TENANT, partitionId).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
  }

  @Test
  @DisplayName("reclaim 后取消对 READY no-op,I2 普通失败终结且 I1 迟到失败不重复记账")
  void reclaimBeforeCancelNoop_andCurrentOrdinaryFailureFencesStaleFailure() throws Exception {
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'JOB_TASK'\s\
        and aggregate_id = ?\
        """;
    final String selectErrorCodeSql = """
        select error_code from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectRetryCountSql = """
        select retry_count from batch.job_partition where tenant_id = ? and id = ?
    """;
    final String selectCountSql2 = """
        select count(*) from batch.retry_schedule where tenant_id = ? and related_type = 'JOB_PARTITION'\s\
        and related_id = ?\
        """;
    final String selectCountSql3 = """
        select count(*) from batch.dead_letter_task where tenant_id = ? and source_type = 'JOB_PARTITION'\s\
        and source_id = ? and replay_status = 'NEW' and max_replay_count = 0 and next_replay_at is null\
        """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectCountSql4 = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'JOB_TASK'\s\
        and aggregate_id = ?\
        """;

    ClaimedTask firstAttempt = launchAndClaim("reclaim-cancel-noop-i2-ordinary-failure");
    jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        firstAttempt.partitionId());
    partitionReclaimUnit.reclaim(jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));
    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.READY.code());

    HttpResponse<String> cancelAfterReclaim = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/cancel",
        Map.of("tenantId", TENANT, "reason", "R15 cancellation after reclaim is a no-op"));
    assertThat(cancelAfterReclaim.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getCancelRequested())
        .isFalse();

    JobTaskEntity secondAttempt = awaitClaim(firstAttempt.taskId());
    String currentInvocation =
        jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getCurrentInvocationId();
    assertThat(secondAttempt.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(secondAttempt.getCancelRequested()).isFalse();
    assertThat(currentInvocation).isNotBlank().isNotEqualTo(firstAttempt.invocationId());
    HttpResponse<String> renewResponse = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/renew",
        Map.of(
            "tenantId", TENANT,
            "workerId", WORKER,
            "partitionInvocationId", currentInvocation));
    assertThat(renewResponse.statusCode()).isEqualTo(200);
    assertThat(renewResponse.body()).contains("\"cancelRequested\":false");

    int dispatchOutboxBeforeFailure = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        TENANT,
        firstAttempt.taskId());
    HttpResponse<String> currentFailure = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/report",
        Map.of(
            "tenantId",
            TENANT,
            "workerId",
            WORKER,
            "success",
            false,
            "errorCode",
            "I2_ORDINARY_FAILURE",
            "message",
            "current attempt ordinary failure after reclaim",
            "partitionInvocationId",
            currentInvocation));
    assertThat(currentFailure.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.FAILED.code());
    String selectedErrorCode = jdbcTemplate.queryForObject(
        selectErrorCodeSql, String.class, TENANT, firstAttempt.taskId());
    assertThat(selectedErrorCode).isEqualTo("I2_ORDINARY_FAILURE");
    assertThat(
            jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.FAILED.code());

    HttpResponse<String> staleFailure = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/report",
        Map.of(
            "tenantId",
            TENANT,
            "workerId",
            WORKER,
            "success",
            false,
            "errorCode",
            "I1_STALE_FAILURE",
            "message",
            "old attempt failure after I2 terminal failure",
            "partitionInvocationId",
            firstAttempt.invocationId()));
    assertThat(staleFailure.statusCode()).isEqualTo(200);
    String selectedErrorCodeRead2 = jdbcTemplate.queryForObject(
        selectErrorCodeSql, String.class, TENANT, firstAttempt.taskId());
    assertThat(selectedErrorCodeRead2).isEqualTo("I2_ORDINARY_FAILURE");
    Integer selectedRetryCount = jdbcTemplate.queryForObject(
        selectRetryCountSql, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedRetryCount).isZero();
    Integer selectedRetryScheduleCount = jdbcTemplate.queryForObject(
        selectCountSql2, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedRetryScheduleCount).isZero();
    Integer selectedDeadLetterTaskCount = jdbcTemplate.queryForObject(
        selectCountSql3, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedDeadLetterTaskCount).isEqualTo(1);
    Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedFailedPartitionCount).isEqualTo(1);
    assertThat(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 100))
        .noneMatch(candidate -> firstAttempt.partitionId().equals(candidate.getId()));
    Integer selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql4, Integer.class, TENANT, firstAttempt.taskId());
    assertThat(selectedOutboxEventCount).isEqualTo(dispatchOutboxBeforeFailure);
  }

  @Test
  @DisplayName("I2 成功后旧 I1 的 HTTP 失败报告不得覆盖结果或重复结算")
  void lateStaleFailureHttpReportAfterCurrentAttemptSuccess_isIgnored() throws Exception {
    final String selectWorkflowRunIdSql = """
        select nr.workflow_run_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and nr.job_instance_id = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectResultSummarySql = """
        select result_summary::text from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectErrorCodeSql = """
        select error_code from batch.job_task where tenant_id = ? and id = ?
    """;
    final String selectRetryCountSql = """
        select retry_count from batch.job_partition where tenant_id = ? and id = ?
    """;
    final String selectSuccessPartitionCountSql = """
        select success_partition_count from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL' and payload_json->>'runStatus' = 'SUCCESS'\
        """;

    ClaimedTask firstAttempt = launchAndClaim("late-stale-failure-after-i2-success");
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectWorkflowRunIdSql,
        Long.class,
        TENANT,
        firstAttempt.jobInstanceId());
    jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        firstAttempt.partitionId());
    partitionReclaimUnit.reclaim(jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));

    JobTaskEntity secondAttempt = awaitClaim(firstAttempt.taskId());
    String currentInvocation =
        jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getCurrentInvocationId();
    assertThat(secondAttempt.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(currentInvocation).isNotBlank().isNotEqualTo(firstAttempt.invocationId());
    assertThat(postReport(firstAttempt.taskId(), currentInvocation, "I2 winning success")
            .statusCode())
        .isEqualTo(200);

    String winningTaskResult = jdbcTemplate.queryForObject(
        selectResultSummarySql,
        String.class,
        TENANT,
        firstAttempt.taskId());
    HttpResponse<String> staleFailure = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/report",
        Map.of(
            "tenantId",
            TENANT,
            "workerId",
            WORKER,
            "success",
            false,
            "errorCode",
            "STALE_I1_FAILURE",
            "message",
            "late I1 failure after I2 success",
            "resultSummary",
            "{\"attempt\":\"stale I1 failure\"}",
            "partitionInvocationId",
            firstAttempt.invocationId()));
    assertThat(staleFailure.statusCode()).isEqualTo(200);

    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    String selectedResultSummary = jdbcTemplate.queryForObject(
        selectResultSummarySql, String.class, TENANT, firstAttempt.taskId());
    assertThat(selectedResultSummary).isEqualTo(winningTaskResult).contains("I2 winning success");
    String selectedErrorCode = jdbcTemplate.queryForObject(
        selectErrorCodeSql, String.class, TENANT, firstAttempt.taskId());
    assertThat(selectedErrorCode).isNull();
    assertThat(
            jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
    Integer selectedRetryCount = jdbcTemplate.queryForObject(
        selectRetryCountSql, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedRetryCount).isZero();
    Integer selectedSuccessPartitionCount = jdbcTemplate.queryForObject(
        selectSuccessPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedSuccessPartitionCount).isEqualTo(1);
    Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedFailedPartitionCount).isZero();
    Integer selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, workflowRunId);
    assertThat(selectedOutboxEventCount).isEqualTo(1);
  }

  @Test
  @DisplayName("成功 REPORT 后的迟到 cancel 与 lease reclaim 不得复活终态或重派")
  void successfulReportBeforeCancelAndLeaseReclaim_keepsTerminalStateAndDispatchCount()
      throws Exception {
        final String selectCountSql = """
            select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'JOB_TASK'\s\
            and aggregate_id = ?\
            """;
        final String updateJobPartitionSql = """
            update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
            where tenant_id = ? and id = ?\
            """;
        final String selectCountSql2 = """
            select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'JOB_TASK'\s\
            and aggregate_id = ?\
            """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;

    ClaimedTask claimed = launchAndClaim("success-before-cancel-reclaim");
    HttpResponse<String> reportResponse =
        postReport(claimed.taskId(), claimed.invocationId(), "success before cancel/reclaim");
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());

    int dispatchOutboxBeforeLateOperations = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        TENANT,
        claimed.taskId());
        jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        claimed.partitionId());
    assertThat(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 100))
        .noneMatch(candidate -> claimed.partitionId().equals(candidate.getId()));

    HttpResponse<String> cancelResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/cancel",
        Map.of("tenantId", TENANT, "reason", "R15 cancellation after successful report"));
    assertThat(cancelResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getCancelRequested())
        .isFalse();
    assertThat(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 100))
        .noneMatch(candidate -> claimed.partitionId().equals(candidate.getId()));

    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getCancelRequested())
        .isFalse();
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
        Integer selectedOutboxEventCount =
            jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, claimed.taskId());
        assertThat(selectedOutboxEventCount).isEqualTo(dispatchOutboxBeforeLateOperations);
        String selectedInstanceStatus = jdbcTemplate.queryForObject(
            selectInstanceStatusSql, String.class, TENANT, claimed.jobInstanceId());
        assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("取消先被接受后 Worker 成功 REPORT,终态成功且 lease 扫描不重派")
  void acceptedCancellationBeforeSuccessReport_andLeaseScan_keepsSuccessTerminal()
      throws Exception {
        final String selectCountSql = """
            select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'JOB_TASK'\s\
            and aggregate_id = ?\
            """;
        final String updateJobPartitionSql = """
            update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
            where tenant_id = ? and id = ?\
            """;
        final String selectCountSql2 = """
            select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'JOB_TASK'\s\
            and aggregate_id = ?\
            """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;

    ClaimedTask claimed = launchAndClaim("cancel-before-success-report");
    HttpResponse<String> cancelResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/cancel",
        Map.of("tenantId", TENANT, "reason", "R15 accepted cancel before successful report"));
    assertThat(cancelResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getCancelRequested())
        .isTrue();

    int dispatchOutboxBeforeReport = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        TENANT,
        claimed.taskId());
    HttpResponse<String> reportResponse =
        postReport(claimed.taskId(), claimed.invocationId(), "success after cancellation request");
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());

        jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        claimed.partitionId());
    assertThat(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 100))
        .noneMatch(candidate -> claimed.partitionId().equals(candidate.getId()));
        Integer selectedOutboxEventCount =
            jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, claimed.taskId());
        assertThat(selectedOutboxEventCount).isEqualTo(dispatchOutboxBeforeReport);
        String selectedInstanceStatus = jdbcTemplate.queryForObject(
            selectInstanceStatusSql, String.class, TENANT, claimed.jobInstanceId());
        assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("取消先被接受后普通失败 REPORT 仍按 RetryPolicy 终结且 lease 扫描不重派")
  void acceptedCancellationBeforeOrdinaryFailureAndLeaseScan_keepsFailureTerminal()
      throws Exception {
        final String selectCountSql = """
            select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'JOB_TASK'\s\
            and aggregate_id = ?\
            """;
        final String selectErrorCodeSql = """
            select error_code from batch.job_task where tenant_id = ? and id = ?
        """;
        final String selectRetryCountSql = """
            select retry_count from batch.job_partition where tenant_id = ? and id = ?
        """;
        final String selectCountSql2 = """
            select count(*) from batch.retry_schedule where tenant_id = ? and related_type = 'JOB_PARTITION'\s\
            and related_id = ?\
            """;
        final String selectCountSql3 = """
            select count(*) from batch.dead_letter_task where tenant_id = ? and source_type = 'JOB_PARTITION'\s\
            and source_id = ? and replay_status = 'NEW' and max_replay_count = 0 and next_replay_at is null\
            """;
        final String selectFailedPartitionCountSql = """
            select failed_partition_count from batch.job_instance where tenant_id = ? and id = ?
        """;
        final String selectCancelledPartitionCountSql = """
            select cancelled_partition_count from batch.job_instance where tenant_id = ? and id = ?\
            """;
        final String updateJobPartitionSql = """
            update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
            where tenant_id = ? and id = ?\
            """;
        final String selectCountSql4 = """
            select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'JOB_TASK'\s\
            and aggregate_id = ?\
            """;
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;

    ClaimedTask claimed = launchAndClaim("cancel-before-ordinary-failure");
    HttpResponse<String> cancelResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/cancel",
        Map.of("tenantId", TENANT, "reason", "R15 accepted cancel before ordinary failure"));
    assertThat(cancelResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getCancelRequested())
        .isTrue();

    int dispatchOutboxBeforeFailure = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        TENANT,
        claimed.taskId());
    HttpResponse<String> failureResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/report",
        Map.of(
            "tenantId",
            TENANT,
            "workerId",
            WORKER,
            "success",
            false,
            "errorCode",
            "E2E_ORDINARY_FAILURE",
            "message",
            "ordinary business failure after cancellation request",
            "partitionInvocationId",
            claimed.invocationId()));
    assertThat(failureResponse.statusCode()).isEqualTo(200);

    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.FAILED.code());
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getCancelRequested())
        .isTrue();
        String selectedErrorCode =
            jdbcTemplate.queryForObject(selectErrorCodeSql, String.class, TENANT, claimed.taskId());
        assertThat(selectedErrorCode).isEqualTo("E2E_ORDINARY_FAILURE");
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.FAILED.code());
        Integer selectedRetryCount = jdbcTemplate.queryForObject(
            selectRetryCountSql, Integer.class, TENANT, claimed.partitionId());
        assertThat(selectedRetryCount).isZero();
        Integer selectedRetryScheduleCount =
            jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, claimed.partitionId());
        assertThat(selectedRetryScheduleCount).isZero();
        Integer selectedDeadLetterTaskCount =
            jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, claimed.partitionId());
        assertThat(selectedDeadLetterTaskCount).isEqualTo(1);
        Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
            selectFailedPartitionCountSql, Integer.class, TENANT, claimed.jobInstanceId());
        assertThat(selectedFailedPartitionCount).isEqualTo(1);
        Integer selectedCancelledPartitionCount = jdbcTemplate.queryForObject(
            selectCancelledPartitionCountSql, Integer.class, TENANT, claimed.jobInstanceId());
        assertThat(selectedCancelledPartitionCount).isZero();

        jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        claimed.partitionId());
    assertThat(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 100))
        .noneMatch(candidate -> claimed.partitionId().equals(candidate.getId()));
        Integer selectedOutboxEventCount =
            jdbcTemplate.queryForObject(selectCountSql4, Integer.class, TENANT, claimed.taskId());
        assertThat(selectedOutboxEventCount).isEqualTo(dispatchOutboxBeforeFailure);
        String selectedInstanceStatus = jdbcTemplate.queryForObject(
            selectInstanceStatusSql, String.class, TENANT, claimed.jobInstanceId());
        assertThat(selectedInstanceStatus).isEqualTo("FAILED");
  }

  @Test
  @DisplayName("HTTP 取消请求由 Worker 心跳观察后回报取消,任务/分区/实例计数收敛")
  void cancellationHttpRequest_isObservedByRenew_andCancelledReportSettlesResources()
      throws Exception {
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectCancelledPartitionCountSql = """
        select cancelled_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;

    ClaimedTask claimed = launchAndClaim("cancel-report");
    HttpResponse<String> cancelResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/cancel",
        Map.of("tenantId", TENANT, "reason", "R15 transport acceptance"));
    assertThat(cancelResponse.statusCode()).isEqualTo(200);

    HttpResponse<String> renewResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/renew",
        Map.of(
            "tenantId", TENANT,
            "workerId", WORKER,
            "partitionInvocationId", claimed.invocationId()));
    assertThat(renewResponse.statusCode()).isEqualTo(200);
    assertThat(renewResponse.body()).contains("\"cancelRequested\":true");

    HttpResponse<String> reportResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/report",
        Map.of(
            "tenantId",
            TENANT,
            "workerId",
            WORKER,
            "success",
            false,
            "errorCode",
            "WORKER_EXECUTION_CANCELLED",
            "message",
            "worker stopped after observing cancellation",
            "partitionInvocationId",
            claimed.invocationId()));
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.CANCELLED.code());
    // Worker 协作取消遵循生产契约：任务为 CANCELLED，而分区失败。
    // 通过真实内部 HTTP 端点和 PostgreSQL 验证旧尝试报告围栏。
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.FAILED.code());
        String selectedInstanceStatus = jdbcTemplate.queryForObject(
            selectInstanceStatusSql, String.class, TENANT, claimed.jobInstanceId());
        assertThat(selectedInstanceStatus).isEqualTo("FAILED");
        Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
            selectFailedPartitionCountSql, Integer.class, TENANT, claimed.jobInstanceId());
        assertThat(selectedFailedPartitionCount).isEqualTo(1);
        Integer selectedCancelledPartitionCount = jdbcTemplate.queryForObject(
            selectCancelledPartitionCountSql, Integer.class, TENANT, claimed.jobInstanceId());
        assertThat(selectedCancelledPartitionCount).isZero();
  }

  @Test
  @DisplayName("取消 HTTP 与成功 REPORT 并发时,任务仍唯一终结且资源计数不重复")
  void cancellationAndSuccessReport_raceOverHttp_preservesSingleTerminalOutcome() throws Exception {
    final String selectInstanceStatusSql = """
        select instance_status from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectSuccessPartitionCountSql = """
        select success_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count + cancelled_partition_count from batch.job_instance where tenant_id = ?\s\
        and id = ?\
        """;

    ClaimedTask claimed = launchAndClaim("cancel-report-race");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<HttpResponse<String>> cancel = executor.submit(() -> {
        ready.countDown();
        start.await();
        return postJson(
            "/internal/tasks/" + claimed.taskId() + "/cancel",
            Map.of("tenantId", TENANT, "reason", "R15 concurrent cancel/report"));
      });
      Future<HttpResponse<String>> report = executor.submit(() -> {
        ready.countDown();
        start.await();
        return postReport(claimed.taskId(), claimed.invocationId(), "race success");
      });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(cancel.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(report.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
    } finally {
      executor.shutdownNow();
    }

    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
    String selectedInstanceStatus = jdbcTemplate.queryForObject(
        selectInstanceStatusSql, String.class, TENANT, claimed.jobInstanceId());
    assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
    Integer selectedSuccessPartitionCount = jdbcTemplate.queryForObject(
        selectSuccessPartitionCountSql, Integer.class, TENANT, claimed.jobInstanceId());
    assertThat(selectedSuccessPartitionCount).isEqualTo(1);
    Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, claimed.jobInstanceId());
    assertThat(selectedFailedPartitionCount).isZero();
  }

  @Test
  @DisplayName("WorkflowRun 暂停不杀在途任务,恢复后真实 DAG 推进至成功终态")
  void workflowPauseAndResumeOverHttp_preservesInflightTask_andCompletesDag() throws Exception {
    final String selectWorkflowRunIdSql = """
        select nr.workflow_run_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and nr.job_instance_id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\s\
        and node_status = 'SUCCESS'\
        """;

    ClaimedTask claimed = launchAndClaim("workflow-pause-resume");
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectWorkflowRunIdSql,
        Long.class,
        TENANT,
        claimed.jobInstanceId());

    HttpResponse<String> pauseResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT, Map.of());
    assertThat(pauseResponse.statusCode()).isEqualTo(200);
    assertThat(pauseResponse.body()).contains("\"status\":\"PAUSED\"");
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("PAUSED");
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.RUNNING.code());

    HttpResponse<String> reportResponse =
        postReport(claimed.taskId(), claimed.invocationId(), "completed while paused");
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("PAUSED");

    HttpResponse<String> resumeResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT, Map.of());
    assertThat(resumeResponse.statusCode()).isEqualTo(200);
    assertThat(resumeResponse.body()).contains("\"status\":\"RUNNING\"");
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          String selectedRunStatusRead3 =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
          assertThat(selectedRunStatusRead3).isEqualTo("SUCCESS");
        });
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
  }

  @Test
  @DisplayName("Workflow pause 与在途成功 REPORT 并发后,恢复仍只推进一次并正确记账")
  void workflowPauseAndSuccessfulReport_raceOverHttp_convergesOnce() throws Exception {
    final String selectWorkflowRunIdSql = """
        select nr.workflow_run_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and nr.job_instance_id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectSuccessPartitionCountSql = """
        select success_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\s\
        and node_status = 'SUCCESS'\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and job_instance_id is not null\
        """;

    ClaimedTask claimed = launchAndClaim("workflow-pause-report-race");
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectWorkflowRunIdSql,
        Long.class,
        TENANT,
        claimed.jobInstanceId());
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    HttpResponse<String> pauseResponse;
    HttpResponse<String> reportResponse;
    try {
      Future<HttpResponse<String>> pause = executor.submit(() -> {
        ready.countDown();
        start.await();
        return postJson(
            "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT, Map.of());
      });
      Future<HttpResponse<String>> report = executor.submit(() -> {
        ready.countDown();
        start.await();
        return postReport(claimed.taskId(), claimed.invocationId(), "pause/report race");
      });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      pauseResponse = pause.get(10, TimeUnit.SECONDS);
      reportResponse = report.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(pauseResponse.statusCode()).isIn(200, 409);
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    String runStatus = jdbcTemplate.queryForObject(
        selectRunStatusSql,
        String.class,
        TENANT,
        workflowRunId);
    if ("PAUSED".equals(runStatus)) {
      HttpResponse<String> resumeResponse = postJson(
          "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT, Map.of());
      assertThat(resumeResponse.statusCode()).isEqualTo(200);
    }
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          String selectedRunStatus =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
          assertThat(selectedRunStatus).isEqualTo("SUCCESS");
        });
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
    Integer selectedSuccessPartitionCount = jdbcTemplate.queryForObject(
        selectSuccessPartitionCountSql, Integer.class, TENANT, claimed.jobInstanceId());
    assertThat(selectedSuccessPartitionCount).isEqualTo(1);
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
    Integer selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1);
  }

  @Test
  @DisplayName("取消请求先于 lease reclaim 时保留到新 attempt,旧 invocation 不能覆盖取消")
  void cancellationRequest_survivesLeaseReclaim_andFencesOldReport() throws Exception {
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectRetryCountSql = """
        select retry_count from batch.job_partition where tenant_id = ? and id = ?
    """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectSuccessPartitionCountSql = """
        select success_partition_count from batch.job_instance where tenant_id = ? and id = ?
    """;

    ClaimedTask firstAttempt = launchAndClaim("cancel-survives-reclaim");
    HttpResponse<String> cancelResponse = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/cancel",
        Map.of("tenantId", TENANT, "reason", "R15 cancel and reclaim ordering"));
    assertThat(cancelResponse.statusCode()).isEqualTo(200);

    jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        firstAttempt.partitionId());
    partitionReclaimUnit.reclaim(jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));

    JobTaskEntity reclaimed = jobTaskMapper.selectById(TENANT, firstAttempt.taskId());
    assertThat(reclaimed.getTaskStatus()).isEqualTo(TaskStatus.READY.code());
    assertThat(reclaimed.getCancelRequested()).isTrue();
    assertThat(
            jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.READY.code());

    JobTaskEntity secondAttempt = awaitClaim(firstAttempt.taskId());
    String currentInvocation =
        jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getCurrentInvocationId();
    assertThat(secondAttempt.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(currentInvocation).isNotBlank().isNotEqualTo(firstAttempt.invocationId());

    HttpResponse<String> renewResponse = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/renew",
        Map.of(
            "tenantId", TENANT,
            "workerId", WORKER,
            "partitionInvocationId", currentInvocation));
    assertThat(renewResponse.statusCode()).isEqualTo(200);
    assertThat(renewResponse.body()).contains("\"cancelRequested\":true");

    HttpResponse<String> staleFailure = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/report",
        Map.of(
            "tenantId",
            TENANT,
            "workerId",
            WORKER,
            "success",
            false,
            "errorCode",
            "STALE_I1_FAILURE",
            "message",
            "late I1 failure after accepted cancellation and reclaim",
            "partitionInvocationId",
            firstAttempt.invocationId()));
    assertThat(staleFailure.statusCode()).isEqualTo(409);
    assertThat(staleFailure.body()).contains("\"code\":\"CONFLICT\"");
    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.RUNNING.code());
    assertThat(
            jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.RUNNING.code());
    Integer selectedRetryCount = jdbcTemplate.queryForObject(
        selectRetryCountSql, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedRetryCount).isZero();
    Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedFailedPartitionCount).isZero();

    HttpResponse<String> staleSuccess = postReport(
        firstAttempt.taskId(),
        firstAttempt.invocationId(),
        "stale success after cancellation reclaim");
    assertThat(staleSuccess.statusCode()).isEqualTo(409);
    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.RUNNING.code());

    HttpResponse<String> cancellationReport = postJson(
        "/internal/tasks/" + firstAttempt.taskId() + "/report",
        Map.of(
            "tenantId",
            TENANT,
            "workerId",
            WORKER,
            "success",
            false,
            "errorCode",
            "WORKER_EXECUTION_CANCELLED",
            "message",
            "replacement attempt observed the persisted cancellation",
            "partitionInvocationId",
            currentInvocation));
    assertThat(cancellationReport.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.CANCELLED.code());
    assertThat(
            jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.FAILED.code());
    Integer selectedSuccessPartitionCount = jdbcTemplate.queryForObject(
        selectSuccessPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedSuccessPartitionCount).isZero();
    Integer selectedFailedPartitionCountRead2 = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedFailedPartitionCountRead2).isEqualTo(1);
  }

  @Test
  @DisplayName("20 轮取消、成功 REPORT 与 lease reclaim 并发竞争均按持久状态收敛")
  void cancellation_successReport_andLeaseReclaimRace_convergesWithoutResidue() throws Exception {
    for (int round = 1; round <= 20; round++) {
      assertCancelReportReclaimRace(round);
    }
  }

  @Test
  @DisplayName("10 轮普通失败 REPORT 与 lease reclaim 并发竞争只产生一次失败记账")
  void ordinaryFailureReport_andLeaseReclaimRace_convergesWithoutDuplicateFailureAccounting()
      throws Exception {
    for (int round = 1; round <= 10; round++) {
      assertOrdinaryFailureReclaimRace(round);
    }
  }

  @Test
  @DisplayName("暂停期间 Job 协作取消后恢复不重派失败节点")
  void workflowResumeAfterCancelledInflightJob_doesNotRedispatchFailedNode() throws Exception {
    final String selectWorkflowRunIdSql = """
        select nr.workflow_run_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and nr.job_instance_id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and job_instance_id is not null\
        """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\s\
        and node_status = 'SUCCESS'\
        """;

    ClaimedTask claimed = launchAndClaim("workflow-pause-cancel-resume");
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectWorkflowRunIdSql,
        Long.class,
        TENANT,
        claimed.jobInstanceId());

    HttpResponse<String> pauseResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT, Map.of());
    assertThat(pauseResponse.statusCode()).isEqualTo(200);
    HttpResponse<String> cancelResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/cancel",
        Map.of("tenantId", TENANT, "reason", "cancel while root workflow is paused"));
    assertThat(cancelResponse.statusCode()).isEqualTo(200);
    HttpResponse<String> renewResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/renew",
        Map.of(
            "tenantId", TENANT,
            "workerId", WORKER,
            "partitionInvocationId", claimed.invocationId()));
    assertThat(renewResponse.statusCode()).isEqualTo(200);
    assertThat(renewResponse.body()).contains("\"cancelRequested\":true");
    HttpResponse<String> reportResponse = postJson(
        "/internal/tasks/" + claimed.taskId() + "/report",
        Map.of(
            "tenantId",
            TENANT,
            "workerId",
            WORKER,
            "success",
            false,
            "errorCode",
            "WORKER_EXECUTION_CANCELLED",
            "message",
            "cancelled while WorkflowRun was paused",
            "partitionInvocationId",
            claimed.invocationId()));
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.CANCELLED.code());
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("PAUSED");

    HttpResponse<String> resumeResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT, Map.of());
    assertThat(resumeResponse.statusCode()).isEqualTo(200);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          String selectedRunStatusRead2 =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
          assertThat(selectedRunStatusRead2).isEqualTo("FAILED");
        });
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
    Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, claimed.jobInstanceId());
    assertThat(selectedFailedPartitionCount).isEqualTo(1);
    Integer selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead2).isZero();
  }

  @Test
  @DisplayName("resume 提交后进程丢失 DAG 推进时,stuck reconciler 从持久节点恢复")
  void workflowResumeProgressLostAfterCommit_isRecoveredFromPersistedNodeState() throws Exception {
    final String selectWorkflowRunIdSql = """
        select nr.workflow_run_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and nr.job_instance_id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'\
        """;
    final String updateWorkflowRunSql = """
        update batch.workflow_run set updated_at = current_timestamp - interval '2 hours' where tenant_id = ?\s\
        and id = ?\
        """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_run where tenant_id = ? and id = ?\s\
        and updated_at < current_timestamp - interval '30 minutes'\
        """;
    final String selectConcatSql = """
        select concat('lock_until=', lock_until::text, ', now=', timezone('utc', current_timestamp)::text,\s\
        ', locked_by=', locked_by) from batch.shedlock where name = 'workflow_run_stuck_reconcile'\
        """;
    final String selectLockUntilSql = """
        select lock_until > timezone('utc', current_timestamp) + interval '4 minutes' from batch.shedlock\s\
        where name = 'workflow_run_stuck_reconcile'\
        """;
    final String selectLockedAtSql = """
        select locked_at::text from batch.shedlock where name = 'workflow_run_stuck_reconcile'\
        """;
    final String selectLockUntilSql2 = """
        select lock_until > timezone('utc', current_timestamp) from batch.shedlock\s\
        where name = 'workflow_run_stuck_reconcile'\
        """;
    final String selectLockedAtSql2 = """
        select locked_at::text from batch.shedlock where name = 'workflow_run_stuck_reconcile'\
        """;
    final String selectCountSql4 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\s\
        and node_status = 'SUCCESS'\
        """;
    final String selectCountSql5 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and job_instance_id is not null\
        """;
    final String selectCountSql6 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\s\
        and node_status = 'SUCCESS'\
        """;
    final String selectCountSql7 = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'\
        """;

    ClaimedTask claimed = launchAndClaim("workflow-resume-progress-recovery");
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectWorkflowRunIdSql,
        Long.class,
        TENANT,
        claimed.jobInstanceId());

    assertThat(postJson(
                "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT, Map.of())
            .statusCode())
        .isEqualTo(200);
    assertThat(postReport(claimed.taskId(), claimed.invocationId(), "pause before resume recovery")
            .statusCode())
        .isEqualTo(200);
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("PAUSED");

    doThrow(new IllegalStateException("simulated process loss after resume commit"))
        .when(workflowRunResumeProgressor)
        .resume(TENANT, workflowRunId);
    HttpResponse<String> interruptedResume = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT, Map.of());
    assertThat(interruptedResume.statusCode()).isBetween(500, 599);
    String selectedRunStatusRead2 =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatusRead2).isEqualTo("RUNNING");
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isZero();
    Integer selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, TENANT, workflowRunId);
    assertThat(selectedOutboxEventCount).isZero();

    Path lockHolderProperties =
        Files.createTempFile("workflow-reconciler-lock-holder-", ".properties");
    Path lockHolderReady = Files.createTempFile("workflow-reconciler-lock-holder-", ".ready");
    Path lockHolderStop = Files.createTempFile("workflow-reconciler-lock-holder-", ".stop");
    Path lockHolderLog = Files.createTempFile("workflow-reconciler-lock-holder-", ".log");
    Path childAProperties = Files.createTempFile("workflow-reconciler-a-", ".properties");
    Path childAReady = Files.createTempFile("workflow-reconciler-a-", ".ready");
    Path childAStop = Files.createTempFile("workflow-reconciler-a-", ".stop");
    Path childALog = Files.createTempFile("workflow-reconciler-a-", ".log");
    Path childBProperties = Files.createTempFile("workflow-reconciler-b-", ".properties");
    Path childBReady = Files.createTempFile("workflow-reconciler-b-", ".ready");
    Path childBStop = Files.createTempFile("workflow-reconciler-b-", ".stop");
    Path childBLog = Files.createTempFile("workflow-reconciler-b-", ".log");
    Files.delete(lockHolderReady);
    Files.delete(lockHolderStop);
    Files.delete(childAReady);
    Files.delete(childAStop);
    Files.delete(childBReady);
    Files.delete(childBStop);
    Process lockHolder = null;
    Process childA = null;
    Process childB = null;
    try {
      jdbcTemplate.update(
          updateWorkflowRunSql,
          TENANT,
          workflowRunId);
      Integer selectedWorkflowRunCount =
          jdbcTemplate.queryForObject(selectCountSql3, Integer.class, TENANT, workflowRunId);
      assertThat(selectedWorkflowRunCount).isEqualTo(1);
      doCallRealMethod().when(workflowRunResumeProgressor).resume(TENANT, workflowRunId);
      writeLockHolderChildProperties(lockHolderProperties);
      lockHolder = startLockHolderChild(
          lockHolderProperties, lockHolderReady, lockHolderStop, lockHolderLog);
      awaitReconcilerChildStarted(lockHolder, lockHolderReady, lockHolderLog, "lease holder");
      String lockLeaseSnapshot = jdbcTemplate.queryForObject(
          selectConcatSql,
          String.class);
      Boolean selectedLockUntil = jdbcTemplate.queryForObject(selectLockUntilSql, Boolean.class);
      assertThat(selectedLockUntil)
          .as(
              "the killed process must own the production five-minute lease; parent row=%s; "
                  + "holder marker=%s; holder log=%s",
              lockLeaseSnapshot, Files.readString(lockHolderReady), Files.readString(lockHolderLog))
          .isTrue();

      writeReconcilerChildProperties(childAProperties, "workflow-stuck-reconciler-a");
      writeReconcilerChildProperties(childBProperties, "workflow-stuck-reconciler-b");
      childA = startReconcilerChild(childAProperties, childAReady, childAStop, childALog);
      awaitReconcilerChildStarted(childA, childAReady, childALog, "instance A");
      childB = startReconcilerChild(childBProperties, childBReady, childBStop, childBLog);
      awaitReconcilerChildStarted(childB, childBReady, childBLog, "instance B");
      await()
          .during(Duration.ofSeconds(2))
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> {
            String selectedRunStatusRead3 =
                jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
            assertThat(selectedRunStatusRead3).isEqualTo("RUNNING");
          });
      String heldLockTimestamp = jdbcTemplate.queryForObject(
          selectLockedAtSql,
          String.class);
      lockHolder.destroyForcibly();
      assertThat(lockHolder.waitFor(10, TimeUnit.SECONDS))
          .as("the lease-holder JVM is killed without unlocking the row")
          .isTrue();
      Boolean selectedLockUntilRead2 =
          jdbcTemplate.queryForObject(selectLockUntilSql2, Boolean.class);
      assertThat(selectedLockUntilRead2).isTrue();
      await()
          .during(Duration.ofSeconds(2))
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> {
            String selectedRunStatusRead4 =
                jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
            assertThat(selectedRunStatusRead4).isEqualTo("RUNNING");
          });
      await()
          .atMost(Duration.ofSeconds(360))
          .untilAsserted(() -> {
            String selectedRunStatusRead5 =
                jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
            assertThat(selectedRunStatusRead5).isEqualTo("SUCCESS");
          });
      String selectedLockedAt = jdbcTemplate.queryForObject(selectLockedAtSql2, String.class);
      assertThat(selectedLockedAt).isNotEqualTo(heldLockTimestamp);
    } finally {
      if (lockHolder != null && lockHolder.isAlive()) {
        lockHolder.destroyForcibly();
        assertThat(lockHolder.waitFor(10, TimeUnit.SECONDS))
            .as("lease-holder JVM is terminated during test cleanup")
            .isTrue();
      }
      try {
        stopReconcilerChild(childA, childAStop, childALog);
      } finally {
        try {
          stopReconcilerChild(childB, childBStop, childBLog);
        } finally {
          Files.deleteIfExists(childAProperties);
          Files.deleteIfExists(childAReady);
          Files.deleteIfExists(childAStop);
          Files.deleteIfExists(childALog);
          Files.deleteIfExists(childBProperties);
          Files.deleteIfExists(childBReady);
          Files.deleteIfExists(childBStop);
          Files.deleteIfExists(childBLog);
          Files.deleteIfExists(lockHolderProperties);
          Files.deleteIfExists(lockHolderReady);
          Files.deleteIfExists(lockHolderStop);
          Files.deleteIfExists(lockHolderLog);
        }
      }
    }

    Integer selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql4, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1);
    Integer selectedWorkflowNodeRunCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql5, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead3).isEqualTo(1);
    await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
      String selectedRunStatusRead6 =
          jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
      assertThat(selectedRunStatusRead6).isEqualTo("SUCCESS");
      Integer selectedWorkflowNodeRunCountRead4 =
          jdbcTemplate.queryForObject(selectCountSql6, Integer.class, workflowRunId);
      assertThat(selectedWorkflowNodeRunCountRead4).isEqualTo(1);
      Integer selectedOutboxEventCountRead2 =
          jdbcTemplate.queryForObject(selectCountSql7, Integer.class, TENANT, workflowRunId);
      assertThat(selectedOutboxEventCountRead2).isEqualTo(1);
    });
  }

  @Test
  @DisplayName("暂停中的成功 REPORT 与 lease reclaim 并发时,旧 attempt 只会成功一次且资源收敛")
  void workflowPause_reportAndLeaseReclaimRace_convergesWithoutResourceDrift() throws Exception {
    final String selectWorkflowRunIdSql = """
        select nr.workflow_run_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and nr.job_instance_id = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectSuccessPartitionCountSql = """
        select success_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and job_instance_id is not null\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\s\
        and node_status = 'SUCCESS'\
        """;
    final String selectCountSql3 = """
        select count(*) from batch.job_task where tenant_id = ? and job_instance_id = ?
    """;
    final String selectCountSql4 = """
        select count(*) from batch.job_partition where tenant_id = ? and job_instance_id = ?\s\
        and partition_status = 'SUCCESS' and retry_count = 0\
        """;
    final String selectSuccessPartitionCountSql2 = """
        select success_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectCountSql5 = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL' and payload_json->>'runStatus' = 'SUCCESS'\
        """;

    ClaimedTask firstAttempt = launchAndClaim("workflow-pause-report-reclaim-race");
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectWorkflowRunIdSql,
        Long.class,
        TENANT,
        firstAttempt.jobInstanceId());

    assertThat(postJson(
                "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT, Map.of())
            .statusCode())
        .isEqualTo(200);
    jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        firstAttempt.partitionId());

    CountDownLatch ready = new CountDownLatch(R15_CONCURRENT_REPORT_COUNT + 1);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(R15_CONCURRENT_REPORT_COUNT + 1);
    Future<?> reclaim;
    List<Future<HttpResponse<String>>> reports = new ArrayList<>(R15_CONCURRENT_REPORT_COUNT);
    List<HttpResponse<String>> firstReports = new ArrayList<>(R15_CONCURRENT_REPORT_COUNT);
    try {
      reclaim = executor.submit(() -> {
        ready.countDown();
        start.await();
        partitionReclaimUnit.reclaim(
            jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));
        return null;
      });
      for (int index = 0; index < R15_CONCURRENT_REPORT_COUNT; index++) {
        reports.add(executor.submit(() -> {
          ready.countDown();
          start.await();
          return postReport(
              firstAttempt.taskId(),
              firstAttempt.invocationId(),
              "duplicate success racing paused lease reclaim");
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      reclaim.get(10, TimeUnit.SECONDS);
      for (Future<HttpResponse<String>> report : reports) {
        firstReports.add(report.get(10, TimeUnit.SECONDS));
      }
    } finally {
      executor.shutdownNow();
    }
    assertThat(firstReports)
        .hasSize(R15_CONCURRENT_REPORT_COUNT)
        .allSatisfy(response -> assertThat(response.statusCode()).isIn(200, 409));

    String taskStatus = jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus();
    if (TaskStatus.READY.code().equals(taskStatus)) {
      // 该分区会计入实例失败数。管理员取消分区的语义不同。
      // 报告端点会对终态/非运行中的重复请求返回 200，但不能
      assertThat(jobPartitionMapper
              .selectById(TENANT, firstAttempt.partitionId())
              .getPartitionStatus())
          .isEqualTo(PartitionStatus.READY.code());
      Integer selectedSuccessPartitionCount = jdbcTemplate.queryForObject(
          selectSuccessPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
      assertThat(selectedSuccessPartitionCount).isZero();
      JobTaskEntity reclaimedAttempt = awaitClaim(firstAttempt.taskId());
      String currentInvocation = jobPartitionMapper
          .selectById(TENANT, firstAttempt.partitionId())
          .getCurrentInvocationId();
      assertThat(currentInvocation).isNotBlank().isNotEqualTo(firstAttempt.invocationId());
      HttpResponse<String> staleReport = postReport(
          firstAttempt.taskId(), firstAttempt.invocationId(), "late stale report after reclaim");
      assertThat(staleReport.statusCode()).isEqualTo(409);
      assertThat(postReport(firstAttempt.taskId(), currentInvocation, "current attempt success")
              .statusCode())
          .isEqualTo(200);
      assertThat(reclaimedAttempt.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    } else {
      assertThat(taskStatus).isEqualTo(TaskStatus.SUCCESS.code());
      assertThat(firstReports)
          .anySatisfy(response -> assertThat(response.statusCode()).isEqualTo(200));
    }

    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("PAUSED");
    assertThat(postJson(
                "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT, Map.of())
            .statusCode())
        .isEqualTo(200);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          String selectedRunStatusRead2 =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
          assertThat(selectedRunStatusRead2).isEqualTo("SUCCESS");
        });

    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isEqualTo(1);
    Integer selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1);
    Integer selectedJobTaskCount = jdbcTemplate.queryForObject(
        selectCountSql3, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedJobTaskCount).isEqualTo(1);
    Integer selectedJobPartitionCount = jdbcTemplate.queryForObject(
        selectCountSql4, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedJobPartitionCount).isEqualTo(1);
    Integer selectedSuccessPartitionCountRead2 = jdbcTemplate.queryForObject(
        selectSuccessPartitionCountSql2, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedSuccessPartitionCountRead2).isEqualTo(1);
    Integer selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql5, Integer.class, TENANT, workflowRunId);
    assertThat(selectedOutboxEventCount).isEqualTo(1);
  }

  @Test
  @DisplayName("暂停期间 lease reclaim 拒绝旧 invocation,新 attempt 等恢复后只推进一次")
  void workflowPauseAndLeaseReclaim_rejectsStaleReport_andResumesCurrentAttempt() throws Exception {
    final String selectWorkflowRunIdSql = """
        select nr.workflow_run_id from batch.workflow_node_run nr\s\
        join batch.workflow_run wr on wr.id = nr.workflow_run_id where wr.tenant_id = ?\s\
        and nr.job_instance_id = ?\
        """;
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectRunStatusSql = """
        select run_status from batch.workflow_run where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and job_instance_id is not null\
        """;
    final String selectCountSql3 = """
        select count(*) from batch.workflow_node_run where workflow_run_id = ? and node_code = 'END'\s\
        and node_status = 'SUCCESS'\
        """;
    final String selectSuccessPartitionCountSql = """
        select success_partition_count from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectCountSql4 = """
        select count(*) from batch.job_task where tenant_id = ? and job_instance_id = ?
    """;
    final String selectCountSql5 = """
        select count(*) from batch.job_partition where tenant_id = ? and job_instance_id = ?\s\
        and partition_status = 'SUCCESS' and retry_count = 0\
        """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count + cancelled_partition_count + terminated_partition_count\s\
        from batch.job_instance where tenant_id = ? and id = ?\
        """;
    final String selectCountSql6 = """
        select count(*) from batch.outbox_event where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN'\s\
        and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL' and payload_json->>'runStatus' = 'SUCCESS'\
        """;

    ClaimedTask firstAttempt = launchAndClaim("workflow-pause-reclaim");
    Long workflowRunId = jdbcTemplate.queryForObject(
        selectWorkflowRunIdSql,
        Long.class,
        TENANT,
        firstAttempt.jobInstanceId());

    assertThat(postJson(
                "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT, Map.of())
            .statusCode())
        .isEqualTo(200);
    jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        firstAttempt.partitionId());
    partitionReclaimUnit.reclaim(jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));
    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.READY.code());

    JobTaskEntity secondAttempt = awaitClaim(firstAttempt.taskId());
    String currentInvocation =
        jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getCurrentInvocationId();
    assertThat(currentInvocation).isNotBlank().isNotEqualTo(firstAttempt.invocationId());

    HttpResponse<String> staleReport =
        postReport(firstAttempt.taskId(), firstAttempt.invocationId(), "stale while paused");
    assertThat(staleReport.statusCode()).isEqualTo(409);

    HttpResponse<String> currentReport =
        postReport(firstAttempt.taskId(), currentInvocation, "current while paused");
    assertThat(currentReport.statusCode()).isEqualTo(200);
    assertThat(secondAttempt.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    String selectedRunStatus =
        jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
    assertThat(selectedRunStatus).isEqualTo("PAUSED");
    Integer selectedWorkflowNodeRunCount =
        jdbcTemplate.queryForObject(selectCountSql, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCount).isZero();

    HttpResponse<String> resumeResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT, Map.of());
    assertThat(resumeResponse.statusCode()).isEqualTo(200);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> {
          String selectedRunStatusRead2 =
              jdbcTemplate.queryForObject(selectRunStatusSql, String.class, TENANT, workflowRunId);
          assertThat(selectedRunStatusRead2).isEqualTo("SUCCESS");
        });
    Integer selectedWorkflowNodeRunCountRead2 =
        jdbcTemplate.queryForObject(selectCountSql2, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead2).isEqualTo(1);
    Integer selectedWorkflowNodeRunCountRead3 =
        jdbcTemplate.queryForObject(selectCountSql3, Integer.class, workflowRunId);
    assertThat(selectedWorkflowNodeRunCountRead3).isEqualTo(1);
    Integer selectedSuccessPartitionCount = jdbcTemplate.queryForObject(
        selectSuccessPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedSuccessPartitionCount).isEqualTo(1);
    Integer selectedJobTaskCount = jdbcTemplate.queryForObject(
        selectCountSql4, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedJobTaskCount).isEqualTo(1);
    Integer selectedJobPartitionCount = jdbcTemplate.queryForObject(
        selectCountSql5, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedJobPartitionCount).isEqualTo(1);
    Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedFailedPartitionCount).isZero();
    Integer selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectCountSql6, Integer.class, TENANT, workflowRunId);
    assertThat(selectedOutboxEventCount).isEqualTo(1);
  }

  private ClaimedTask launchAndClaim(String tracePrefix) {
    final String insertWorkerRegistrySql = """
        insert into batch.worker_registry (tenant_id, worker_code, worker_group, capability_tags, status,\s\
        heartbeat_at, current_load) values (?, ?, 'IMPORT', '[]'::jsonb, 'ONLINE', ?, 0)\s\
        on conflict (tenant_id, worker_code) do update\s\
        set status = 'ONLINE', heartbeat_at = excluded.heartbeat_at\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?
    """;
    final String selectIdSql = """
        select id from batch.job_instance where tenant_id = ? and dedup_key = ?
    """;

    E2eScenarioFixture.LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    jdbcTemplate.update(
        insertWorkerRegistrySql,
        TENANT,
        WORKER,
        Timestamp.from(BatchDateTimeSupport.utcNow()));
    launchService.launch(new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        "r15-" + tracePrefix + "-" + seed.requestId(),
        Map.of()));
    outboxPublishSupport.publishAllPending(TENANT);
    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() -> {
          Integer selectedJobInstanceCount =
              jdbcTemplate.queryForObject(selectCountSql, Integer.class, TENANT, seed.dedupKey());
          assertThat(selectedJobInstanceCount).isEqualTo(1);
        });
    Long jobInstanceId = jdbcTemplate.queryForObject(
        selectIdSql,
        Long.class,
        TENANT,
        seed.dedupKey());
    JobInstanceEntity jobInstance = jobInstanceMapper.selectById(TENANT, jobInstanceId);
    JobTaskEntity task = jobTaskMapper
        .selectByQuery(new JobTaskQuery(TENANT, jobInstance.getId(), null, null, null))
        .get(0);
    JobPartitionEntity partition = jobPartitionMapper
        .selectByQuery(new JobPartitionQuery(TENANT, jobInstance.getId(), null, null))
        .get(0);
    awaitClaim(task.getId());
    String invocationId =
        jobPartitionMapper.selectById(TENANT, partition.getId()).getCurrentInvocationId();
    return new ClaimedTask(task.getId(), jobInstanceId, partition.getId(), invocationId);
  }

  private void writeReconcilerChildProperties(Path path, String applicationName) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("spring.application.name", applicationName);
    properties.setProperty("spring.datasource.url", platformJdbcUrl());
    properties.setProperty("spring.datasource.username", platformJdbcUsername());
    properties.setProperty("spring.datasource.password", platformJdbcPassword());
    properties.setProperty("spring.datasource.driver-class-name", "org.postgresql.Driver");
    properties.setProperty("spring.datasource.hikari.maximum-pool-size", "20");
    properties.setProperty("batch.datasource.business.url", businessJdbcUrl());
    properties.setProperty("batch.datasource.business.username", businessJdbcUsername());
    properties.setProperty("batch.datasource.business.password", businessJdbcPassword());
    properties.setProperty("batch.datasource.business.schema", "biz");
    properties.setProperty("spring.kafka.bootstrap-servers", kafkaBootstrapServers());
    properties.setProperty("spring.data.redis.host", redisHost());
    properties.setProperty("spring.data.redis.port", Integer.toString(redisPort()));
    properties.setProperty("batch.security.bypass-mode", "true");
    properties.setProperty("batch.security.internal-secret", internalSecret);
    properties.setProperty("batch.security.kms.default-key-ref", "DEFAULT_TEST");
    properties.setProperty("batch.security.kms.keys.DEFAULT_TEST", "AAAAAAAAAAAAAAAAAAAAAA==");
    properties.setProperty("spring.main.web-application-type", "none");
    properties.setProperty("server.port", "0");
    properties.setProperty("spring.flyway.enabled", "false");
    properties.setProperty("spring.quartz.auto-startup", "false");
    properties.setProperty("spring.kafka.listener.auto-startup", "false");
    properties.setProperty("batch.shedlock.auto-create", "false");
    properties.setProperty("batch.workflow.stuck-reconcile.enabled", "true");
    properties.setProperty("batch.workflow.stuck-reconcile.poll-interval-millis", "1000");
    properties.setProperty("batch.outbox.poll-interval-millis", "600000");
    properties.setProperty("batch.partition-lease.reclaim-interval-millis", "600000");
    properties.setProperty("batch.resource-scheduler.waiting-dispatch-interval-millis", "600000");
    properties.setProperty("batch.sensor.enabled", "false");
    properties.setProperty("batch.sla.enabled", "false");
    properties.setProperty("batch.worker.drain.enabled", "false");
    if ("filesystem".equals(storageBackend())) {
      properties.setProperty("batch.storage.backend", "filesystem");
      properties.setProperty("batch.storage.filesystem.root", filesystemRoot().toString());
      properties.setProperty(
          "batch.storage.filesystem.download-base-url",
          "http://localhost/api/console/files/fs-download");
      properties.setProperty(
          "batch.storage.filesystem.presign-secret", TEST_PRESIGN_SECRET);
      properties.setProperty("batch.storage.s3.bucket", s3Bucket());
    } else {
      properties.setProperty("batch.storage.backend", "s3");
      properties.setProperty("batch.storage.s3.endpoint", s3Endpoint());
      properties.setProperty("batch.storage.s3.access-key", s3AccessKey());
      properties.setProperty("batch.storage.s3.secret-key", s3SecretKey());
      properties.setProperty("batch.storage.s3.bucket", s3Bucket());
      properties.setProperty("batch.storage.s3.region", s3Region());
      properties.setProperty(
          "batch.storage.s3.path-style-enabled", Boolean.toString(s3PathStyleEnabled()));
    }
    try (var output = Files.newOutputStream(path)) {
      properties.store(output, "second WorkflowRunStuckReconciler JVM");
    }
  }

  private void writeLockHolderChildProperties(Path path) throws Exception {
    Properties properties = new Properties();
    properties.setProperty("spring.datasource.url", platformJdbcUrl());
    properties.setProperty("spring.datasource.username", platformJdbcUsername());
    properties.setProperty("spring.datasource.password", platformJdbcPassword());
    try (var output = Files.newOutputStream(path)) {
      properties.store(output, "ShedLock lease-holder JDBC connection");
    }
  }

  private Process startLockHolderChild(Path properties, Path ready, Path stop, Path log)
      throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    assertThat(classpath).isNotBlank();
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            ShedLockLeaseHolderChildProcess.class.getName(),
            properties.toString(),
            ready.toString(),
            stop.toString())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
  }

  private Process startReconcilerChild(Path properties, Path ready, Path stop, Path log)
      throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    assertThat(classpath).isNotBlank();
    return new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp",
            classpath,
            WorkflowStuckReconcilerChildProcess.class.getName(),
            properties.toString(),
            ready.toString(),
            stop.toString())
        .redirectErrorStream(true)
        .redirectOutput(log.toFile())
        .start();
  }

  private void awaitReconcilerChildStarted(
      Process process, Path ready, Path log, String instanceName) throws Exception {
    await().atMost(Duration.ofSeconds(90)).until(() -> Files.exists(ready) || !process.isAlive());
    assertThat(process.isAlive())
        .as("%s starts; log: %s", instanceName, Files.readString(log))
        .isTrue();
  }

  private void stopReconcilerChild(Process process, Path stop, Path log) throws Exception {
    if (process == null) {
      return;
    }
    Files.writeString(stop, "stop");
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      assertThat(process.waitFor(10, TimeUnit.SECONDS))
          .as(
              "second Orchestrator process is terminated after test; log: %s",
              Files.readString(log))
          .isTrue();
      return;
    }
    assertThat(process.exitValue())
        .as("second Orchestrator process exits cleanly; log: %s", Files.readString(log))
        .isZero();
  }

  private JobTaskEntity awaitClaim(Long taskId) {
    final JobTaskEntity[] claimed = new JobTaskEntity[1];
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      claimed[0] = taskExecutionService.assignWorker(TENANT, taskId, WORKER);
      assertThat(claimed[0]).isNotNull();
      assertThat(claimed[0].getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    });
    return claimed[0];
  }

  private void assertCancelReportReclaimRace(int round) throws Exception {
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String updateJobPartitionSql2 = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectCountSql = """
        select count(*) from batch.job_task where tenant_id = ? and job_instance_id = ?
    """;
    final String selectRetryCountSql = """
        select retry_count from batch.job_partition where tenant_id = ? and id = ?
    """;
    final String selectSuccessPartitionCountSql = """
        select success_partition_count from batch.job_instance where tenant_id = ? and id = ?
    """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count from batch.job_instance where tenant_id = ? and id = ?
    """;

    ClaimedTask firstAttempt = launchAndClaim("cancel-report-reclaim-race-" + round);
    jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        firstAttempt.partitionId());

    CountDownLatch ready = new CountDownLatch(3);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(3);
    Future<HttpResponse<String>> cancelRequest;
    Future<HttpResponse<String>> successReport;
    Future<Boolean> reclaim;
    try {
      cancelRequest = executor.submit(() -> {
        ready.countDown();
        start.await();
        return postJson(
            "/internal/tasks/" + firstAttempt.taskId() + "/cancel",
            Map.of("tenantId", TENANT, "reason", "R15 concurrent cancel/report/reclaim"));
      });
      successReport = executor.submit(() -> {
        ready.countDown();
        start.await();
        return postReport(
            firstAttempt.taskId(), firstAttempt.invocationId(), "concurrent success report");
      });
      reclaim = executor.submit(() -> {
        ready.countDown();
        start.await();
        try {
          partitionReclaimUnit.reclaim(
              jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));
          return true;
        } catch (ReclaimRetryableException retryNextCycle) {
          return false;
        }
      });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(cancelRequest.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
      assertThat(successReport.get(10, TimeUnit.SECONDS).statusCode()).isIn(200, 409);
      reclaim.get(10, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    JobTaskEntity task = jobTaskMapper.selectById(TENANT, firstAttempt.taskId());
    if (TaskStatus.RUNNING.code().equals(task.getTaskStatus())) {
      // 应用重复请求的结果。一旦领取 I2，同一旧调用必须被 409 围栏拒绝。
      // 若 NOWAIT 领取因 REPORT 正在执行而让出锁，则竞态结束后重试；不得让活动任务
      jdbcTemplate.update(
          updateJobPartitionSql2,
          TENANT,
          firstAttempt.partitionId());
      partitionReclaimUnit.reclaim(
          jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));
      task = jobTaskMapper.selectById(TENANT, firstAttempt.taskId());
    }

    if (TaskStatus.READY.code().equals(task.getTaskStatus())) {
      assertThat(jobPartitionMapper
              .selectById(TENANT, firstAttempt.partitionId())
              .getPartitionStatus())
          .isEqualTo(PartitionStatus.READY.code());
      JobTaskEntity secondAttempt = awaitClaim(firstAttempt.taskId());
      String currentInvocation = jobPartitionMapper
          .selectById(TENANT, firstAttempt.partitionId())
          .getCurrentInvocationId();
      assertThat(currentInvocation).isNotBlank().isNotEqualTo(firstAttempt.invocationId());
      assertThat(
              postReport(firstAttempt.taskId(), firstAttempt.invocationId(), "stale I1 after race")
                  .statusCode())
          .isEqualTo(409);
      HttpResponse<String> renewResponse = postJson(
          "/internal/tasks/" + firstAttempt.taskId() + "/renew",
          Map.of(
              "tenantId", TENANT,
              "workerId", WORKER,
              "partitionInvocationId", currentInvocation));
      assertThat(renewResponse.statusCode()).isEqualTo(200);
      boolean cancelRequested = Boolean.TRUE.equals(task.getCancelRequested());
      assertThat(renewResponse.body()).contains("\"cancelRequested\":" + cancelRequested);
      if (cancelRequested) {
        assertThat(postJson(
                    "/internal/tasks/" + firstAttempt.taskId() + "/report",
                    Map.of(
                        "tenantId",
                        TENANT,
                        "workerId",
                        WORKER,
                        "success",
                        false,
                        "errorCode",
                        "WORKER_EXECUTION_CANCELLED",
                        "message",
                        "I2 observed cancellation after concurrent reclaim",
                        "partitionInvocationId",
                        currentInvocation))
                .statusCode())
            .isEqualTo(200);
      } else {
        assertThat(postReport(firstAttempt.taskId(), currentInvocation, "I2 success after race")
                .statusCode())
            .isEqualTo(200);
      }
      assertThat(secondAttempt.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
      task = jobTaskMapper.selectById(TENANT, firstAttempt.taskId());
    }

    assertThat(task.getTaskStatus()).isIn(TaskStatus.SUCCESS.code(), TaskStatus.CANCELLED.code());
    String expectedPartitionStatus = TaskStatus.SUCCESS.code().equals(task.getTaskStatus())
        ? PartitionStatus.SUCCESS.code()
        : PartitionStatus.FAILED.code();
    assertThat(
            jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getPartitionStatus())
        .isEqualTo(expectedPartitionStatus);
    Integer selectedJobTaskCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedJobTaskCount).isEqualTo(1);
    Integer selectedRetryCount = jdbcTemplate.queryForObject(
        selectRetryCountSql, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedRetryCount).isZero();
    Integer selectedSuccessPartitionCount = jdbcTemplate.queryForObject(
        selectSuccessPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedSuccessPartitionCount)
        .isEqualTo(TaskStatus.SUCCESS.code().equals(task.getTaskStatus()) ? 1 : 0);
    Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedFailedPartitionCount)
        .isEqualTo(TaskStatus.CANCELLED.code().equals(task.getTaskStatus()) ? 1 : 0);
  }

  private void assertOrdinaryFailureReclaimRace(int round) throws Exception {
    final String updateJobPartitionSql = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String updateJobPartitionSql2 = """
        update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute'\s\
        where tenant_id = ? and id = ?\
        """;
    final String selectRetryCountSql = """
        select retry_count from batch.job_partition where tenant_id = ? and id = ?
    """;
    final String selectCountSql = """
        select count(*) from batch.retry_schedule where tenant_id = ? and related_type = 'JOB_PARTITION'\s\
        and related_id = ?\
        """;
    final String selectCountSql2 = """
        select count(*) from batch.dead_letter_task where tenant_id = ? and source_type = 'JOB_PARTITION'\s\
        and source_id = ? and replay_status = 'NEW' and max_replay_count = 0 and next_replay_at is null\
        """;
    final String selectFailedPartitionCountSql = """
        select failed_partition_count from batch.job_instance where tenant_id = ? and id = ?
    """;

    ClaimedTask firstAttempt = launchAndClaim("ordinary-failure-reclaim-race-" + round);
    jdbcTemplate.update(
        updateJobPartitionSql,
        TENANT,
        firstAttempt.partitionId());

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<HttpResponse<String>> failureReport;
    Future<Boolean> reclaim;
    try {
      failureReport = executor.submit(() -> {
        ready.countDown();
        start.await();
        return postJson(
            "/internal/tasks/" + firstAttempt.taskId() + "/report",
            Map.of(
                "tenantId",
                TENANT,
                "workerId",
                WORKER,
                "success",
                false,
                "errorCode",
                "R15_ORDINARY_FAILURE",
                "message",
                "ordinary failure raced with expired lease reclaim",
                "partitionInvocationId",
                firstAttempt.invocationId()));
      });
      reclaim = executor.submit(() -> {
        ready.countDown();
        start.await();
        try {
          partitionReclaimUnit.reclaim(
              jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));
          return true;
        } catch (ReclaimRetryableException retryNextCycle) {
          return false;
        }
      });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(failureReport.get(15, TimeUnit.SECONDS).statusCode()).isIn(200, 409);
      reclaim.get(15, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    JobTaskEntity task = jobTaskMapper.selectById(TENANT, firstAttempt.taskId());
    if (TaskStatus.RUNNING.code().equals(task.getTaskStatus())) {
      jdbcTemplate.update(
          updateJobPartitionSql2,
          TENANT,
          firstAttempt.partitionId());
      partitionReclaimUnit.reclaim(
          jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));
      task = jobTaskMapper.selectById(TENANT, firstAttempt.taskId());
    }
    if (TaskStatus.READY.code().equals(task.getTaskStatus())) {
      awaitClaim(firstAttempt.taskId());
      String currentInvocation = jobPartitionMapper
          .selectById(TENANT, firstAttempt.partitionId())
          .getCurrentInvocationId();
      assertThat(currentInvocation).isNotBlank().isNotEqualTo(firstAttempt.invocationId());
      assertThat(postReport(
                  firstAttempt.taskId(),
                  firstAttempt.invocationId(),
                  "stale I1 failure after reclaim")
              .statusCode())
          .isEqualTo(409);
      assertThat(postJson(
                  "/internal/tasks/" + firstAttempt.taskId() + "/report",
                  Map.of(
                      "tenantId",
                      TENANT,
                      "workerId",
                      WORKER,
                      "success",
                      false,
                      "errorCode",
                      "R15_ORDINARY_FAILURE",
                      "message",
                      "I2 ordinary failure after reclaim won the race",
                      "partitionInvocationId",
                      currentInvocation))
              .statusCode())
          .isEqualTo(200);
      task = jobTaskMapper.selectById(TENANT, firstAttempt.taskId());
    }

    assertThat(task.getTaskStatus()).isEqualTo(TaskStatus.FAILED.code());
    assertThat(task.getErrorCode()).isEqualTo("R15_ORDINARY_FAILURE");
    assertThat(
            jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.FAILED.code());
    Integer selectedRetryCount = jdbcTemplate.queryForObject(
        selectRetryCountSql, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedRetryCount).isZero();
    Integer selectedRetryScheduleCount = jdbcTemplate.queryForObject(
        selectCountSql, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedRetryScheduleCount).isZero();
    Integer selectedDeadLetterTaskCount = jdbcTemplate.queryForObject(
        selectCountSql2, Integer.class, TENANT, firstAttempt.partitionId());
    assertThat(selectedDeadLetterTaskCount).isEqualTo(1);
    Integer selectedFailedPartitionCount = jdbcTemplate.queryForObject(
        selectFailedPartitionCountSql, Integer.class, TENANT, firstAttempt.jobInstanceId());
    assertThat(selectedFailedPartitionCount).isEqualTo(1);
    assertThat(jobPartitionMapper.selectExpiredLeasesGlobal(
            PartitionStatus.READY.code(), PartitionStatus.RUNNING.code(), 100))
        .noneMatch(candidate -> firstAttempt.partitionId().equals(candidate.getId()));
  }

  private HttpResponse<String> postReport(Long taskId, String invocationId, String summary)
      throws Exception {
    return postJson(
        "/internal/tasks/" + taskId + "/report",
        Map.of(
            "tenantId", TENANT,
            "workerId", WORKER,
            "success", true,
            "resultSummary", "{\"attempt\":\"" + summary + "\"}",
            "partitionInvocationId", invocationId));
  }

  private HttpResponse<String> postJson(String path, Map<String, Object> payload) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + localServerPort + path))
        .header("Content-Type", "application/json")
        .header("X-Internal-Secret", internalSecret)
        .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(payload)))
        .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private record ClaimedTask(
      Long taskId, Long jobInstanceId, Long partitionId, String invocationId) {}
}
