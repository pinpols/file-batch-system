package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleAuthTokenResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.console.shared.client.OrchestratorInternalRestClient;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.TestContainerLabels;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.client.ResourceAccessException;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Console HTTP 审批、补偿接纳及 Worker 恢复的端到端验证。
 *
 * <p>链路：
 *
 * <pre>
 * JDBC 夹具建立源实例/分区/任务与 dead letter → Console 查询/申请/审批
 *   → 生产补偿接纳 → 新根 Workflow → Kafka/CLAIM/Worker/REPORT → 持久化结果及审计回读
 * </pre>
 *
 * <p>UNKNOWN 来源由夹具建立，不证明远端实际发生过模糊结果；SFTP 用例使用独立 Dispatch Worker JVM
 * 和真实 SFTP 服务，验证授权后新根执行及一次性发送。
 */
@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.security.bypass-mode=true",
      "batch.console.security.default-tenant-id=t1",
      "batch.console.security.allowed-tenants=t1",
      "batch.console.orchestrator.base-url=http://127.0.0.1:${local.server.port}",
      "batch.console.trigger.base-url=http://127.0.0.1:${local.server.port}"
    })
@E2eBusinessSchema
@Sql(
    scripts = {
      E2eTestSql.IMPORT_TEMPLATE_SEED,
    })
