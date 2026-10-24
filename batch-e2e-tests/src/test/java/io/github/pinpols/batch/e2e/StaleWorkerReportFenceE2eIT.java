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
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionReclaimUnit;
import io.github.pinpols.batch.orchestrator.application.service.task.WorkflowRunResumeProgressor;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Exercises the stale-attempt report fence through the real internal HTTP endpoint and PostgreSQL. */
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
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

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

  @Autowired
  private LockProvider lockProvider;

  @MockitoSpyBean
  private WorkflowRunResumeProgressor workflowRunResumeProgressor;

  @Value("${batch.security.internal-secret}")
  private String internalSecret;

  @Test
  @DisplayName("重认领后旧 invocation 的 HTTP REPORT 返回冲突,新 invocation 成功且状态未被旧结果污染")
  void staleInvocationHttpReport_isRejectedAfterReclaim_andCurrentAttemptCompletes() throws Exception {
    ClaimedTask claimed = launchAndClaim("stale-report");
    Long taskId = claimed.taskId();
    Long partitionId = claimed.partitionId();
    String oldInvocation = claimed.invocationId();
    assertThat(oldInvocation).isNotBlank();

    jdbcTemplate.update(
        "update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute' "
            + "where tenant_id = ? and id = ?",
        TENANT,
        partitionId);
    partitionReclaimUnit.reclaim(jobPartitionMapper.selectById(TENANT, partitionId));
    assertThat(jobTaskMapper.selectById(TENANT, taskId).getTaskStatus())
        .isEqualTo(TaskStatus.READY.code());
    assertThat(jobPartitionMapper.selectById(TENANT, partitionId).getPartitionStatus())
        .isEqualTo(PartitionStatus.READY.code());

    JobTaskEntity secondClaim = awaitClaim(taskId);
    assertThat(secondClaim.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    String currentInvocation = jobPartitionMapper.selectById(TENANT, partitionId)
        .getCurrentInvocationId();
    assertThat(currentInvocation).isNotBlank().isNotEqualTo(oldInvocation);

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
  @DisplayName("HTTP 取消请求由 Worker 心跳观察后回报取消,任务/分区/实例计数收敛")
  void cancellationHttpRequest_isObservedByRenew_andCancelledReportSettlesResources() throws Exception {
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
            "tenantId", TENANT,
            "workerId", WORKER,
            "success", false,
            "errorCode", "WORKER_EXECUTION_CANCELLED",
            "message", "worker stopped after observing cancellation",
            "partitionInvocationId", claimed.invocationId()));
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.CANCELLED.code());
    // Worker cooperative cancellation retains the production contract: task=CANCELLED while its
    // partition contributes to the instance's failed count. Admin partition cancellation differs.
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.FAILED.code());
    assertThat(jdbcTemplate.queryForObject(
            "select instance_status from batch.job_instance where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            claimed.jobInstanceId()))
        .isEqualTo("FAILED");
    assertThat(jdbcTemplate.queryForObject(
            "select failed_partition_count from batch.job_instance "
                + "where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            claimed.jobInstanceId()))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select cancelled_partition_count from batch.job_instance "
                + "where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            claimed.jobInstanceId()))
        .isZero();
  }

  @Test
  @DisplayName("取消 HTTP 与成功 REPORT 并发时,任务仍唯一终结且资源计数不重复")
  void cancellationAndSuccessReport_raceOverHttp_preservesSingleTerminalOutcome() throws Exception {
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
    assertThat(jdbcTemplate.queryForObject(
            "select instance_status from batch.job_instance where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            claimed.jobInstanceId()))
        .isEqualTo("SUCCESS");
    assertThat(jdbcTemplate.queryForObject(
            "select success_partition_count from batch.job_instance "
                + "where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            claimed.jobInstanceId()))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select failed_partition_count + cancelled_partition_count "
                + "from batch.job_instance where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            claimed.jobInstanceId()))
        .isZero();
  }

  @Test
  @DisplayName("WorkflowRun 暂停不杀在途任务,恢复后真实 DAG 推进至成功终态")
  void workflowPauseAndResumeOverHttp_preservesInflightTask_andCompletesDag() throws Exception {
    ClaimedTask claimed = launchAndClaim("workflow-pause-resume");
    Long workflowRunId = jdbcTemplate.queryForObject(
        "select nr.workflow_run_id from batch.workflow_node_run nr "
            + "join batch.workflow_run wr on wr.id = nr.workflow_run_id "
            + "where wr.tenant_id = ? and nr.job_instance_id = ?",
        Long.class,
        TENANT,
        claimed.jobInstanceId());

    HttpResponse<String> pauseResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT,
        Map.of());
    assertThat(pauseResponse.statusCode()).isEqualTo(200);
    assertThat(pauseResponse.body()).contains("\"status\":\"PAUSED\"");
    assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("PAUSED");
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.RUNNING.code());

    HttpResponse<String> reportResponse =
        postReport(claimed.taskId(), claimed.invocationId(), "completed while paused");
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("PAUSED");

    HttpResponse<String> resumeResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT,
        Map.of());
    assertThat(resumeResponse.statusCode()).isEqualTo(200);
    assertThat(resumeResponse.body()).contains("\"status\":\"RUNNING\"");
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("SUCCESS"));
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'",
            Integer.class,
            workflowRunId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Workflow pause 与在途成功 REPORT 并发后,恢复仍只推进一次并正确记账")
  void workflowPauseAndSuccessfulReport_raceOverHttp_convergesOnce() throws Exception {
    ClaimedTask claimed = launchAndClaim("workflow-pause-report-race");
    Long workflowRunId = jdbcTemplate.queryForObject(
        "select nr.workflow_run_id from batch.workflow_node_run nr "
            + "join batch.workflow_run wr on wr.id = nr.workflow_run_id "
            + "where wr.tenant_id = ? and nr.job_instance_id = ?",
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
            "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT,
            Map.of());
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
        "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
        String.class,
        TENANT,
        workflowRunId);
    if ("PAUSED".equals(runStatus)) {
      HttpResponse<String> resumeResponse = postJson(
          "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT,
          Map.of());
      assertThat(resumeResponse.statusCode()).isEqualTo(200);
    }
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("SUCCESS"));
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.SUCCESS.code());
    assertThat(jobPartitionMapper.selectById(TENANT, claimed.partitionId()).getPartitionStatus())
        .isEqualTo(PartitionStatus.SUCCESS.code());
    assertThat(jdbcTemplate.queryForObject(
            "select success_partition_count from batch.job_instance "
                + "where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            claimed.jobInstanceId()))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'",
            Integer.class,
            workflowRunId))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and job_instance_id is not null",
            Integer.class,
            workflowRunId))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("暂停期间 Job 协作取消后恢复不重派失败节点")
  void workflowResumeAfterCancelledInflightJob_doesNotRedispatchFailedNode() throws Exception {
    ClaimedTask claimed = launchAndClaim("workflow-pause-cancel-resume");
    Long workflowRunId = jdbcTemplate.queryForObject(
        "select nr.workflow_run_id from batch.workflow_node_run nr "
            + "join batch.workflow_run wr on wr.id = nr.workflow_run_id "
            + "where wr.tenant_id = ? and nr.job_instance_id = ?",
        Long.class,
        TENANT,
        claimed.jobInstanceId());

    HttpResponse<String> pauseResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT,
        Map.of());
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
            "tenantId", TENANT,
            "workerId", WORKER,
            "success", false,
            "errorCode", "WORKER_EXECUTION_CANCELLED",
            "message", "cancelled while WorkflowRun was paused",
            "partitionInvocationId", claimed.invocationId()));
    assertThat(reportResponse.statusCode()).isEqualTo(200);
    assertThat(jobTaskMapper.selectById(TENANT, claimed.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.CANCELLED.code());
    assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("PAUSED");

    HttpResponse<String> resumeResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT,
        Map.of());
    assertThat(resumeResponse.statusCode()).isEqualTo(200);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
            jdbcTemplate.queryForObject(
                "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
                String.class,
                TENANT,
                workflowRunId))
        .isEqualTo("FAILED"));
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and job_instance_id is not null",
            Integer.class,
            workflowRunId))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select failed_partition_count from batch.job_instance "
                + "where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            claimed.jobInstanceId()))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'",
            Integer.class,
            workflowRunId))
        .isZero();
  }

  @Test
  @DisplayName("resume 提交后进程丢失 DAG 推进时,stuck reconciler 从持久节点恢复")
  void workflowResumeProgressLostAfterCommit_isRecoveredFromPersistedNodeState() throws Exception {
    ClaimedTask claimed = launchAndClaim("workflow-resume-progress-recovery");
    Long workflowRunId = jdbcTemplate.queryForObject(
        "select nr.workflow_run_id from batch.workflow_node_run nr "
            + "join batch.workflow_run wr on wr.id = nr.workflow_run_id "
            + "where wr.tenant_id = ? and nr.job_instance_id = ?",
        Long.class,
        TENANT,
        claimed.jobInstanceId());

    assertThat(postJson(
            "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT,
            Map.of())
        .statusCode())
        .isEqualTo(200);
    assertThat(postReport(claimed.taskId(), claimed.invocationId(), "pause before resume recovery")
            .statusCode())
        .isEqualTo(200);
    assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("PAUSED");

    doThrow(new IllegalStateException("simulated process loss after resume commit"))
        .when(workflowRunResumeProgressor)
        .resume(TENANT, workflowRunId);
    HttpResponse<String> interruptedResume = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT,
        Map.of());
    assertThat(interruptedResume.statusCode()).isBetween(500, 599);
    assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("RUNNING");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and node_code = 'END'",
            Integer.class,
            workflowRunId))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.outbox_event "
                + "where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN' "
                + "and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'",
            Integer.class,
            TENANT,
            workflowRunId))
        .isZero();

    AtomicReference<SimpleLock> heldReconcilerLock = new AtomicReference<>();
    await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
      Optional<SimpleLock> acquired = lockProvider.lock(new LockConfiguration(
          Instant.now(),
          "workflow_run_stuck_reconcile",
          Duration.ofSeconds(60),
          Duration.ZERO));
      assertThat(acquired).isPresent();
      heldReconcilerLock.set(acquired.orElseThrow());
    });
    Path childAProperties = Files.createTempFile("workflow-reconciler-a-", ".properties");
    Path childAReady = Files.createTempFile("workflow-reconciler-a-", ".ready");
    Path childAStop = Files.createTempFile("workflow-reconciler-a-", ".stop");
    Path childALog = Files.createTempFile("workflow-reconciler-a-", ".log");
    Path childBProperties = Files.createTempFile("workflow-reconciler-b-", ".properties");
    Path childBReady = Files.createTempFile("workflow-reconciler-b-", ".ready");
    Path childBStop = Files.createTempFile("workflow-reconciler-b-", ".stop");
    Path childBLog = Files.createTempFile("workflow-reconciler-b-", ".log");
    Files.delete(childAReady);
    Files.delete(childAStop);
    Files.delete(childBReady);
    Files.delete(childBStop);
    Process childA = null;
    Process childB = null;
    try {
      jdbcTemplate.update(
          "update batch.workflow_run set updated_at = current_timestamp - interval '2 hours' "
              + "where tenant_id = ? and id = ?",
          TENANT,
          workflowRunId);
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.workflow_run "
                  + "where tenant_id = ? and id = ? and updated_at "
                  + "< current_timestamp - interval '30 minutes'",
              Integer.class,
              TENANT,
              workflowRunId))
          .isEqualTo(1);
      doCallRealMethod().when(workflowRunResumeProgressor).resume(TENANT, workflowRunId);
      writeReconcilerChildProperties(childAProperties, "workflow-stuck-reconciler-a");
      writeReconcilerChildProperties(childBProperties, "workflow-stuck-reconciler-b");
      childA = startReconcilerChild(childAProperties, childAReady, childAStop, childALog);
      awaitReconcilerChildStarted(childA, childAReady, childALog, "instance A");
      childB = startReconcilerChild(childBProperties, childBReady, childBStop, childBLog);
      awaitReconcilerChildStarted(childB, childBReady, childBLog, "instance B");
      await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(() ->
          assertThat(jdbcTemplate.queryForObject(
                  "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
                  String.class,
                  TENANT,
                  workflowRunId))
              .isEqualTo("RUNNING"));
      String heldLockTimestamp = jdbcTemplate.queryForObject(
          "select locked_at::text from batch.shedlock "
              + "where name = 'workflow_run_stuck_reconcile'",
          String.class);
      heldReconcilerLock.getAndSet(null).unlock();
      await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
              "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("SUCCESS"));
      assertThat(jdbcTemplate.queryForObject(
              "select locked_at::text from batch.shedlock "
                  + "where name = 'workflow_run_stuck_reconcile'",
              String.class))
          .isNotEqualTo(heldLockTimestamp);
    } finally {
      SimpleLock remainingLock = heldReconcilerLock.getAndSet(null);
      if (remainingLock != null) {
        remainingLock.unlock();
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
        }
      }
    }

    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'",
            Integer.class,
            workflowRunId))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and job_instance_id is not null",
            Integer.class,
            workflowRunId))
        .isEqualTo(1);
    await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
      assertThat(jdbcTemplate.queryForObject(
              "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
              String.class,
              TENANT,
              workflowRunId))
          .isEqualTo("SUCCESS");
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.workflow_node_run "
                  + "where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'",
              Integer.class,
              workflowRunId))
          .isEqualTo(1);
      assertThat(jdbcTemplate.queryForObject(
              "select count(*) from batch.outbox_event "
                  + "where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN' "
                  + "and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL'",
              Integer.class,
              TENANT,
              workflowRunId))
          .isEqualTo(1);
    });
  }

  @Test
  @DisplayName("暂停期间 lease reclaim 拒绝旧 invocation,新 attempt 等恢复后只推进一次")
  void workflowPauseAndLeaseReclaim_rejectsStaleReport_andResumesCurrentAttempt()
      throws Exception {
    ClaimedTask firstAttempt = launchAndClaim("workflow-pause-reclaim");
    Long workflowRunId = jdbcTemplate.queryForObject(
        "select nr.workflow_run_id from batch.workflow_node_run nr "
            + "join batch.workflow_run wr on wr.id = nr.workflow_run_id "
            + "where wr.tenant_id = ? and nr.job_instance_id = ?",
        Long.class,
        TENANT,
        firstAttempt.jobInstanceId());

    assertThat(postJson(
            "/internal/workflow-runs/" + workflowRunId + "/pause?tenantId=" + TENANT,
            Map.of())
        .statusCode())
        .isEqualTo(200);
    jdbcTemplate.update(
        "update batch.job_partition set lease_expire_at = current_timestamp - interval '1 minute' "
            + "where tenant_id = ? and id = ?",
        TENANT,
        firstAttempt.partitionId());
    partitionReclaimUnit.reclaim(
        jobPartitionMapper.selectById(TENANT, firstAttempt.partitionId()));
    assertThat(jobTaskMapper.selectById(TENANT, firstAttempt.taskId()).getTaskStatus())
        .isEqualTo(TaskStatus.READY.code());

    JobTaskEntity secondAttempt = awaitClaim(firstAttempt.taskId());
    String currentInvocation = jobPartitionMapper
        .selectById(TENANT, firstAttempt.partitionId())
        .getCurrentInvocationId();
    assertThat(currentInvocation).isNotBlank().isNotEqualTo(firstAttempt.invocationId());

    HttpResponse<String> staleReport =
        postReport(firstAttempt.taskId(), firstAttempt.invocationId(), "stale while paused");
    assertThat(staleReport.statusCode()).isEqualTo(409);

    HttpResponse<String> currentReport =
        postReport(firstAttempt.taskId(), currentInvocation, "current while paused");
    assertThat(currentReport.statusCode()).isEqualTo(200);
    assertThat(secondAttempt.getTaskStatus()).isEqualTo(TaskStatus.RUNNING.code());
    assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("PAUSED");
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and node_code = 'END'",
            Integer.class,
            workflowRunId))
        .isZero();

    HttpResponse<String> resumeResponse = postJson(
        "/internal/workflow-runs/" + workflowRunId + "/resume?tenantId=" + TENANT,
        Map.of());
    assertThat(resumeResponse.statusCode()).isEqualTo(200);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
            "select run_status from batch.workflow_run where tenant_id = ? and id = ?",
            String.class,
            TENANT,
            workflowRunId))
        .isEqualTo("SUCCESS"));
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and job_instance_id is not null",
            Integer.class,
            workflowRunId))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.workflow_node_run "
                + "where workflow_run_id = ? and node_code = 'END' and node_status = 'SUCCESS'",
            Integer.class,
            workflowRunId))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select success_partition_count from batch.job_instance "
                + "where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            firstAttempt.jobInstanceId()))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.job_task where tenant_id = ? and job_instance_id = ?",
            Integer.class,
            TENANT,
            firstAttempt.jobInstanceId()))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.job_partition where tenant_id = ? and job_instance_id = ? "
                + "and partition_status = 'SUCCESS' and retry_count = 0",
            Integer.class,
            TENANT,
            firstAttempt.jobInstanceId()))
        .isEqualTo(1);
    assertThat(jdbcTemplate.queryForObject(
            "select failed_partition_count + cancelled_partition_count + "
                + "terminated_partition_count from batch.job_instance "
                + "where tenant_id = ? and id = ?",
            Integer.class,
            TENANT,
            firstAttempt.jobInstanceId()))
        .isZero();
    assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.outbox_event "
                + "where tenant_id = ? and aggregate_type = 'WORKFLOW_RUN' "
                + "and aggregate_id = ? and event_type = 'WORKFLOW_TERMINAL' "
                + "and payload_json->>'runStatus' = 'SUCCESS'",
            Integer.class,
            TENANT,
            workflowRunId))
        .isEqualTo(1);
  }

  private ClaimedTask launchAndClaim(String tracePrefix) {
    E2eScenarioFixture.LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "IMPORT", TriggerType.API);
    jdbcTemplate.update(
        "insert into batch.worker_registry (tenant_id, worker_code, worker_group, "
            + "capability_tags, status, heartbeat_at, current_load) "
            + "values (?, ?, 'IMPORT', '[]'::jsonb, 'ONLINE', ?, 0) "
            + "on conflict (tenant_id, worker_code) do update set status = 'ONLINE', "
            + "heartbeat_at = excluded.heartbeat_at",
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
    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(jdbcTemplate.queryForObject(
            "select count(*) from batch.job_instance where tenant_id = ? and dedup_key = ?",
            Integer.class,
            TENANT,
            seed.dedupKey()))
        .isEqualTo(1));
    Long jobInstanceId = jdbcTemplate.queryForObject(
        "select id from batch.job_instance where tenant_id = ? and dedup_key = ?",
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
    String invocationId = jobPartitionMapper.selectById(TENANT, partition.getId())
        .getCurrentInvocationId();
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
          "batch.storage.filesystem.presign-secret", "test-fs-presign-secret-0123456789abcdef");
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

  private Process startReconcilerChild(Path properties, Path ready, Path stop, Path log)
      throws Exception {
    String classpath = System.getProperty(
        "surefire.test.class.path", System.getProperty("java.class.path"));
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
    await().atMost(Duration.ofSeconds(90)).until(() ->
        Files.exists(ready) || !process.isAlive());
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
          .as("second Orchestrator process shuts down; log: %s", Files.readString(log))
          .isTrue();
      throw new IllegalStateException("second Orchestrator process did not stop cleanly");
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

  private record ClaimedTask(Long taskId, Long jobInstanceId, Long partitionId, String invocationId) {}
}