@Tag("critical")
@DisplayName("死信人工闭环端到端:任务进入死信后由控制台发起重放审批并审批通过,验证重放重新入队生效,死信状态与重放次数更新,审批单流转到已执行")
class DeadLetterApprovalReplayE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String BASE_TEMPLATE_CODE = "IMP-CUSTOMER-JSON-ARRAY";
  private static final String REPLAY_TEMPLATE_CODE = "IMP-CUSTOMER-JSON-ARRAY-DLQ-E2E";
  private static final String APPROVAL_OPERATOR = "e2e-ops";

  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  @Autowired
  private JdbcTemplate jdbcTemplate;

  private final DataSource businessDataSource;

  @Autowired
  private E2eOutboxPublishSupport e2eOutboxPublishSupport;

  @Autowired
  private ObjectMapper objectMapper;

  @Value("${local.server.port}")
  private int localServerPort;

  private ConfigurableApplicationContext securedConsoleContext;

  private int securedConsolePort;

  private String consoleJwt;
  private String consoleCsrf;

  @Autowired
  DeadLetterApprovalReplayE2eIT(
      @Qualifier("importBusinessDataSource") DataSource businessDataSource) {
    this.businessDataSource = businessDataSource;
  }

  @BeforeEach
  void authenticateConsoleClient() throws Exception {
    securedConsoleContext = startSecuredConsole();
    securedConsolePort =
        Integer.parseInt(securedConsoleContext.getEnvironment().getProperty("local.server.port"));
    ConsoleJwtService securedConsoleJwtService =
        securedConsoleContext.getBean(ConsoleJwtService.class);
    HttpResponse<Void> unauthenticatedCheck = HTTP_CLIENT.send(
        HttpRequest.newBuilder()
            .uri(consoleUri("/api/console/auth/check"))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());
    assertThat(unauthenticatedCheck.statusCode()).isEqualTo(401);

    ConsoleAuthTokenResponse issuedToken =
        securedConsoleJwtService.issueToken(APPROVAL_OPERATOR, TENANT, Set.of("ROLE_ADMIN"));
    consoleJwt = issuedToken.accessToken();
    HttpResponse<Void> authenticatedCheck = HTTP_CLIENT.send(
        HttpRequest.newBuilder()
            .uri(consoleUri("/api/console/auth/check"))
            .header("Cookie", "batch_console_token=" + consoleJwt)
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());
    assertThat(authenticatedCheck.statusCode()).isEqualTo(204);

    String csrfCookie = authenticatedCheck.headers().allValues("Set-Cookie").stream()
        .filter(value -> value.startsWith("XSRF-TOKEN="))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("authenticated Console response must issue CSRF cookie"));
    int cookieEnd = csrfCookie.indexOf(';');
    String csrfValue = cookieEnd < 0 ? csrfCookie : csrfCookie.substring(0, cookieEnd);
    consoleCsrf = csrfValue.substring("XSRF-TOKEN=".length());
    assertThat(consoleCsrf).isNotBlank();
  }

  @AfterEach
  void stopSecuredConsole() {
    if (securedConsoleContext != null) {
      securedConsoleContext.close();
      securedConsoleContext = null;
    }
  }

  @Test
  @DisplayName("死信初始为待重放且来源为作业分片,控制台发起重放并审批通过后:验证原记录重放状态转为成功,重放次数为 1,审批单状态转为已执行")
  void shouldMarkDeadLetterReplaySuccess_whenConsoleApprovalExecuted() throws Exception {
    String initialTraceId = traceId("dlq");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);

    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", REPLAY_TEMPLATE_CODE);
    params.put("bizType", "CUSTOMER");
    params.put(
        "content",
        "[{\"customerNo\":\"DLQ-E2E-001\",\"customerName\":\"Dead Letter User\","
            + "\"customerType\":\"PERSONAL\",\"certificateNo\":\"ID-20260402-0001\","
            + "\"mobileNo\":\"13800000001\",\"email\":\"dlq@example.com\",\"status\":\"ACTIVE\"}]");

    long deadLetterId = seedReplayGraph(seed, initialTraceId, params);

    JsonNode deadLetters = getConsoleJson("/api/console/queries/dead-letters?tenantId=t1&traceId="
        + encode(initialTraceId)
        + "&pageNo=1&pageSize=10");
    JsonNode deadLetter = firstItem(deadLetters);
    String deadLetterTraceId = deadLetter.path("traceId").asText();
    assertThat(deadLetterId).isPositive();
    assertThat(deadLetterTraceId).isEqualTo(initialTraceId);
    assertThat(deadLetter.path("sourceType").asText()).isEqualTo("JOB_PARTITION");
    assertThat(deadLetter.path("replayStatus").asText()).isEqualTo("NEW");

    insertReplayTemplate();

    String replayApprovalNo = extractDataText(postConsoleJson(
        "/api/console/jobs/dead-letters/replay",
        """
                {"tenantId":"t1","deadLetterId":%d,"reason":"fix template and replay","operatorId":"%s"}
                """.formatted(deadLetterId, APPROVAL_OPERATOR),
        idempotencyKey("dead-letter-replay")));

    JsonNode pendingApprovals =
        getConsoleJson("/api/console/queries/approvals?tenantId=t1&approvalNo="
            + encode(replayApprovalNo)
            + "&approvalStatus=PENDING&pageNo=1&pageSize=10");
    JsonNode pendingApproval = firstItem(pendingApprovals);
    assertThat(pendingApproval.path("approvalNo").asText()).isEqualTo(replayApprovalNo);
    assertThat(pendingApproval.path("approvalStatus").asText()).isEqualTo("PENDING");
    assertThat(pendingApproval.path("actionType").asText()).isEqualTo("DLQ_REPLAY");

    JsonNode approveResponse = postConsoleJson(
        "/api/console/approvals/" + encode(replayApprovalNo) + "/approve",
        """
            {"tenantId":"t1","operatorId":"%s","reason":"approved for replay"}
            """.formatted(APPROVAL_OPERATOR),
        idempotencyKey("dead-letter-approve"));
    String replayCommandNo = extractDataText(approveResponse);
    assertThat(replayCommandNo).isNotBlank();

    // approve() 已同步触发补偿并将 dead letter 置为 SUCCESS；
    // publishAllPending 驱动 outbox 投递，findItemById 精确定位原始记录，
    // 避免 import worker 失败后新生成的同 traceId dead letter 干扰断言。
    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          e2eOutboxPublishSupport.publishAllPending(TENANT);
          JsonNode completedDeadLetter = findItemById(
              getConsoleJson("/api/console/queries/dead-letters?tenantId=t1&traceId="
                  + encode(deadLetterTraceId)
                  + "&pageNo=1&pageSize=10"),
              deadLetterId);
          assertThat(completedDeadLetter.path("replayStatus").asText()).isEqualTo("SUCCESS");
          assertThat(completedDeadLetter.path("replayCount").asInt()).isEqualTo(1);
        });

    JsonNode executedApproval =
        firstItem(getConsoleJson("/api/console/queries/approvals?tenantId=t1&approvalNo="
            + encode(replayApprovalNo)
            + "&pageNo=1&pageSize=10"));
    assertThat(executedApproval.path("approvalStatus").asText()).isEqualTo("EXECUTED");
  }

  @Test
  @DisplayName("认证操作员核实外部动作未执行后经审批仅重试一次并保存证据")
  void shouldReplayUnknownExternalOutcomeOnce_whenVerifiedNotExecutedThroughConsoleApproval()
      throws Exception {
    final String selectSourceIdSql = """
            select source_id, payload_ref from batch.dead_letter_task where tenant_id=? and id=?
        """;
    final String selectJobInstanceIdSql = """
            select job_instance_id from batch.job_task where tenant_id=? and id=?
        """;
    final String updateJobTaskSql = """
            update batch.job_task set task_status='FAILED', error_code='EXTERNAL_OUTCOME_UNKNOWN',\s\
            failure_class='UNKNOWN', error_message='remote action outcome is unknown', finished_at=now(),\s\
            version=version+1 where tenant_id=? and id=?\
            """;
    final String updateJobPartitionSql = """
            update batch.job_partition set partition_status='FAILED', version=version+1 where tenant_id=?\s\
            and id=?\
            """;
    final String updateJobInstanceSql = """
            update batch.job_instance set instance_status='FAILED', failure_class='UNKNOWN',\s\
            failed_partition_count=1, finished_at=now(), version=version+1 where tenant_id=? and id=?\
            """;
    final String updateDeadLetterTaskSql = """
            update batch.dead_letter_task set max_replay_count=0, next_replay_at=null where tenant_id=? and id=?\
            """;
    final String selectCountSql = """
            select count(*) from batch.outbox_event where tenant_id=? and aggregate_type='JOB_TASK'\s\
            and aggregate_id=?
        """;
    final String selectCountSql2 = """
            select count(*) from batch.outbox_event where tenant_id=? and event_type='WORKFLOW_LAUNCH'
        """;
    final String selectTaskStatusSql = """
            select task_status, error_code, external_outcome_resolution_version,
                   external_outcome_resolution_trigger_request_id
            from batch.job_task\s\
            where tenant_id=? and id=?\
            """;
    final String selectOriginalTaskStatusSql = """
            select task_status from batch.job_task where tenant_id=? and id=?
        """;
    final String selectVersionSql = """
            select version from batch.job_task where tenant_id=? and id=?
        """;
    final String selectInstanceStatusSql = """
            select instance_status from batch.job_instance where tenant_id=? and id=?
        """;
    final String selectReplayStatusSql = """
            select replay_status from batch.dead_letter_task where tenant_id=? and id=?
        """;
    final String selectResultSummarySql = """
            select result_summary::text from batch.compensation_command where tenant_id=? and command_no=?
        """;
    final String selectApprovalStatusSql = """
            select approval_status from batch.approval_command where tenant_id=? and approval_no=?
        """;
    final String selectCompensationCommandCountSql = """
            select count(*) from batch.compensation_command where tenant_id=? and approval_id=?
        """;
    final String selectApprovedCommandNoSql = """
            select command_no from batch.compensation_command where tenant_id=? and approval_id=?
        """;
    final String selectCountSql3 = """
            select count(*) from batch.trigger_request where tenant_id=? and request_id=?
        """;
    final String selectTriggerRequestPrimaryKeySql = """
            select id from batch.trigger_request where tenant_id=? and request_id=?
            """;
    final String selectAuthorizedTriggerRequestIdSql = """
            select external_outcome_resolution_trigger_request_id
            from batch.job_task where tenant_id=? and id=?
            """;
    final String selectResolutionLaunchIdentitySql = """
            select launch_payload #>> '{params,_externalOutcomeResolutionTaskId}' as source_task_id,
                   launch_payload #>> '{params,_externalOutcomeResolutionVersion}' as source_version
            from batch.trigger_request where tenant_id=? and id=?
            """;
    final String selectSourceResolutionVersionSql = """
            select external_outcome_resolution_version
            from batch.job_task where tenant_id=? and id=?
            """;
    final String selectCountSql4 = """
            select count(*) from batch.outbox_event where tenant_id=? and event_type='WORKFLOW_LAUNCH'\s\
            and event_key=?
        """;
    final String updateSourceParamsSnapshotSql = """
            update batch.job_instance
            set params_snapshot=?::jsonb
            where tenant_id=? and id=?
            """;
    final String selectRecoveredWorkflowStatusSql = """
            select wr.run_status, ji.instance_status, jt.task_status
            from batch.workflow_run wr
            join batch.trigger_request tr on tr.tenant_id=wr.tenant_id and tr.id=wr.trigger_request_id
            join batch.workflow_node_run wnr on wnr.workflow_run_id=wr.id
            join batch.job_instance ji on ji.tenant_id=wr.tenant_id and ji.id=wnr.job_instance_id
            join batch.job_task jt on jt.tenant_id=ji.tenant_id and jt.job_instance_id=ji.id
            where wr.tenant_id=? and tr.request_id=?
            """;
    final String selectCustomerCountSql = """
            select count(*) from biz.customer_account where customer_no=?
        """;
    final String selectExtraJsonSql = """
        select extra_json from batch.job_execution_log where tenant_id=? and message='DEAD_LETTER_REPLAY'\s\
        and extra_json->>'deadLetterTaskId'=?\
        """;

    String traceId = traceId("dlq-unknown");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    String customerNo = "UNKNOWN_OUTCOME_" + UUID.randomUUID().toString().replace("-", "");
    Map<String, Object> importParams = new LinkedHashMap<>();
    importParams.put("fileFormatType", "JSON");
    importParams.put("templateCode", BASE_TEMPLATE_CODE);
    importParams.put("bizType", "CUSTOMER");
    importParams.put("systemPipelineCode", "IMPORT");
    importParams.put("systemPipelineVersion", 1);
    String importContent = "[{\"customerNo\":\"" + customerNo
        + "\",\"customerName\":\"Unknown outcome recovery\",\"customerType\":\"PERSONAL\","
        + "\"certificateNo\":\"ID-" + customerNo + "\",\"status\":\"ACTIVE\"}]";
    importParams.put("content", importContent);
    long deadLetterId = seedReplayGraph(seed, traceId, importParams);
    Map<String, Object> deadLetter =
        jdbcTemplate.queryForMap(selectSourceIdSql, TENANT, deadLetterId);
    long partitionId = ((Number) deadLetter.get("source_id")).longValue();
    String payloadRef = (String) deadLetter.get("payload_ref");
    String[] sourceReference = payloadRef.split(":", 2);
    assertThat(sourceReference).hasSize(2);
    long taskId = Long.parseLong(sourceReference[1]);
    long jobInstanceId =
        jdbcTemplate.queryForObject(selectJobInstanceIdSql, Long.class, TENANT, taskId);
    Map<String, Object> recoveryParams = new LinkedHashMap<>(importParams);
    recoveryParams.put("_rerunConfigVersionPolicy", "USE_LATEST_CONFIG");
    Map<String, Object> effectiveParamsSnapshot = Map.of("effectiveParams", recoveryParams);
    String sourceParamsSnapshotJson = objectMapper.writeValueAsString(effectiveParamsSnapshot);
    jdbcTemplate.update(
        updateSourceParamsSnapshotSql, sourceParamsSnapshotJson, TENANT, jobInstanceId);
    jdbcTemplate.update(updateJobTaskSql, TENANT, taskId);
    jdbcTemplate.update(updateJobPartitionSql, TENANT, partitionId);
    jdbcTemplate.update(updateJobInstanceSql, TENANT, jobInstanceId);
    jdbcTemplate.update(updateDeadLetterTaskSql, TENANT, deadLetterId);
    long dispatchEventsBefore =
        jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, taskId);
    long rootLaunchEventsBefore = jdbcTemplate.queryForObject(selectCountSql2, Long.class, TENANT);

    String approvalNo = extractDataText(postConsoleJson(
        "/api/console/jobs/dead-letters/replay",
        """
                {"tenantId":"t1","deadLetterId":%d,"reason":"reconciliation verified no remote action",
                 "operatorId":"untrusted-request-operator","externalActionVerifiedNotExecuted":true,
                 "externalOutcomeEvidenceReference":"incident://INC-E2E-2051/evidence"}
                """.formatted(deadLetterId),
        idempotencyKey("dead-letter-unknown-replay")));

    JsonNode pendingApproval =
        firstItem(getConsoleJson("/api/console/queries/approvals?tenantId=t1&approvalNo="
            + encode(approvalNo) + "&approvalStatus=PENDING&pageNo=1&pageSize=10"));
    assertThat(pendingApproval.path("actionType").asText()).isEqualTo("DLQ_REPLAY");

    AtomicBoolean failFirstExecutedCall = new AtomicBoolean(true);
    OrchestratorInternalRestClient securedConsoleInternalClient =
        securedConsoleContext.getBean(OrchestratorInternalRestClient.class);
    doAnswer(invocation -> {
          if (failFirstExecutedCall.compareAndSet(true, false)) {
            throw new ResourceAccessException("simulated lost approval EXECUTED request");
          }
          return invocation.callRealMethod();
        })
        .when(securedConsoleInternalClient)
        .markApprovalExecuted(anyString(), anyString());

    String approvePath = "/api/console/approvals/" + encode(approvalNo) + "/approve";
    String approveBody = """
        {"tenantId":"t1","operatorId":"%s","reason":"verified external action was not executed"}
        """.formatted(APPROVAL_OPERATOR);
    HttpResponse<String> firstApprovalAttempt = postConsoleResponse(
        approvePath, approveBody, idempotencyKey("dead-letter-unknown-approve-first"));
    assertThat(firstApprovalAttempt.statusCode()).isBetween(500, 599);
    String selectedApprovalStatus =
        jdbcTemplate.queryForObject(selectApprovalStatusSql, String.class, TENANT, approvalNo);
    assertThat(selectedApprovalStatus).isEqualTo("APPROVED");
    Long selectedCompensationCommandCount = jdbcTemplate.queryForObject(
        selectCompensationCommandCountSql, Long.class, TENANT, approvalNo);
    assertThat(selectedCompensationCommandCount).isEqualTo(1L);
    String firstCommandNo =
        jdbcTemplate.queryForObject(selectApprovedCommandNoSql, String.class, TENANT, approvalNo);

    String replayCommandNo = extractDataText(postConsoleJson(
        approvePath, approveBody, idempotencyKey("dead-letter-unknown-approve-retry")));
    assertThat(replayCommandNo).isNotBlank();
    assertThat(replayCommandNo).isEqualTo(firstCommandNo);
    Long selectedCompensationCommandCountRead2 = jdbcTemplate.queryForObject(
        selectCompensationCommandCountSql, Long.class, TENANT, approvalNo);
    assertThat(selectedCompensationCommandCountRead2).isEqualTo(1L);

    await()
        .atMost(Duration.ofSeconds(30))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          Map<String, Object> task = jdbcTemplate.queryForMap(selectTaskStatusSql, TENANT, taskId);
          assertThat(task.get("task_status")).isEqualTo("FAILED");
          assertThat(task.get("error_code")).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
          assertThat(task.get("external_outcome_resolution_version")).isNotNull();
          Long selectedVersion =
              jdbcTemplate.queryForObject(selectVersionSql, Long.class, TENANT, taskId);
          assertThat(((Number) selectedVersion).longValue())
              .isEqualTo(
                  ((Number) task.get("external_outcome_resolution_version")).longValue() + 1);
          String selectedInstanceStatus = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatus).isEqualTo("FAILED");
          String selectedReplayStatus = jdbcTemplate.queryForObject(
              selectReplayStatusSql, String.class, TENANT, deadLetterId);
          assertThat(selectedReplayStatus).isEqualTo("SUCCESS");
          Long selectedOutboxEventCount =
              jdbcTemplate.queryForObject(selectCountSql, Long.class, TENANT, taskId);
          assertThat(selectedOutboxEventCount).isEqualTo(dispatchEventsBefore);
          Long selectedOutboxEventCountRead2 =
              jdbcTemplate.queryForObject(selectCountSql2, Long.class, TENANT);
          assertThat(selectedOutboxEventCountRead2).isEqualTo(rootLaunchEventsBefore + 1);
        });

    String resultSummary =
        jdbcTemplate.queryForObject(selectResultSummarySql, String.class, TENANT, replayCommandNo);
    JsonNode resultJson = objectMapper.readTree(resultSummary);
    String launchRequestId = resultJson.path("launchRequestId").asText();
    assertThat(launchRequestId).isNotBlank();
    Long rootTriggerRequestId = jdbcTemplate.queryForObject(
        selectTriggerRequestPrimaryKeySql, Long.class, TENANT, launchRequestId);
    assertThat(rootTriggerRequestId).isNotNull();
    Long authorizedTriggerRequestId = jdbcTemplate.queryForObject(
        selectAuthorizedTriggerRequestIdSql, Long.class, TENANT, taskId);
    assertThat(authorizedTriggerRequestId).isEqualTo(rootTriggerRequestId);
    Map<String, Object> resolutionLaunchIdentity =
        jdbcTemplate.queryForMap(selectResolutionLaunchIdentitySql, TENANT, rootTriggerRequestId);
    Long sourceResolutionVersion =
        jdbcTemplate.queryForObject(selectSourceResolutionVersionSql, Long.class, TENANT, taskId);
    assertThat(resolutionLaunchIdentity.get("source_task_id")).isEqualTo(Long.toString(taskId));
    assertThat(resolutionLaunchIdentity.get("source_version"))
        .isEqualTo(sourceResolutionVersion.toString());
    Long selectedTriggerRequestCount =
        jdbcTemplate.queryForObject(selectCountSql3, Long.class, TENANT, launchRequestId);
    assertThat(selectedTriggerRequestCount).isEqualTo(1L);
    Long selectedOutboxEventCountRead3 = jdbcTemplate.queryForObject(
        selectCountSql4, Long.class, TENANT, TENANT + ":workflow-launch:" + launchRequestId);
    assertThat(selectedOutboxEventCountRead3).isEqualTo(1L);

    e2eOutboxPublishSupport.publishAllPending(TENANT);
    JdbcTemplate businessJdbcTemplate = new JdbcTemplate(businessDataSource);
    await()
        .atMost(Duration.ofSeconds(45))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          e2eOutboxPublishSupport.publishAllPending(TENANT);
          List<Map<String, Object>> recoveredRuns =
              jdbcTemplate.queryForList(selectRecoveredWorkflowStatusSql, TENANT, launchRequestId);
          assertThat(recoveredRuns).hasSize(1);
          Map<String, Object> recoveredRun = recoveredRuns.get(0);
          assertThat(recoveredRun.get("run_status")).isEqualTo("SUCCESS");
          assertThat(recoveredRun.get("instance_status")).isEqualTo("SUCCESS");
          assertThat(recoveredRun.get("task_status")).isEqualTo("SUCCESS");
          Integer selectedCustomerAccountCount = businessJdbcTemplate.queryForObject(
              selectCustomerCountSql, Integer.class, customerNo);
          assertThat(selectedCustomerAccountCount).isEqualTo(1);
          String selectedTaskStatus = jdbcTemplate.queryForObject(
              selectOriginalTaskStatusSql, String.class, TENANT, taskId);
          assertThat(selectedTaskStatus).isEqualTo("FAILED");
          String selectedInstanceStatusRead2 = jdbcTemplate.queryForObject(
              selectInstanceStatusSql, String.class, TENANT, jobInstanceId);
          assertThat(selectedInstanceStatusRead2).isEqualTo("FAILED");
          Long selectedOutboxEventCountRead4 = jdbcTemplate.queryForObject(
              selectCountSql4, Long.class, TENANT, TENANT + ":workflow-launch:" + launchRequestId);
          assertThat(selectedOutboxEventCountRead4).isEqualTo(1L);
        });

    Map<String, Object> audit =
        jdbcTemplate.queryForMap(selectExtraJsonSql, TENANT, Long.toString(deadLetterId));
    JsonNode auditJson = objectMapper.readTree(audit.get("extra_json").toString());
    assertThat(auditJson.path("operatorId").asText()).isEqualTo(APPROVAL_OPERATOR);
    assertThat(auditJson.path("externalActionOutcome").asText()).isEqualTo("VERIFIED_NOT_EXECUTED");
    assertThat(auditJson.path("externalOutcomeEvidenceReference").asText())
        .isEqualTo("incident://INC-E2E-2051/evidence");
    JsonNode executedApproval =
        firstItem(getConsoleJson("/api/console/queries/approvals?tenantId=t1&approvalNo="
            + encode(approvalNo) + "&pageNo=1&pageSize=10"));
    assertThat(executedApproval.path("approvalStatus").asText()).isEqualTo("EXECUTED");

    String approvalAuditPath = "/api/console/queries/operation-audits?tenantId=t1"
        + "&aggregateType=approval&aggregateId=" + encode(approvalNo)
        + "&action=approval.approve&pageNo=1&pageSize=10";
    await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
      JsonNode operationAudit = firstItem(getConsoleJson(approvalAuditPath));
      assertThat(operationAudit.path("operatorId").asText()).isEqualTo(APPROVAL_OPERATOR);
      assertThat(operationAudit.path("result").asText()).isEqualTo("SUCCESS");
      assertThat(operationAudit.path("aggregateId").asText()).isEqualTo(approvalNo);
      assertThat(operationAudit.path("params").asText())
          .contains("verified external action was not executed");
      assertThat(operationAudit.path("traceId").asText()).isNotBlank();
    });

    Long commandsBeforeTampering = jdbcTemplate.queryForObject(
        selectCompensationCommandCountSql, Long.class, TENANT, approvalNo);
    assertThat(commandsBeforeTampering).isEqualTo(1L);
    Map<String, Object> originalExecution = new LinkedHashMap<>();
    originalExecution.put("tenantId", TENANT);
    originalExecution.put("deadLetterId", deadLetterId);
    originalExecution.put("approvalId", approvalNo);
    originalExecution.put("reason", "reconciliation verified no remote action");
    originalExecution.put("operatorId", "untrusted-request-operator");
    originalExecution.put("externalActionVerifiedNotExecuted", true);
    originalExecution.put("externalOutcomeEvidenceReference", "incident://INC-E2E-2051/evidence");
    Map<String, Object> unauthorizedChanges = Map.of(
        "deadLetterId",
        deadLetterId + 1,
        "externalActionVerifiedNotExecuted",
        false,
        "externalOutcomeEvidenceReference",
        "incident://unapproved-evidence",
        "strategy",
        "FORCE");
    for (Map.Entry<String, Object> change : unauthorizedChanges.entrySet()) {
      Map<String, Object> altered = new LinkedHashMap<>(originalExecution);
      altered.put(change.getKey(), change.getValue());
      HttpResponse<String> denied = postConsoleResponse(
          "/api/console/jobs/dead-letters/replay",
          objectMapper.writeValueAsString(altered),
          idempotencyKey("tampered-approval"));
      assertThat(denied.statusCode()).as("reject changed %s", change.getKey()).isEqualTo(403);
      assertThat(objectMapper.readTree(denied.body()).path("code").asText()).isEqualTo("FORBIDDEN");
    }
    Long selectedCompensationCommandCountRead3 = jdbcTemplate.queryForObject(
        selectCompensationCommandCountSql, Long.class, TENANT, approvalNo);
    assertThat(selectedCompensationCommandCountRead3).isEqualTo(commandsBeforeTampering);
    Long selectedOutboxEventCountRead5 =
        jdbcTemplate.queryForObject(selectCountSql2, Long.class, TENANT);
    assertThat(selectedOutboxEventCountRead5).isEqualTo(rootLaunchEventsBefore + 1);
    String selectedReplayStatusRead2 =
        jdbcTemplate.queryForObject(selectReplayStatusSql, String.class, TENANT, deadLetterId);
    assertThat(selectedReplayStatusRead2).isEqualTo("SUCCESS");
  }

  @ParameterizedTest
  @ValueSource(strings = {"JOB_TASK", "JOB_PARTITION"})
  @DisplayName("经 Console HTTP 审批的任务或分区 RETRY 会实际重排队并完成业务写入")
  void shouldExecuteApprovedTaskOrPartitionRetryThroughConsoleHttp(String targetType)
      throws Exception {
    final String selectSourceSql = """
        select source_id, payload_ref from batch.dead_letter_task where tenant_id=? and trace_id=?
        """;
    final String selectInstanceStatusSql = """
        select ji.instance_status
        from batch.job_instance ji
        join batch.job_task jt on jt.job_instance_id=ji.id and jt.tenant_id=ji.tenant_id
        where ji.tenant_id=? and jt.id=?
        """;
    final String failTaskSql = """
        update batch.job_task
        set task_status='FAILED', error_code='E2E_RETRYABLE_FAILURE',
            failure_class='INFRASTRUCTURE', error_message='simulated retryable failure',
            finished_at=now(), version=version+1
        where tenant_id=? and id=?
        """;
    final String failPartitionSql = """
        update batch.job_partition set partition_status='FAILED', version=version+1
        where tenant_id=? and id=?
        """;
    final String selectTaskStateSql = """
        select task_status from batch.job_task where tenant_id=? and id=?
        """;
    final String selectPartitionStateSql = """
        select partition_status from batch.job_partition where tenant_id=? and id=?
        """;
    final String selectRetryOutboxSql = """
        select count(*) from batch.outbox_event
        where tenant_id=? and aggregate_type='JOB_TASK' and aggregate_id=?
        """;
    final String selectCustomerSql = """
        select count(*) from biz.customer_account where customer_no=?
        """;
    final String selectApprovalStatusSql = """
        select approval_status from batch.approval_command where tenant_id=? and approval_no=?
        """;

    String uniqueTraceId = traceId("approved-retry");
    String customerNo = "APPROVED_RETRY_" + UUID.randomUUID().toString().replace("-", "");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbcTemplate, TENANT, "IMPORT", "import", TriggerType.API);
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("fileFormatType", "JSON");
    params.put("templateCode", BASE_TEMPLATE_CODE);
    params.put("bizType", "CUSTOMER");
    params.put("systemPipelineCode", "IMPORT");
    params.put("systemPipelineVersion", 1);
    String customerContent = """
        [{"customerNo":"%s","customerName":"Approved retry","customerType":"PERSONAL",\
        "certificateNo":"ID-%s","status":"ACTIVE"}]\
        """.formatted(customerNo, customerNo);
    params.put("content", customerContent);
    long deadLetterId = seedReplayGraph(seed, uniqueTraceId, params);
    Map<String, Object> source = jdbcTemplate.queryForMap(selectSourceSql, TENANT, uniqueTraceId);
    long partitionId = ((Number) source.get("source_id")).longValue();
    String[] sourceReference = ((String) source.get("payload_ref")).split(":", 2);
    assertThat(sourceReference).hasSize(2);
    long taskId = Long.parseLong(sourceReference[1]);

    int affectedFailTaskRows = jdbcTemplate.update(failTaskSql, TENANT, taskId);
    assertThat(affectedFailTaskRows).isEqualTo(1);
    int affectedFailPartitionRows = jdbcTemplate.update(failPartitionSql, TENANT, partitionId);
    assertThat(affectedFailPartitionRows).isEqualTo(1);

    String requestPath = "JOB_TASK".equals(targetType)
        ? "/api/console/jobs/tasks/replay"
        : "/api/console/jobs/partitions/replay";
    Map<String, Object> replayPayload = new LinkedHashMap<>();
    replayPayload.put("tenantId", TENANT);
    replayPayload.put("reason", "approved retry e2e");
    replayPayload.put("operatorId", APPROVAL_OPERATOR);
    replayPayload.put(
        "JOB_TASK".equals(targetType) ? "taskId" : "partitionId",
        "JOB_TASK".equals(targetType) ? taskId : partitionId);
    String replayBody = objectMapper.writeValueAsString(replayPayload);
    String approvalNo = extractDataText(postConsoleJson(
        requestPath, replayBody, idempotencyKey("approved-retry-submit-" + targetType)));
    JsonNode pending =
        firstItem(getConsoleJson("/api/console/queries/approvals?tenantId=t1&approvalNo="
            + encode(approvalNo) + "&pageNo=1&pageSize=10"));
    assertThat(pending.path("approvalStatus").asText()).isEqualTo("PENDING");
    assertThat(pending.path("actionType").asText()).isEqualTo("RETRY");
    assertThat(pending.path("targetType").asText()).isEqualTo(targetType);

    String retryOperation = extractDataText(postConsoleJson(
        "/api/console/approvals/" + encode(approvalNo) + "/approve",
        objectMapper.writeValueAsString(Map.of(
            "tenantId", TENANT, "operatorId", APPROVAL_OPERATOR, "reason", "approved retry e2e")),
        idempotencyKey("approved-retry-approve-" + targetType)));
    assertThat(retryOperation).isNotBlank();
    String selectedTaskStatus =
        jdbcTemplate.queryForObject(selectTaskStateSql, String.class, TENANT, taskId);
    assertThat(selectedTaskStatus).isEqualTo("READY");
    String selectedPartitionStatus =
        jdbcTemplate.queryForObject(selectPartitionStateSql, String.class, TENANT, partitionId);
    assertThat(selectedPartitionStatus).isEqualTo("READY");
    Long selectedOutboxEventCount =
        jdbcTemplate.queryForObject(selectRetryOutboxSql, Long.class, TENANT, taskId);
    assertThat(selectedOutboxEventCount).isEqualTo(1L);
    String selectedApprovalStatus =
        jdbcTemplate.queryForObject(selectApprovalStatusSql, String.class, TENANT, approvalNo);
    assertThat(selectedApprovalStatus).isEqualTo("EXECUTED");

    e2eOutboxPublishSupport.publishAllPending(TENANT);
    await()
        .atMost(Duration.ofSeconds(45))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(() -> {
          String selectedTaskStatusRead2 =
              jdbcTemplate.queryForObject(selectTaskStateSql, String.class, TENANT, taskId);
          assertThat(selectedTaskStatusRead2).isEqualTo("SUCCESS");
          String selectedPartitionStatusRead2 = jdbcTemplate.queryForObject(
              selectPartitionStateSql, String.class, TENANT, partitionId);
          assertThat(selectedPartitionStatusRead2).isEqualTo("SUCCESS");
          Integer selectedCustomerAccountCount = new JdbcTemplate(businessDataSource)
              .queryForObject(selectCustomerSql, Integer.class, customerNo);
          assertThat(selectedCustomerAccountCount).isEqualTo(1);
          String selectedInstanceStatus =
              jdbcTemplate.queryForObject(selectInstanceStatusSql, String.class, TENANT, taskId);
          assertThat(selectedInstanceStatus).isEqualTo("SUCCESS");
          String selectedApprovalStatusRead2 = jdbcTemplate.queryForObject(
              selectApprovalStatusSql, String.class, TENANT, approvalNo);
          assertThat(selectedApprovalStatusRead2).isEqualTo("EXECUTED");
        });
    assertThat(deadLetterId).isPositive();
    Long selectedOutboxEventCountRead2 =
        jdbcTemplate.queryForObject(selectRetryOutboxSql, Long.class, TENANT, taskId);
    assertThat(selectedOutboxEventCountRead2).isEqualTo(1L);
  }

  private void insertReplayTemplate() {
    final String insertFileTemplateConfigSql = """
        insert into batch.file_template_config
          (tenant_id, template_code, template_name, template_type, biz_type,
           file_format_type, charset, target_charset, with_bom,
           delimiter, quote_char, escape_char,
           record_length, header_rows, footer_rows,
           checksum_type, compress_type, encrypt_type,
           field_mappings, validation_rule_set, query_param_schema,
           streaming_enabled, page_size, fetch_size, chunk_size,
           content_encryption_enabled, encryption_key_ref,
           preview_masking_enabled, download_requires_approval,
           enabled, version, created_by, load_target_ref)
        select tenant_id,
               ?,
               ?,
               template_type,
               biz_type,
               file_format_type,
               charset,
               target_charset,
               with_bom,
               delimiter,
               quote_char,
               escape_char,
               record_length,
               header_rows,
               footer_rows,
               checksum_type,
               compress_type,
               encrypt_type,
               field_mappings,
               validation_rule_set,
               query_param_schema,
               streaming_enabled,
               page_size,
               fetch_size,
               chunk_size,
               content_encryption_enabled,
               encryption_key_ref,
               preview_masking_enabled,
               download_requires_approval,
               enabled,
               version,
               created_by,
               load_target_ref
        from batch.file_template_config
        where tenant_id = ?
          and template_code = ?
        """;

    jdbcTemplate.update(
        insertFileTemplateConfigSql,
        REPLAY_TEMPLATE_CODE,
        "Customer Import JSON Array DLQ E2E",
        TENANT,
        BASE_TEMPLATE_CODE);
  }

  @Test
  @DisplayName("认证审批授权由独立派发 Worker 消费一次并经 SFTP 完成新工作流")
  void shouldDeliverSftpOnce_whenUnknownOutcomeRecoveryIsApproved(@TempDir Path tempDir)
      throws Exception {
    final String insertFileSql = """
        insert into batch.file_record (
            tenant_id, file_code, biz_type, file_category, file_name, original_file_name,
            file_ext, file_format_type, charset, mime_type, file_size_bytes, checksum_type,
            storage_type, storage_path, source_type, file_status, biz_date, trace_id
        ) values (?, ?, 'OUTPUT', 'OUTPUT', ?, ?, 'txt', 'DELIMITED', 'UTF-8', 'text/plain', ?,
                  'NONE', 'LOCAL', ?, 'SYSTEM', 'GENERATED', date '2026-01-15', ?)
        returning id
        """;
    final String insertChannelSql = """
        insert into batch.file_channel_config (
            tenant_id, channel_code, channel_name, channel_type, target_endpoint, auth_type,
            config_json, receipt_policy, timeout_seconds, enabled
        ) values (?, ?, '审批后 SFTP 单次恢复', 'SFTP', ?, 'NONE', ?::jsonb, 'SYNC', 30, true)
        """;
    final String selectSourceSql = """
        select jt.id as task_id, jt.job_instance_id, jt.job_partition_id
        from batch.dead_letter_task dlq
        join batch.job_task jt on jt.tenant_id=dlq.tenant_id
            and jt.id=split_part(dlq.payload_ref, ':', 2)::bigint
        where dlq.tenant_id=? and dlq.id=?
        """;
    final String failSourceTaskSql = """
        update batch.job_task set task_status='FAILED', error_code='EXTERNAL_OUTCOME_UNKNOWN',
            failure_class='UNKNOWN', finished_at=now(), version=version+1
        where tenant_id=? and id=?
        """;
    final String failSourcePartitionSql = """
        update batch.job_partition set partition_status='FAILED', version=version+1
        where tenant_id=? and id=?
        """;
    final String failSourceInstanceSql = """
        update batch.job_instance set instance_status='FAILED', failure_class='UNKNOWN',
            failed_partition_count=1, finished_at=now(), version=version+1,
            params_snapshot=?::jsonb where tenant_id=? and id=?
        """;
    final String disableAutomaticReplaySql = """
        update batch.dead_letter_task set max_replay_count=0, next_replay_at=null
        where tenant_id=? and id=?
        """;
    final String insertSourcePipelineSql = """
        insert into batch.pipeline_instance (
            tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,
            file_id, related_job_instance_id, current_stage, run_status, trace_id
        ) values (?, 'DISPATCH', 1, ?, 'DISPATCH', ?, ?, 'DELIVER', 'FAILED', ?)
        returning id
        """;
    final String insertUnknownDispatchSql = """
        insert into batch.file_dispatch_record (
            tenant_id, file_id, pipeline_instance_id, channel_code, dispatch_target,
            dispatch_status, dispatch_attempt, receipt_status, external_request_id, error_code
        ) values (?, ?, ?, ?, '/upload/', 'FAILED', 1, 'FAILED', ?, 'EXTERNAL_OUTCOME_UNKNOWN')
        """;
    final String selectReplayRequestSql = """
        select result_summary->>'launchRequestId' from batch.compensation_command
        where tenant_id=? and command_no=?
        """;
    final String selectRecoverySql = """
        select wr.run_status, ji.instance_status, jt.task_status, pi.id as pipeline_id
        from batch.trigger_request tr
        join batch.workflow_run wr on wr.tenant_id=tr.tenant_id and wr.trigger_request_id=tr.id
        join batch.workflow_node_run node on node.workflow_run_id=wr.id
        join batch.job_instance ji on ji.tenant_id=wr.tenant_id and ji.id=node.job_instance_id
        join batch.job_task jt on jt.tenant_id=ji.tenant_id and jt.job_instance_id=ji.id
        join batch.pipeline_instance pi on pi.tenant_id=ji.tenant_id
            and pi.related_job_instance_id=ji.id
        where tr.tenant_id=? and tr.request_id=?
        """;
    final String selectDispatchSql = """
        select pipeline_instance_id, dispatch_status, dispatch_attempt, dispatched_at
        from batch.file_dispatch_record where tenant_id=? and file_id=? and channel_code=?
        """;
    final String selectAuthorizationSql = """
        select task_status, error_code, external_outcome_resolution_consumed_at
        from batch.job_task where tenant_id=? and id=?
        """;
    final String selectReplayCountSql = """
        select count(*) from batch.compensation_command where tenant_id=? and approval_id=?
        """;

    String suffix = Long.toUnsignedString(System.nanoTime());
    String sftpUser = "batch";
    String sftpPassword = "batch-pass";
    String fileName = "approved-retry-" + suffix + ".txt";
    String channelCode = "approved-sftp-" + suffix;
    String sourceTrace = traceId("approved-sftp");
    byte[] content = ("approved-once-" + suffix).getBytes(StandardCharsets.UTF_8);
    Path sourceFile = tempDir.resolve(fileName);
    Files.write(sourceFile, content);

    try (GenericContainer<?> sftp = new GenericContainer<>(
            DockerImageName.parse("jmcombs/sftp:alpine"))
        .withLabel(TestContainerLabels.OWNER, TestContainerLabels.OWNER_VALUE)
        .withExposedPorts(22)
        .withCommand(sftpUser + ":" + sftpPassword + ":::upload")
        .waitingFor(Wait.forListeningPort())) {
      sftp.start();
      Long fileId = jdbcTemplate.queryForObject(
          insertFileSql,
          Long.class,
          TENANT,
          "approved-file-" + suffix,
          fileName,
          fileName,
          content.length,
          sourceFile.toString(),
          sourceTrace);
      Map<String, Object> channelConfig = Map.ofEntries(
          Map.entry("channel_type", "SFTP"),
          Map.entry("channel_code", channelCode),
          Map.entry("sftp_host", sftp.getHost()),
          Map.entry("sftp_port", sftp.getMappedPort(22)),
          Map.entry("sftp_user", sftpUser),
          Map.entry("sftp_password", sftpPassword),
          Map.entry("sftp_remote_directory", "/upload/"),
          Map.entry("sftp_remote_file_name", fileName),
          Map.entry("sftp_strict_host_key_checking", "no"));
      String channelConfigJson = objectMapper.writeValueAsString(channelConfig);
      String endpoint = "sftp://" + sftp.getHost() + ":" + sftp.getMappedPort(22) + "/upload";
      jdbcTemplate.update(insertChannelSql, TENANT, channelCode, endpoint, channelConfigJson);

      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbcTemplate, TENANT, "DISPATCH", "dispatch", TriggerType.API);
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("fileId", fileId.toString());
      params.put("channelCode", channelCode);
      params.put("dispatchTarget", "/upload/");
      params.put("externalRequestId", "approved-request-" + suffix);
      params.put("ackRequired", false);
      params.put("forceRetry", false);
      params.put("systemPipelineCode", "DISPATCH");
      params.put("systemPipelineVersion", 1);
      params.put("_rerunConfigVersionPolicy", "USE_LATEST_CONFIG");
      long deadLetterId = seedReplayGraph(seed, sourceTrace, params, "DISPATCH", "DISPATCH");
      Map<String, Object> source = jdbcTemplate.queryForMap(selectSourceSql, TENANT, deadLetterId);
      long taskId = ((Number) source.get("task_id")).longValue();
      long sourceInstanceId = ((Number) source.get("job_instance_id")).longValue();
      long sourcePartitionId = ((Number) source.get("job_partition_id")).longValue();
      String sourceSnapshot = objectMapper.writeValueAsString(Map.of("effectiveParams", params));
      jdbcTemplate.update(failSourceTaskSql, TENANT, taskId);
      jdbcTemplate.update(failSourcePartitionSql, TENANT, sourcePartitionId);
      jdbcTemplate.update(failSourceInstanceSql, sourceSnapshot, TENANT, sourceInstanceId);
      jdbcTemplate.update(disableAutomaticReplaySql, TENANT, deadLetterId);
      Long sourcePipelineId = jdbcTemplate.queryForObject(
          insertSourcePipelineSql,
          Long.class,
          TENANT,
          seed.jobCode(),
          fileId,
          sourceInstanceId,
          sourceTrace);
      jdbcTemplate.update(
          insertUnknownDispatchSql,
          TENANT,
          fileId,
          sourcePipelineId,
          channelCode,
          params.get("externalRequestId"));
      String remotePath = "/home/" + sftpUser + "/upload/" + fileName;
      ExecResult beforeApproval = sftp.execInContainer("test", "!", "-e", remotePath);
      assertThat(beforeApproval.getExitCode()).isZero();

      Path workerLog = Files.createTempFile("approved-dispatch-worker-", ".log");
      Process worker = startApprovedDispatchWorker(workerLog, suffix);
      try {
        await().atMost(Duration.ofSeconds(120)).untilAsserted(() -> {
          assertThat(worker.isAlive()).as("Dispatch Worker log: %s", workerLog).isTrue();
          String startupLog = Files.readString(workerLog, StandardCharsets.UTF_8);
          assertThat(startupLog).contains("Started BatchWorkerDispatchApplication");
        });
        Map<String, Object> replayRequest = Map.of(
            "tenantId",
            TENANT,
            "deadLetterId",
            deadLetterId,
            "reason",
            "SFTP reconciliation proves no file was published",
            "externalActionVerifiedNotExecuted",
            true,
            "externalOutcomeEvidenceReference",
            "incident://approved-sftp/" + suffix);
        String replayBody = objectMapper.writeValueAsString(replayRequest);
        JsonNode approvalResponse = postConsoleJson(
            "/api/console/jobs/dead-letters/replay", replayBody, idempotencyKey("sftp-submit"));
        String approvalNo = extractDataText(approvalResponse);
        String approvePath = "/api/console/approvals/" + encode(approvalNo) + "/approve";
        String approveBody = objectMapper.writeValueAsString(
            Map.of("tenantId", TENANT, "reason", "confirmed no remote execution"));
        JsonNode commandResponse =
            postConsoleJson(approvePath, approveBody, idempotencyKey("sftp-approve"));
        String commandNo = extractDataText(commandResponse);
        String replayRequestId =
            jdbcTemplate.queryForObject(selectReplayRequestSql, String.class, TENANT, commandNo);
        assertThat(replayRequestId).isNotBlank();

        await()
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> {
              e2eOutboxPublishSupport.publishAllPending(TENANT);
              List<Map<String, Object>> runs =
                  jdbcTemplate.queryForList(selectRecoverySql, TENANT, replayRequestId);
              assertThat(runs).as("Dispatch Worker log: %s", workerLog).hasSize(1);
              Map<String, Object> run = runs.getFirst();
              assertThat(run.get("run_status")).isEqualTo("SUCCESS");
              assertThat(run.get("instance_status")).isEqualTo("SUCCESS");
              assertThat(run.get("task_status")).isEqualTo("SUCCESS");
              assertThat(run.get("pipeline_id")).isNotEqualTo(sourcePipelineId);
            });
        Map<String, Object> recoveredRun =
            jdbcTemplate.queryForMap(selectRecoverySql, TENANT, replayRequestId);
        Map<String, Object> dispatched =
            jdbcTemplate.queryForMap(selectDispatchSql, TENANT, fileId, channelCode);
        assertThat(dispatched.get("pipeline_instance_id"))
            .isEqualTo(recoveredRun.get("pipeline_id"));
        assertThat(dispatched.get("dispatch_status")).isEqualTo("ACKED");
        assertThat(dispatched.get("dispatch_attempt")).isEqualTo(2);
        Map<String, Object> authorization =
            jdbcTemplate.queryForMap(selectAuthorizationSql, TENANT, taskId);
        assertThat(authorization.get("task_status")).isEqualTo("FAILED");
        assertThat(authorization.get("error_code")).isEqualTo("EXTERNAL_OUTCOME_UNKNOWN");
        assertThat(authorization.get("external_outcome_resolution_consumed_at")).isNotNull();
        ExecResult published = sftp.execInContainer("cat", remotePath);
        assertThat(published.getExitCode()).isZero();
        String expectedContent = new String(content, StandardCharsets.UTF_8);
        assertThat(published.getStdout()).isEqualTo(expectedContent);

        JsonNode repeatedApproval =
            postConsoleJson(approvePath, approveBody, idempotencyKey("sftp-approve-repeat"));
        String repeatedApprovalNo = extractDataText(repeatedApproval);
        assertThat(repeatedApprovalNo).isEqualTo(approvalNo);
        e2eOutboxPublishSupport.publishAllPending(TENANT);
        Long replayCount =
            jdbcTemplate.queryForObject(selectReplayCountSql, Long.class, TENANT, approvalNo);
        assertThat(replayCount).isEqualTo(1L);
        List<Map<String, Object>> runsAfterRepeat =
            jdbcTemplate.queryForList(selectRecoverySql, TENANT, replayRequestId);
        assertThat(runsAfterRepeat).containsExactly(recoveredRun);
        Map<String, Object> dispatchAfterRepeat =
            jdbcTemplate.queryForMap(selectDispatchSql, TENANT, fileId, channelCode);
        assertThat(dispatchAfterRepeat).isEqualTo(dispatched);
      } finally {
        worker.destroy();
        if (!worker.waitFor(10, TimeUnit.SECONDS)) {
          worker.destroyForcibly();
          boolean terminated = worker.waitFor(10, TimeUnit.SECONDS);
          assertThat(terminated).as("test-owned Dispatch Worker must stop").isTrue();
        }
      }
    }
  }

  private Process startApprovedDispatchWorker(Path logFile, String identity)
      throws IOException, InterruptedException {
    Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    if (!Files.isRegularFile(projectRoot.resolve("mvnw"))) {
      projectRoot = projectRoot.getParent();
    }
    Path buildLog = Files.createTempFile("approved-dispatch-build-", ".log");
    Process build = new ProcessBuilder(
            "./mvnw", "-o", "-ntp", "-pl", "batch-worker/dispatch", "-am", "-DskipTests", "package")
        .directory(projectRoot.toFile())
        .redirectErrorStream(true)
        .redirectOutput(buildLog.toFile())
        .start();
    try {
      boolean completed = build.waitFor(5, TimeUnit.MINUTES);
      assertThat(completed).as("current-source Dispatch build: %s", buildLog).isTrue();
      assertThat(build.exitValue())
          .as("current-source Dispatch build: %s", buildLog)
          .isZero();
    } finally {
      if (build.isAlive()) {
        build.destroyForcibly();
        build.waitFor(10, TimeUnit.SECONDS);
      }
    }
    Path workerJar;
    try (var artifacts = Files.list(projectRoot.resolve("batch-worker/dispatch/target"))) {
      List<Path> executableJars = artifacts
          .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
          .toList();
      assertThat(executableJars).as("current-source Dispatch executable jar").hasSize(1);
      workerJar = executableJars.getFirst();
    }
    Map<String, Object> properties = new LinkedHashMap<>();
    BiConsumer<String, Supplier<?>> propertyRegistrar =
        (key, supplier) -> properties.put(key, supplier.get());
    AbstractIntegrationTest.registerDynamicProperties(propertyRegistrar::accept, false);
    properties.put("spring.profiles.active", "test,e2e");
    properties.put("server.port", 0);
    properties.put("batch.security.bypass-mode", true);
    properties.put("batch.rls.startup-fail-fast", false);
    properties.put("batch.security.internal-secret", "dead-letter-approval-e2e-internal-secret");
    properties.put("batch.orchestrator.base-url", "http://127.0.0.1:" + localServerPort);
    properties.put("batch.worker.task-client.base-url", "http://127.0.0.1:" + localServerPort);
    properties.put("batch.worker.registry.fail-fast-on-startup", false);
    properties.put("batch.worker.dispatch.tenant-id", TENANT);
    properties.put("batch.worker.dispatch.worker-code", "approved-dispatch-" + identity);
    properties.put("batch.worker.dispatch.worker-type", "DISPATCH");
    properties.put("batch.worker.dispatch.consumer-group-id", "approved-dispatch-" + identity);
    properties.put("batch.worker.dispatch.heartbeat-interval-millis", 1000);
    properties.put("batch.worker.dispatch.topic", "batch.task.dispatch.dispatch");
    properties.put("batch.worker.dispatch.health.enabled", false);
    properties.put("batch.worker.dispatch.receipt-poll.enabled", false);
    String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    List<String> command = new ArrayList<>(List.of(javaBin, "-jar", workerJar.toString()));
    properties.forEach((key, value) -> command.add("--" + key + "=" + value));
    return new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(logFile.toFile())
        .start();
  }

  private JsonNode getConsoleJson(String path) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(consoleUri(path))
        .header("Cookie", consoleCookie())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId("get"))
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId("get"))
        .GET()
        .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isBetween(200, 299);
    return objectMapper.readTree(response.body());
  }

  private JsonNode postConsoleJson(String path, String body, String idempotencyKey)
      throws Exception {
    HttpResponse<String> response = postConsoleResponse(path, body, idempotencyKey);
    assertThat(response.statusCode()).isBetween(200, 299);
    return objectMapper.readTree(response.body());
  }

  private HttpResponse<String> postConsoleResponse(String path, String body, String idempotencyKey)
      throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(consoleUri(path))
        .header("Cookie", consoleCookie())
        .header("X-XSRF-TOKEN", consoleCsrf)
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "spoofed-header-operator")
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId("post"))
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId("post"))
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private URI consoleUri(String path) {
    return URI.create("http://127.0.0.1:" + securedConsolePort + path);
  }

  private ConfigurableApplicationContext startSecuredConsole() {
    Map<String, Object> consoleProperties = new HashMap<>();
    BiConsumer<String, Supplier<?>> propertyRegistrar =
        (name, supplier) -> consoleProperties.put(name, supplier.get());
    AbstractIntegrationTest.registerDynamicProperties(propertyRegistrar::accept, false);
    consoleProperties.put("server.port", 0);
    consoleProperties.put("spring.main.web-application-type", "servlet");
    consoleProperties.put("spring.flyway.enabled", false);
    consoleProperties.put(
        "spring.autoconfigure.exclude",
        String.join(
            ",",
            "org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration",
            "org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration"));
    consoleProperties.put("batch.startup-self-check.enabled", false);
    consoleProperties.put("batch.console.security.single-session-enabled", false);
    consoleProperties.put("batch.console.security.default-tenant-id", TENANT);
    consoleProperties.put("batch.console.security.allowed-tenants", TENANT);
    consoleProperties.put(
        "batch.security.internal-secret", "dead-letter-approval-e2e-internal-secret");
    String orchestratorBaseUrl = "http://127.0.0.1:" + localServerPort;
    consoleProperties.put("batch.console.orchestrator.base-url", orchestratorBaseUrl);
    consoleProperties.put("batch.console.trigger.base-url", orchestratorBaseUrl);

    String[] consoleArguments = consoleProperties.entrySet().stream()
        .map(property -> "--" + property.getKey() + "=" + property.getValue())
        .toArray(String[]::new);
    SpringApplicationBuilder consoleApplication =
        new SpringApplicationBuilder(BatchConsoleApiApplication.class);
    consoleApplication.initializers(applicationContext -> {
      BeanPostProcessor spyInternalClient = new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          return bean instanceof OrchestratorInternalRestClient ? Mockito.spy(bean) : bean;
        }
      };
      applicationContext.getBeanFactory().addBeanPostProcessor(spyInternalClient);
    });
    consoleApplication.profiles("test", "e2e");
    return consoleApplication.run(consoleArguments);
  }

  private String consoleCookie() {
    return "batch_console_token=" + consoleJwt + "; XSRF-TOKEN=" + consoleCsrf;
  }

  private JsonNode firstItem(JsonNode response) {
    JsonNode items = response.path("data").path("items");
    assertThat(items.isArray()).isTrue();
    assertThat(items.size()).isGreaterThan(0);
    return items.get(0);
  }

  private JsonNode findItemById(JsonNode response, long id) {
    JsonNode items = response.path("data").path("items");
    assertThat(items.isArray()).isTrue();
    for (JsonNode item : items) {
      if (item.path("id").asLong() == id) {
        return item;
      }
    }
    throw new AssertionError("dead letter id=" + id + " not found in response");
  }

  private String extractDataText(JsonNode response) {
    JsonNode data = response.path("data");
    assertThat(data.isMissingNode()).isFalse();
    return data.asText();
  }

  private String idempotencyKey(String suffix) {
    return "e2e-dlq-" + suffix + "-" + UUID.randomUUID();
  }

  private String requestId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private String traceId(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private long seedReplayGraph(LaunchSeed seed, String traceId, Map<String, Object> params)
      throws Exception {
    return seedReplayGraph(seed, traceId, params, "IMPORT", "import");
  }

  private long seedReplayGraph(
      LaunchSeed seed,
      String traceId,
      Map<String, Object> params,
      String taskType,
      String workerGroup)
      throws Exception {
    final String selectIdSql = """
            select id
            from batch.job_definition
            where tenant_id = ?
              and job_code = ?
            """;
    final String selectIdSql2 = """
            select id
            from batch.trigger_request
            where tenant_id = ?
              and request_id = ?
            """;
    final String insertJobInstanceSql = """
            insert into batch.job_instance (
                tenant_id, job_definition_id, trigger_request_id, job_code, instance_no, biz_date,
                trigger_type, instance_status, batch_no, operator_id, rerun_flag, retry_flag,
                rerun_reason, related_file_id, parent_instance_id, queue_code, worker_group, priority,
                dedup_key, version, expected_partition_count, success_partition_count, failed_partition_count,
                trace_id, params_snapshot, result_summary, deadline_at, expected_duration_seconds,\s\
                sla_alerted_at
            ) values (
                ?, ?, ?, ?, ?, date '2026-01-15',
                'API', 'READY', null, 'e2e-ops', false, false,
                null, null, null, 'e2e-q', ?, 5,
                ?, 0, 1, 0, 0,
                ?, ?::jsonb, ?::jsonb, null, 300, null
            )
            returning id
            """;
    final String insertJobPartitionSql = """
            insert into batch.job_partition (
                tenant_id, job_instance_id, job_instance_biz_date, partition_no, partition_key,\s\
                partition_status,
                worker_group, worker_code, lease_expire_at, retry_count, business_key,
                idempotency_key, input_snapshot, output_summary
            ) values (
                ?, ?, (select biz_date from batch.job_instance where tenant_id=? and id=?),
                1, 'e2e-partition-1', 'READY',
                ?, null, null, 0, 'e2e-business-key',
                ?, ?::jsonb, null
            )
            returning id
            """;
    final String insertJobTaskSql = """
            insert into batch.job_task (
                tenant_id, job_instance_id, job_instance_biz_date, job_partition_id, task_type, task_seq,\s\
                task_status,
                assigned_worker_code, task_payload, result_summary, error_code, error_message,
                started_at, finished_at
            ) values (
                ?, ?, (select biz_date from batch.job_instance where tenant_id=?\s\
                and id=?), ?, ?, 1, 'READY',
                null, ?::jsonb, null, null, null,
                null, null
            )
            returning id
            """;
    final String insertDeadLetterTaskSql = """
        insert into batch.dead_letter_task
          (tenant_id, source_type, source_id, dead_letter_reason, payload_ref,
           replay_status, replay_count, last_replay_at, last_replay_result, trace_id)
        values (?, 'JOB_PARTITION', ?, ?, ?, 'NEW', 0, null, null, ?)
        """;
    final String selectIdSql3 = """
        select id
        from batch.dead_letter_task
        where tenant_id = ?
          and source_type = 'JOB_PARTITION'
          and source_id = ?
          and trace_id = ?
        order by id desc
        limit 1
        """;

    Long jobDefinitionId =
        jdbcTemplate.queryForObject(selectIdSql, Long.class, TENANT, seed.jobCode());
    assertThat(jobDefinitionId).isNotNull();

    Long triggerRequestId =
        jdbcTemplate.queryForObject(selectIdSql2, Long.class, TENANT, seed.requestId());
    assertThat(triggerRequestId).isNotNull();

    String instanceNo = "inst-dlq-e2e-" + UUID.randomUUID();
    String paramsSnapshot = objectMapper.writeValueAsString(params);
    String resultSummary = "{}";
    Long jobInstanceId = jdbcTemplate.queryForObject(
        insertJobInstanceSql,
        Long.class,
        TENANT,
        jobDefinitionId,
        triggerRequestId,
        seed.jobCode(),
        instanceNo,
        workerGroup,
        seed.dedupKey(),
        traceId,
        paramsSnapshot,
        resultSummary);
    assertThat(jobInstanceId).isNotNull();

    Long partitionId = jdbcTemplate.queryForObject(
        insertJobPartitionSql,
        Long.class,
        TENANT,
        jobInstanceId,
        TENANT,
        jobInstanceId,
        workerGroup,
        seed.dedupKey() + ":partition",
        paramsSnapshot);
    assertThat(partitionId).isNotNull();

    Long taskId = jdbcTemplate.queryForObject(
        insertJobTaskSql,
        Long.class,
        TENANT,
        jobInstanceId,
        TENANT,
        jobInstanceId,
        partitionId,
        taskType,
        paramsSnapshot);
    assertThat(taskId).isNotNull();

    jdbcTemplate.update(
        insertDeadLetterTaskSql,
        TENANT,
        partitionId,
        "manual dead letter for e2e",
        instanceNo + ":" + taskId,
        traceId);

    return jdbcTemplate.queryForObject(selectIdSql3, Long.class, TENANT, partitionId, traceId);
  }
}
