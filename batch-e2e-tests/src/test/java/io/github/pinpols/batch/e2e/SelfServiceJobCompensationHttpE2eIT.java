package io.github.pinpols.batch.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleAuthTokenResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.e2e.apps.E2eConsoleImportApplication;
import io.github.pinpols.batch.e2e.support.E2eBusinessSchema;
import io.github.pinpols.batch.e2e.support.E2eOutboxPublishSupport;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture;
import io.github.pinpols.batch.e2e.support.E2eScenarioFixture.LaunchSeed;
import io.github.pinpols.batch.e2e.support.E2eTestSql;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplayService;
import io.github.pinpols.batch.orchestrator.application.service.replay.BatchDayReplaySubmitCommand;
import io.github.pinpols.batch.orchestrator.application.service.workflow.WorkflowRunLaunchService;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.OrchestratorConfigCacheService;
import io.github.pinpols.batch.orchestrator.infrastructure.scheduler.BatchDayReplayDispatcher;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.time.LocalDate;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.awaitility.core.ThrowingRunnable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.test.web.reactive.server.WebTestClient;

/** Real authenticated Console self-service approval through a completed Workflow and Worker rerun. */
@E2eSpringBootTest(
    classes = E2eConsoleImportApplication.class,
    webEnvironment =
        org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.worker.import.worker-type=IMPORT",
      "batch.security.bypass-mode=true",
      "batch.security.internal-secret=self-service-e2e-internal-secret-32chars",
      "batch.console.security.default-tenant-id=t1",
      "batch.console.security.allowed-tenants=t1",
      "batch.console.orchestrator.base-url=http://127.0.0.1:${local.server.port}",
      "batch.console.trigger.base-url=http://127.0.0.1:${local.server.port}"
    })
@E2eBusinessSchema
@Sql(scripts = E2eTestSql.IMPORT_TEMPLATE_SEED)
@Tag("critical")
@DisplayName("租户自助 JOB 补偿经真实认证审批与 Worker 重跑")
class SelfServiceJobCompensationHttpE2eIT extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final LocalDate BIZ_DATE = LocalDate.of(2026, 1, 15);
  private static final String SELECT_APPROVAL_STATUS_SQL = """
      select approval_status from batch.approval_command where tenant_id=? and approval_no=?
      """;
  private static final String SELECT_COMPENSATION_COMMAND_SQL = """
      select command_no, target_id, command_status, result_summary::text result_summary
      from batch.compensation_command where tenant_id=? and approval_id=?
      """;
  private static final String SELECT_RERUN_STATUS_SQL = """
      select ji.instance_status, ji.job_definition_version, task.error_message
      from batch.job_instance ji
      join batch.workflow_node_run n on n.job_instance_id=ji.id and n.node_type='JOB'
      join batch.job_task task on task.tenant_id=ji.tenant_id and task.job_instance_id=ji.id
      join batch.workflow_run w on w.id=n.workflow_run_id and w.tenant_id=ji.tenant_id
      join batch.trigger_request tr on tr.id=w.trigger_request_id and tr.tenant_id=w.tenant_id
      where ji.tenant_id=? and tr.request_id=?
        and not exists (
            select 1 from batch.workflow_node_run newer
            where newer.workflow_run_id=n.workflow_run_id
              and newer.node_code=n.node_code
              and newer.run_seq > n.run_seq
        )
      """;
  private static final String COUNT_CUSTOMER_ACCOUNT_SQL = """
      select count(*) from biz.customer_account where tenant_id=? and customer_no=?
      """;
  private static final String COUNT_COMPENSATION_COMMAND_SQL = """
      select count(*) from batch.compensation_command where tenant_id=? and approval_id=?
      """;
  private static final String SELECT_RERUN_INSTANCE_SQL = """
      select ji.id, ji.job_definition_version, n.workflow_run_id
      from batch.job_instance ji
      join batch.workflow_node_run n on n.job_instance_id=ji.id and n.node_type='JOB'
      join batch.workflow_run w on w.id=n.workflow_run_id and w.tenant_id=ji.tenant_id
      join batch.trigger_request tr on tr.id=w.trigger_request_id and tr.tenant_id=w.tenant_id
      where ji.tenant_id=? and tr.request_id=?
        and not exists (
            select 1 from batch.workflow_node_run newer
            where newer.workflow_run_id=n.workflow_run_id
              and newer.node_code=n.node_code
              and newer.run_seq > n.run_seq
        )
      """;
  private static final String SELECT_WORKFLOW_RUN_STATUS_SQL = """
      select run_status from batch.workflow_run where tenant_id=? and id=?
      """;
  private static final String SELECT_INSTANCE_SQL = """
      select id, instance_no, instance_status, calendar_code
      from batch.job_instance where tenant_id=? and dedup_key=?
      """;
  private static final String SELECT_INSTANCE_BY_ID_SQL = """
      select id, instance_no, instance_status
      from batch.job_instance where tenant_id=? and id=?
      """;
  private static final String SELECT_REPLAY_SESSION_STATUS_SQL = """
      select session.status from batch.batch_day_replay_session session
      where session.tenant_id=? and session.id=?
      """;
  private static final String SELECT_REPLAY_ENTRY_SQL = """
      select replay_entry.status, replay_entry.rerun_instance_id
      from batch.batch_day_replay_entry replay_entry
      where replay_entry.tenant_id=? and replay_entry.session_id=?
      """;
  private static final String SELECT_APPROVAL_STATE_SQL = """
      select approval_status, requester_id, action_type, target_type, target_id
      from batch.approval_command where tenant_id=? and approval_no=?
      """;
  private static final String SELECT_FAILED_NODE_SQL = """
      select node_run.id, node_run.job_instance_id, node_run.node_status,
        node_run.skip_reason_code, node_run.skip_reason
      from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.id=node_run.workflow_run_id and workflow_run.tenant_id=?
      where node_run.workflow_run_id=? and node_run.node_code='JOB'
      order by node_run.run_seq desc limit 1
      """;
  private static final String SELECT_SKIP_AUDIT_SQL = """
      select job_instance_id, extra_json::text
      from batch.job_execution_log
      where tenant_id=? and log_type='AUDIT' and message='WORKFLOW_NODE_SKIP'
        and detail_ref='workflow_node_run'
        and extra_json->>'workflowRunId'=? and extra_json->>'nodeRunId'=?
      order by id desc limit 1
      """;
  private static final String SELECT_RUN_STATUS_SQL = """
      select run_status from batch.workflow_run where tenant_id=? and id=?
      """;
  private static final String SELECT_SOURCE_WORKFLOW_RUN_ID_SQL = """
      select node_run.workflow_run_id
      from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.id=node_run.workflow_run_id and workflow_run.tenant_id=?
      where node_run.job_instance_id=? and node_run.node_type='JOB'
      order by node_run.run_seq desc limit 1
      """;
  private static final String ADVANCE_JOB_DEFINITION_VERSION_SQL = """
      update batch.job_definition
      set version=version+1
      where tenant_id=? and job_code=? and version=1 and enabled=true
      """;
  private static final String SELECT_JOB_DEFINITION_VERSION_SQL = """
      select version from batch.job_definition where tenant_id=? and job_code=?
      """;
  private static final String SELECT_RERUN_SNAPSHOT_JOB_VERSION_SQL = """
      select (execution_snapshot->'jobDefinitions'->'JOB'->>'jobDefinitionVersion')::integer
      from batch.workflow_run where tenant_id=? and id=?
      """;
  private static final String SELECT_NODE_STATUS_SQL = """
      select node_run.node_status from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.id=node_run.workflow_run_id and workflow_run.tenant_id=?
      where node_run.workflow_run_id=? and node_run.node_code=?
      order by node_run.run_seq desc limit 1
      """;
  private static final String COUNT_NODE_RUNS_SQL = """
      select count(*) from batch.workflow_node_run node_run
      join batch.workflow_run workflow_run
        on workflow_run.id=node_run.workflow_run_id and workflow_run.tenant_id=?
      where node_run.workflow_run_id=? and node_run.node_code=?
      """;
  private static final String UPDATE_WORKFLOW_DEFINITION_FOR_DAG_SQL = """
      update batch.workflow_definition
      set single_job_definition_id=null, version=version+1
      where tenant_id=? and id=?
      """;
  private static final String MOVE_END_NODE_FOR_DAG_SQL = """
      update batch.workflow_node set node_order=3
      where tenant_id=? and workflow_definition_id=? and node_code='END'
      """;
  private static final String INSERT_WAIT_NODE_FOR_DAG_SQL = """
      insert into batch.workflow_node
        (tenant_id,workflow_definition_id,node_code,node_name,node_type,node_params,
         node_order,retry_policy,retry_max_count,timeout_seconds,enabled)
      values (?, ?, 'WAIT_RELEASE', 'Controlled release', 'WAIT', ?::jsonb,
              2, 'NONE', 0, 300, true)
      """;
  private static final String DELETE_WORKFLOW_EDGES_FOR_DAG_SQL = """
      delete from batch.workflow_edge where tenant_id=? and workflow_definition_id=?
      """;
  private static final String INSERT_WORKFLOW_EDGES_FOR_DAG_SQL = """
      insert into batch.workflow_edge
        (tenant_id,workflow_definition_id,from_node_code,to_node_code,edge_type,enabled)
      values (?, ?, 'START', 'JOB', 'ALWAYS', true),
             (?, ?, 'START', 'WAIT_RELEASE', 'ALWAYS', true),
             (?, ?, 'JOB', 'END', 'SUCCESS', true),
             (?, ?, 'WAIT_RELEASE', 'END', 'SUCCESS', true)
      """;

  @Autowired
  private JdbcTemplate jdbc;

  @Autowired
  private E2eOutboxPublishSupport outboxPublishSupport;

  @Autowired
  private WorkflowRunLaunchService launchService;

  @Autowired
  private OrchestratorConfigCacheService configCacheService;

  @Autowired
  private BatchDayReplayService batchDayReplayService;

  @Autowired
  private BatchDayReplayDispatcher batchDayReplayDispatcher;

  @Autowired
  @Qualifier("importBusinessDataSource")
  private javax.sql.DataSource businessDataSource;

  @Autowired
  private ObjectMapper objectMapper;

  @LocalServerPort
  private int orchestratorPort;

  @ParameterizedTest(name = "failed source={0}, replay before compensation={1}")
  @MethodSource("selfServiceCompensationScenarios")
  @DisplayName("TENANT_USER 补偿成功、失败或重放后的 JobInstance 并按来源配置完成新 Workflow")
  void shouldExecuteAuthenticatedSelfServiceCompensation_asWorkflowRerun(
      boolean sourceInitiallyFailed,
      boolean replayBeforeSelfServiceCompensation) throws Exception {
    executeSelfServiceCompensationScenario(
        sourceInitiallyFailed,
        replayBeforeSelfServiceCompensation);
  }

  @Test
  @DisplayName("真实失败来源经 BatchDay replay 成功后仍可完成自助补偿")
  void shouldCompensateReplayedFailedSourceThroughAuthenticatedConsole() throws Exception {
    executeSelfServiceCompensationScenario(true, true);
  }

  private void executeSelfServiceCompensationScenario(
      boolean sourceInitiallyFailed,
      boolean replayBeforeSelfServiceCompensation) throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String customerNo = "SELF_SERVICE_" + suffix;
    String requestId = "self-service-source-" + suffix;
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API);
    String customerName = "self service";
    String customerType = "PERSONAL";
    String certificateNo = "ID-" + customerNo;
    String mobileNo = "13800000001";
    String email = "self-service@example.com";
    String status = "ACTIVE";
    String repairedImportContentTemplate = """
        [{"customerNo":"%s","customerName":"%s","customerType":"%s",
          "certificateNo":"%s","mobileNo":"%s","email":"%s","status":"%s"}]
        """;
    String repairedImportContent = repairedImportContentTemplate.formatted(
        customerNo,
        customerName,
        customerType,
        certificateNo,
        mobileNo,
        email,
        status);
    CompensationSourceInput sourceInput = prepareCompensationSourceInput(
        suffix,
        customerNo,
        repairedImportContent,
        sourceInitiallyFailed);
    LaunchRequest sourceLaunchRequest = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        requestId,
        sourceInput.launchParams());
    launchService.launch(sourceLaunchRequest);
    outboxPublishSupport.publishAllPending(TENANT);

    String expectedSourceStatus = sourceInitiallyFailed ? "FAILED" : "SUCCESS";
    ThrowingRunnable sourceInstanceCompleted = () -> {
      Map<String, Object> currentSourceInstance =
          jdbc.queryForMap(SELECT_INSTANCE_SQL, TENANT, seed.dedupKey());
      assertThat(currentSourceInstance)
          .containsEntry("instance_status", expectedSourceStatus);
    };
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(sourceInstanceCompleted);
    Map<String, Object> sourceInstance =
        jdbc.queryForMap(SELECT_INSTANCE_SQL, TENANT, seed.dedupKey());
    long sourceInstanceId = ((Number) sourceInstance.get("id")).longValue();
    Long sourceWorkflowRunId = jdbc.queryForObject(
        SELECT_SOURCE_WORKFLOW_RUN_ID_SQL,
        Long.class,
        TENANT,
        sourceInstanceId);
    Map<String, Object> sourceJobNode =
        jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, sourceWorkflowRunId);
    String sourceWorkflowStatus = jdbc.queryForObject(
        SELECT_WORKFLOW_RUN_STATUS_SQL,
        String.class,
        TENANT,
        sourceWorkflowRunId);
    assertThat(sourceJobNode).containsEntry("node_status", expectedSourceStatus);
    assertThat(sourceWorkflowStatus).isEqualTo(expectedSourceStatus);

    Map<String, Object> compensationSourceInstance = sourceInstance;
    long compensationSourceWorkflowRunId = sourceWorkflowRunId;
    Long replaySessionId = null;
    if (replayBeforeSelfServiceCompensation) {
      repairCompensationObject(sourceInput);
      replaySessionId = replayFailedSource(seed, sourceInstance);
      Map<String, Object> replayEntry = jdbc.queryForMap(
          SELECT_REPLAY_ENTRY_SQL,
          TENANT,
          replaySessionId);
      assertThat(replayEntry).containsEntry("status", "SUCCEEDED");
      Number replayedInstanceIdValue = (Number) replayEntry.get("rerun_instance_id");
      long replayedInstanceId = replayedInstanceIdValue.longValue();
      compensationSourceInstance = jdbc.queryForMap(
          SELECT_INSTANCE_BY_ID_SQL,
          TENANT,
          replayedInstanceId);
      assertThat(compensationSourceInstance).containsEntry("instance_status", "SUCCESS");
      Long replayedWorkflowRunId = jdbc.queryForObject(
          SELECT_SOURCE_WORKFLOW_RUN_ID_SQL,
          Long.class,
          TENANT,
          replayedInstanceId);
      compensationSourceWorkflowRunId = replayedWorkflowRunId;
    }
    String compensationSourceInstanceNo =
        (String) compensationSourceInstance.get("instance_no");
    long compensationSourceInstanceId =
        ((Number) compensationSourceInstance.get("id")).longValue();

    ConfigurableApplicationContext startedConsole = startSecuredConsole();
    try (ConfigurableApplicationContext securedConsole = startedConsole) {
      BatchSecurityProperties securedConsoleSecurity =
          securedConsole.getBean(BatchSecurityProperties.class);
      assertThat(securedConsoleSecurity.isBypassMode()).isFalse();
      int consolePort =
          securedConsole.getEnvironment().getProperty("local.server.port", Integer.class);
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl("http://127.0.0.1:" + consolePort)
          .responseTimeout(Duration.ofSeconds(45))
          .build();
      ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
      Actor requester =
          authenticate(client, jwtService, "self-service-requester", "ROLE_TENANT_USER");
      Actor approver = authenticate(client, jwtService, "self-service-approver", "ROLE_ADMIN");
      Actor auditor = authenticate(client, jwtService, "self-service-auditor", "ROLE_AUDITOR");

      String requestPath = "/api/console/self-service/jobs/compensation-request";
      Map<String, Object> request = Map.of(
          "tenantId",
          TENANT,
          "jobCode",
          seed.jobCode(),
          "bizDate",
          BIZ_DATE.toString(),
          "compensationType",
          "JOB",
          "targetInstanceNo",
          compensationSourceInstanceNo,
          "reason",
          "self-service compensation after source " + expectedSourceStatus.toLowerCase());
      WebTestClient.ResponseSpec createRequestResponse =
          post(client, requester, requestPath, request).expectStatus().isOk();
      JsonNode created = response(createRequestResponse);
      String approvalNo = created.path("data").asText();
      assertThat(approvalNo).isNotBlank();

      Map<String, Object> pending = jdbc.queryForMap(SELECT_APPROVAL_STATE_SQL, TENANT, approvalNo);
      assertThat(pending)
          .containsEntry("approval_status", "PENDING")
          .containsEntry("requester_id", "self-service-requester")
          .containsEntry("action_type", "COMPENSATION")
          .containsEntry("target_type", "JOB_INSTANCE")
          .containsEntry("target_id", String.valueOf(compensationSourceInstanceId));

      String approvePath = "/api/console/approvals/" + approvalNo + "/approve";
      Map<String, Object> approvalAction = Map.of(
          "tenantId", TENANT,
          "operatorId", "self-service-approver",
          "reason", "independent approval for exact source instance");
      post(client, auditor, approvePath, approvalAction).expectStatus().isForbidden();
      post(client, requester, approvePath, approvalAction).expectStatus().isForbidden();
      String statusAfterDeniedApproval =
          jdbc.queryForObject(SELECT_APPROVAL_STATUS_SQL, String.class, TENANT, approvalNo);
      assertThat(statusAfterDeniedApproval).isEqualTo("PENDING");

      WebTestClient.ResponseSpec approvalResponse =
          post(client, approver, approvePath, approvalAction).expectStatus().isOk();
      JsonNode approved = response(approvalResponse);
      assertThat(approved.path("data").asText()).isNotBlank();
      Map<String, Object> command =
          jdbc.queryForMap(SELECT_COMPENSATION_COMMAND_SQL, TENANT, approvalNo);
      assertThat(command)
          .containsEntry("command_status", "SUCCESS")
          .containsEntry("target_id", compensationSourceInstance.get("id"));
      JsonNode commandResult = objectMapper.readTree((String) command.get("result_summary"));
      assertThat(commandResult.path("sourceInstanceNo").asText())
          .isEqualTo(compensationSourceInstanceNo);
      String rerunRequestId = commandResult.path("launchRequestId").asText();
      assertThat(rerunRequestId)
          .as("compensation result summary %s", commandResult)
          .isNotBlank();
      String statusAfterApproval =
          jdbc.queryForObject(SELECT_APPROVAL_STATUS_SQL, String.class, TENANT, approvalNo);
      assertThat(statusAfterApproval).isEqualTo("EXECUTED");

      int advancedDefinitionCount = jdbc.update(
          ADVANCE_JOB_DEFINITION_VERSION_SQL, TENANT, seed.jobCode());
      assertThat(advancedDefinitionCount).isEqualTo(1);
      Integer latestJobDefinitionVersion = jdbc.queryForObject(
          SELECT_JOB_DEFINITION_VERSION_SQL, Integer.class, TENANT, seed.jobCode());
      assertThat(latestJobDefinitionVersion).isEqualTo(2);
      configCacheService.evictJobDefinition(TENANT, seed.jobCode());
      if (sourceInitiallyFailed && !replayBeforeSelfServiceCompensation) {
        repairCompensationObject(sourceInput);
      }

      ThrowingRunnable rerunCompleted = () -> {
        outboxPublishSupport.publishAllPending(TENANT);
        List<Map<String, Object>> currentRerunInstances =
            jdbc.queryForList(SELECT_RERUN_STATUS_SQL, TENANT, rerunRequestId);
        assertThat(currentRerunInstances).hasSize(1);
        String currentRerunStatus =
            (String) currentRerunInstances.getFirst().get("instance_status");
        String currentRerunErrorMessage =
            (String) currentRerunInstances.getFirst().get("error_message");
        assertThat(currentRerunStatus)
            .as("rerun instance=%s, task error=%s", currentRerunInstances, currentRerunErrorMessage)
            .isEqualTo("SUCCESS");
        Integer currentRerunDefinitionVersion =
            (Integer) currentRerunInstances.getFirst().get("job_definition_version");
        assertThat(currentRerunDefinitionVersion).isEqualTo(1);
      };
      await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(rerunCompleted);
      Map<String, Object> rerunInstance =
          jdbc.queryForMap(SELECT_RERUN_INSTANCE_SQL, TENANT, rerunRequestId);
      long rerunInstanceId = ((Number) rerunInstance.get("id")).longValue();
      assertThat(rerunInstanceId).isNotEqualTo(compensationSourceInstanceId);
      long rerunWorkflowRunId = ((Number) rerunInstance.get("workflow_run_id")).longValue();
      Integer frozenJobDefinitionVersion = jdbc.queryForObject(
          SELECT_RERUN_SNAPSHOT_JOB_VERSION_SQL,
          Integer.class,
          TENANT,
          rerunWorkflowRunId);
      assertThat(frozenJobDefinitionVersion).isEqualTo(1);
      String rerunWorkflowStatus = jdbc.queryForObject(
          SELECT_WORKFLOW_RUN_STATUS_SQL, String.class, TENANT, rerunWorkflowRunId);
      assertThat(rerunWorkflowStatus).isEqualTo("SUCCESS");
      String sourceWorkflowStatusAfterRerun = jdbc.queryForObject(
          SELECT_WORKFLOW_RUN_STATUS_SQL,
          String.class,
          TENANT,
          compensationSourceWorkflowRunId);
      String expectedCompensationSourceRunStatus =
          replayBeforeSelfServiceCompensation ? "SUCCESS" : expectedSourceStatus;
      assertThat(sourceWorkflowStatusAfterRerun).isEqualTo(expectedCompensationSourceRunStatus);
      String sourceNodeStatusAfterRerun = jdbc.queryForObject(
          SELECT_NODE_STATUS_SQL,
          String.class,
          TENANT,
          compensationSourceWorkflowRunId,
          "JOB");
      assertThat(sourceNodeStatusAfterRerun).isEqualTo(expectedCompensationSourceRunStatus);
      String originalSourceWorkflowStatusAfterRerun = jdbc.queryForObject(
          SELECT_WORKFLOW_RUN_STATUS_SQL,
          String.class,
          TENANT,
          sourceWorkflowRunId);
      assertThat(originalSourceWorkflowStatusAfterRerun).isEqualTo(expectedSourceStatus);
      if (replaySessionId != null) {
        String replaySessionStatusAfterCompensation = jdbc.queryForObject(
            SELECT_REPLAY_SESSION_STATUS_SQL,
            String.class,
            TENANT,
            replaySessionId);
        assertThat(replaySessionStatusAfterCompensation).isEqualTo("SUCCEEDED");
      }
      JdbcTemplate businessJdbc = new JdbcTemplate(businessDataSource);
      Integer customerRows = businessJdbc.queryForObject(
          COUNT_CUSTOMER_ACCOUNT_SQL, Integer.class, TENANT, customerNo);
      assertThat(customerRows).isEqualTo(1);
      Integer compensationCommandCount =
          jdbc.queryForObject(COUNT_COMPENSATION_COMMAND_SQL, Integer.class, TENANT, approvalNo);
      assertThat(compensationCommandCount).isEqualTo(1);
    }
  }

  private static Stream<Arguments> selfServiceCompensationScenarios() {
    return Stream.of(
        Arguments.of(false, false),
        Arguments.of(true, false));
  }

  private Long replayFailedSource(
      LaunchSeed seed, Map<String, Object> failedSourceInstance) {
    String calendarCode = (String) failedSourceInstance.get("calendar_code");
    String replayTraceId = "self-service-replay-" + UUID.randomUUID();
    BatchDayReplaySubmitCommand previewCommand = replayCommand(
        seed.jobCode(), calendarCode, replayTraceId, null);
    var preview = batchDayReplayService.preview(previewCommand);
    long failedSourceInstanceId =
        ((Number) failedSourceInstance.get("id")).longValue();
    assertThat(preview.entries())
        .singleElement()
        .satisfies(entry -> assertThat(entry.sourceInstanceId())
            .isEqualTo(failedSourceInstanceId));

    BatchDayReplaySubmitCommand submitCommand = replayCommand(
        seed.jobCode(), calendarCode, replayTraceId, preview.previewToken());
    var session = batchDayReplayService.submit(submitCommand);
    batchDayReplayDispatcher.scheduledDispatch();
    ThrowingRunnable replayCompleted = () -> {
      outboxPublishSupport.publishAllPending(TENANT);
      String currentSessionStatus = jdbc.queryForObject(
          SELECT_REPLAY_SESSION_STATUS_SQL,
          String.class,
          TENANT,
          session.id());
      assertThat(currentSessionStatus).isEqualTo("SUCCEEDED");
    };
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(replayCompleted);
    return session.id();
  }

  private static BatchDayReplaySubmitCommand replayCommand(
      String jobCode,
      String calendarCode,
      String traceId,
      String previewToken) {
    return BatchDayReplaySubmitCommand.builder()
        .tenantId(TENANT)
        .calendarCode(calendarCode)
        .bizDate(BIZ_DATE)
        .scope("SUBSET_JOB_CODES")
        .jobCodes(List.of(jobCode))
        .resultPolicy("CREATE_NEW_VERSION")
        .configVersionPolicy("USE_ORIGINAL_CONFIG")
        .reason("replay failed source before self-service compensation")
        .requestedBy("self-service-replay-e2e")
        .autoApprove(true)
        .traceId(traceId)
        .executionMode("REPLAY")
        .candidateSource("EXISTING_INSTANCES")
        .previewToken(previewToken)
        .build();
  }

  private void repairCompensationObject(CompensationSourceInput sourceInput) {
    byte[] repairedFileContent = sourceInput.repairedFileContent();
    String storageBucket = sourceInput.storageBucket();
    String storagePath = sourceInput.storagePath();
    putObject(storageBucket, storagePath, repairedFileContent);
  }

  @Test
  @DisplayName("真实多角色 Console 只允许管理员跳过失败 JOB 并持久化准确目标、操作者和理由")
  void shouldSkipFailedJobNodeThroughAuthenticatedConsoleAndPersistAudit() throws Exception {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
        jdbc, TENANT, "IMPORT", "import", TriggerType.API);
    String requestId = "manual-skip-" + suffix;
    Map<String, Object> failedImportParams = Map.of(
        "fileFormatType", "JSON",
        "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
        "bizType", "CUSTOMER",
        "content", "this is not valid JSON");
    LaunchRequest launchRequest = new LaunchRequest(
        TENANT,
        seed.workflowDefinitionId(),
        BIZ_DATE,
        TriggerType.API,
        seed.requestId(),
        requestId,
        failedImportParams);
    LaunchResponse launchResponse = launchService.launch(launchRequest);
    String workflowRunReference = launchResponse.instanceNo();
    long workflowRunId = Long.parseLong(workflowRunReference);
    outboxPublishSupport.publishAllPending(TENANT);

    ThrowingRunnable failedJobNode = () -> {
      Map<String, Object> currentNode =
          jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, workflowRunId);
      assertThat(currentNode).containsEntry("node_status", "FAILED");
    };
    await()
        .atMost(Duration.ofSeconds(90))
        .pollInterval(Duration.ofMillis(200))
        .untilAsserted(failedJobNode);

    ConfigurableApplicationContext startedConsole = startSecuredConsole();
    try (ConfigurableApplicationContext securedConsole = startedConsole) {
      int consolePort =
          securedConsole.getEnvironment().getProperty("local.server.port", Integer.class);
      String consoleBaseUrl = "http://127.0.0.1:" + consolePort;
      WebTestClient client = WebTestClient.bindToServer()
          .baseUrl(consoleBaseUrl)
          .responseTimeout(Duration.ofSeconds(45))
          .build();
      ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
      Actor administrator = authenticate(client, jwtService, "manual-skip-admin", "ROLE_ADMIN");
      Actor auditor = authenticate(client, jwtService, "manual-skip-auditor", "ROLE_AUDITOR");
      String reason = "skip failed import after operator verified upstream data";
      UriComponentsBuilder skipNodeUriBuilder = UriComponentsBuilder.fromUriString(consoleBaseUrl);
      skipNodeUriBuilder
          .path("/api/console/workflow-runs/{id}/skip-node")
          .queryParam("tenantId", TENANT)
          .queryParam("nodeCode", "JOB")
          .queryParam("reason", reason);
      URI skipNodeUri = skipNodeUriBuilder.buildAndExpand(workflowRunId).encode().toUri();

      WebTestClient.ResponseSpec deniedResponse = postSkipNode(client, auditor, skipNodeUri);
      deniedResponse.expectStatus().isForbidden();
      Map<String, Object> nodeAfterDeniedRequest =
          jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, workflowRunId);
      assertThat(nodeAfterDeniedRequest).containsEntry("node_status", "FAILED");

      WebTestClient.ResponseSpec skipResponse = postSkipNode(client, administrator, skipNodeUri);
      skipResponse.expectStatus().isOk();
      JsonNode skippedNodeResponse = response(skipResponse);
      JsonNode skippedNodeData = skippedNodeResponse.path("data");
      long responseWorkflowRunId = skippedNodeData.path("id").asLong();
      String responseNodeCode = skippedNodeData.path("nodeCode").asText();
      String responseNodeStatus = skippedNodeData.path("nodeStatus").asText();
      assertThat(responseWorkflowRunId).isEqualTo(workflowRunId);
      assertThat(responseNodeCode).isEqualTo("JOB");
      assertThat(responseNodeStatus).isEqualTo("SKIPPED");

      Map<String, Object> skippedNode =
          jdbc.queryForMap(SELECT_FAILED_NODE_SQL, TENANT, workflowRunId);
      assertThat(skippedNode)
          .containsEntry("node_status", "SKIPPED")
          .containsEntry("skip_reason_code", "OPERATOR_SKIPPED")
          .containsEntry("skip_reason", reason);
      long nodeRunId = ((Number) skippedNode.get("id")).longValue();
      long jobInstanceId = ((Number) skippedNode.get("job_instance_id")).longValue();
      Map<String, Object> skipAuditRow = jdbc.queryForMap(
          SELECT_SKIP_AUDIT_SQL,
          TENANT,
          String.valueOf(workflowRunId),
          String.valueOf(nodeRunId));
      String auditJson = (String) skipAuditRow.get("extra_json");
      long auditedJobInstanceId = ((Number) skipAuditRow.get("job_instance_id")).longValue();
      JsonNode audit = objectMapper.readTree(auditJson);
      long auditedWorkflowRunId = audit.path("workflowRunId").asLong();
      long auditedNodeRunId = audit.path("nodeRunId").asLong();
      String auditedOperatorId = audit.path("operatorId").asText();
      String auditedOperatorType = audit.path("operatorType").asText();
      String auditedReason = audit.path("reason").asText();
      assertThat(auditedWorkflowRunId).isEqualTo(workflowRunId);
      assertThat(auditedNodeRunId).isEqualTo(nodeRunId);
      assertThat(auditedOperatorId).isEqualTo("manual-skip-admin");
      assertThat(auditedOperatorType).isEqualTo("REQUEST");
      assertThat(auditedReason).isEqualTo(reason);

      // The real Job failure already committed the WorkflowRun terminal state and event.
      // Skipping the node records operator disposition without rewriting terminal history.
      String workflowStatusAfterNodeSkip =
          jdbc.queryForObject(SELECT_RUN_STATUS_SQL, String.class, TENANT, workflowRunId);
      assertThat(workflowStatusAfterNodeSkip).isEqualTo("FAILED");
      assertThat(auditedJobInstanceId).isEqualTo(jobInstanceId);

      UriComponentsBuilder operationAuditUriBuilder = UriComponentsBuilder
          .fromUriString(consoleBaseUrl)
          .path("/api/console/queries/operation-audits");
      operationAuditUriBuilder
          .queryParam("tenantId", TENANT)
          .queryParam("aggregateType", "workflow_run")
          .queryParam("aggregateId", workflowRunId)
          .queryParam("action", "workflowRun.skipNode")
          .queryParam("pageNo", 1)
          .queryParam("pageSize", 10);
      String operationAuditUri = operationAuditUriBuilder.build().encode().toUriString();
      WebTestClient.ResponseSpec operationAuditResponse = client
          .get()
          .uri(operationAuditUri)
          .cookie("batch_console_token", administrator.jwt())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .exchange();
      operationAuditResponse.expectStatus().isOk();
      JsonNode operationAuditPage = response(operationAuditResponse);
      JsonNode operationAuditData = operationAuditPage.path("data");
      JsonNode operationAuditItems = operationAuditData.path("items");
      assertThat(operationAuditItems.isArray()).isTrue();
      assertThat(operationAuditItems.size()).isEqualTo(1);
      JsonNode operationAudit = operationAuditItems.get(0);
      String operationAuditOperator = operationAudit.path("operatorId").asText();
      String operationAuditResult = operationAudit.path("result").asText();
      String operationAuditAggregateId = operationAudit.path("aggregateId").asText();
      String operationAuditParams = operationAudit.path("params").asText();
      assertThat(operationAuditOperator).isEqualTo("manual-skip-admin");
      assertThat(operationAuditResult).isEqualTo("SUCCESS");
      assertThat(operationAuditAggregateId).isEqualTo(String.valueOf(workflowRunId));
      assertThat(operationAuditParams).contains(reason, "JOB");

      UriComponentsBuilder jobExecutionLogUriBuilder = UriComponentsBuilder
          .fromUriString(consoleBaseUrl)
          .path("/api/console/queries/job-execution-logs");
      jobExecutionLogUriBuilder
          .queryParam("tenantId", TENANT)
          .queryParam("jobInstanceId", jobInstanceId)
          .queryParam("logType", "AUDIT")
          .queryParam("keyword", "WORKFLOW_NODE_SKIP")
          .queryParam("pageNo", 1)
          .queryParam("pageSize", 10);
      String jobExecutionLogUri = jobExecutionLogUriBuilder.build().encode().toUriString();
      WebTestClient.ResponseSpec jobExecutionLogResponse = client
          .get()
          .uri(jobExecutionLogUri)
          .cookie("batch_console_token", administrator.jwt())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
          .exchange();
      jobExecutionLogResponse.expectStatus().isOk();
      JsonNode jobExecutionLogPage = response(jobExecutionLogResponse);
      JsonNode jobExecutionLogData = jobExecutionLogPage.path("data");
      JsonNode jobExecutionLogItems = jobExecutionLogData.path("items");
      assertThat(jobExecutionLogItems.isArray()).isTrue();
      assertThat(jobExecutionLogItems.size()).isEqualTo(1);
      JsonNode jobExecutionLog = jobExecutionLogItems.get(0);
      long queriedJobInstanceId = jobExecutionLog.path("jobInstanceId").asLong();
      String queriedLogMessage = jobExecutionLog.path("message").asText();
      String queriedDetailReference = jobExecutionLog.path("detailRef").asText();
      String queriedAuditJson = jobExecutionLog.path("extraJson").asText();
      assertThat(queriedJobInstanceId).isEqualTo(jobInstanceId);
      assertThat(queriedLogMessage).isEqualTo("WORKFLOW_NODE_SKIP");
      assertThat(queriedDetailReference).isEqualTo("workflow_node_run");
      JsonNode queriedAudit = objectMapper.readTree(queriedAuditJson);
      long queriedWorkflowRunId = queriedAudit.path("workflowRunId").asLong();
      long queriedNodeRunId = queriedAudit.path("nodeRunId").asLong();
      String queriedOperator = queriedAudit.path("operatorId").asText();
      String queriedReason = queriedAudit.path("reason").asText();
      assertThat(queriedWorkflowRunId).isEqualTo(workflowRunId);
      assertThat(queriedNodeRunId).isEqualTo(nodeRunId);
      assertThat(queriedOperator).isEqualTo("manual-skip-admin");
      assertThat(queriedReason).isEqualTo(reason);
    }
  }

  @Test
  @DisplayName("认证 Console 跳过失败 DAG 节点后等待 Join 分支并按 DAG 规则完成")
  void shouldAdvanceDagJoinAfterAuthenticatedSkipOfFailedNode() throws Exception {
    String previousAllowPrivate = System.getProperty("batch.security.ssrf-guard.allow-private");
    System.setProperty("batch.security.ssrf-guard.allow-private", "true");
    InetSocketAddress sensorAddress = new InetSocketAddress("127.0.0.1", 0);
    HttpServer sensorEndpoint = HttpServer.create(sensorAddress, 0);
    AtomicBoolean sensorReady = new AtomicBoolean(false);
    sensorEndpoint.createContext("/sensor", exchange -> {
      String sensorStatus = sensorReady.get() ? "READY" : "WAIT";
      byte[] responseBody = ("{\"status\":\"" + sensorStatus + "\"}")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, responseBody.length);
      exchange.getResponseBody().write(responseBody);
      exchange.close();
    });
    sensorEndpoint.start();
    try {
      UUID generatedSuffix = UUID.randomUUID();
      String suffixWithSeparators = generatedSuffix.toString();
      String suffix = suffixWithSeparators.replace("-", "");
      LaunchSeed seed = E2eScenarioFixture.prepareLaunchWithoutPreSeededWorker(
          jdbc, TENANT, "IMPORT", "import", TriggerType.API);
      int sensorPort = sensorEndpoint.getAddress().getPort();
      String sensorUrl = "http://127.0.0.1:" + sensorPort + "/sensor";
      Map<String, Object> sensorSpecification = Map.of(
          "url", sensorUrl,
          "matchExpr", "$.status==READY");
      Map<String, Object> waitNodeParams = Map.of(
          "sensor_type", "HTTP_POLL",
          "sensor_spec", sensorSpecification,
          "timeout_seconds", 300,
          "poll_interval_seconds", 1,
          "on_timeout", "FAIL");
      String serializedWaitNodeParams = objectMapper.writeValueAsString(waitNodeParams);
      configureManualSkipWaitDag(seed, serializedWaitNodeParams);
      long workflowDefinitionId = seed.workflowDefinitionId();

      Map<String, Object> failedImportParams = Map.of(
          "fileFormatType", "JSON",
          "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
          "bizType", "CUSTOMER",
          "content", "this is not valid JSON");
      String requestId = "manual-skip-dag-" + suffix;
      LaunchRequest launchRequest = new LaunchRequest(
          TENANT,
          workflowDefinitionId,
          BIZ_DATE,
          TriggerType.API,
          seed.requestId(),
          requestId,
          failedImportParams);
      LaunchResponse launchResponse = launchService.launch(launchRequest);
      long workflowRunId = Long.parseLong(launchResponse.instanceNo());
      outboxPublishSupport.publishAllPending(TENANT);

      ThrowingRunnable jobFailedAndWaitPending = () -> {
        String jobStatus = jdbc.queryForObject(
            SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "JOB");
        String waitStatus = jdbc.queryForObject(
            SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "WAIT_RELEASE");
        String workflowStatus = jdbc.queryForObject(
            SELECT_RUN_STATUS_SQL, String.class, TENANT, workflowRunId);
        assertThat(jobStatus).isEqualTo("FAILED");
        assertThat(waitStatus).isEqualTo("RUNNING");
        assertThat(workflowStatus).isEqualTo("RUNNING");
      };
      await()
          .atMost(Duration.ofSeconds(90))
          .pollInterval(Duration.ofMillis(200))
          .untilAsserted(jobFailedAndWaitPending);

      ConfigurableApplicationContext startedConsole = startSecuredConsole();
      try (ConfigurableApplicationContext securedConsole = startedConsole) {
        Environment consoleEnvironment = securedConsole.getEnvironment();
        Integer configuredConsolePort =
            consoleEnvironment.getProperty("local.server.port", Integer.class);
        int consolePort = configuredConsolePort;
        String consoleBaseUrl = "http://127.0.0.1:" + consolePort;
        WebTestClient client = WebTestClient.bindToServer()
            .baseUrl(consoleBaseUrl)
            .responseTimeout(Duration.ofSeconds(45))
            .build();
        ConsoleJwtService jwtService = securedConsole.getBean(ConsoleJwtService.class);
        Actor administrator = authenticate(client, jwtService, "manual-skip-dag-admin", "ROLE_ADMIN");
        Actor auditor = authenticate(client, jwtService, "manual-skip-dag-auditor", "ROLE_AUDITOR");
        URI failedJobSkipUri = buildSkipNodeUri(consoleBaseUrl, workflowRunId, "JOB");
        URI runningWaitSkipUri = buildSkipNodeUri(consoleBaseUrl, workflowRunId, "WAIT_RELEASE");

        WebTestClient.ResponseSpec deniedSkipResponse =
            postSkipNode(client, auditor, failedJobSkipUri);
        deniedSkipResponse.expectStatus().isForbidden();
        WebTestClient.ResponseSpec runningWaitSkipResponse =
            postSkipNode(client, administrator, runningWaitSkipUri);
        runningWaitSkipResponse.expectStatus().isEqualTo(409);
        String waitStatusAfterRejectedSkip = jdbc.queryForObject(
            SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "WAIT_RELEASE");
        assertThat(waitStatusAfterRejectedSkip).isEqualTo("RUNNING");

        WebTestClient.ResponseSpec skipResponse =
            postSkipNode(client, administrator, failedJobSkipUri);
        skipResponse.expectStatus().isOk();
        JsonNode skipResponseBody = response(skipResponse);
        JsonNode skippedNodeData = skipResponseBody.path("data");
        String skippedNodeStatus = skippedNodeData.path("nodeStatus").asText();
        assertThat(skippedNodeStatus).isEqualTo("SKIPPED");
        String workflowStatusAfterSkip = jdbc.queryForObject(
            SELECT_RUN_STATUS_SQL, String.class, TENANT, workflowRunId);
        String waitStatusAfterSkip = jdbc.queryForObject(
            SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "WAIT_RELEASE");
        Integer endNodeCountAfterSkip = jdbc.queryForObject(
            COUNT_NODE_RUNS_SQL, Integer.class, TENANT, workflowRunId, "END");
        assertThat(workflowStatusAfterSkip).isEqualTo("RUNNING");
        assertThat(waitStatusAfterSkip).isEqualTo("RUNNING");
        assertThat(endNodeCountAfterSkip).isZero();

        sensorReady.set(true);
        ThrowingRunnable workflowJoinCompleted = () -> {
          String completedWaitStatus = jdbc.queryForObject(
              SELECT_NODE_STATUS_SQL, String.class, TENANT, workflowRunId, "WAIT_RELEASE");
          String completedWorkflowStatus = jdbc.queryForObject(
              SELECT_RUN_STATUS_SQL, String.class, TENANT, workflowRunId);
          assertThat(completedWaitStatus).isEqualTo("SUCCESS");
          assertThat(completedWorkflowStatus).isEqualTo("SUCCESS");
        };
        await()
            .atMost(Duration.ofSeconds(90))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted(workflowJoinCompleted);
      }
    } finally {
      sensorEndpoint.stop(0);
      if (previousAllowPrivate == null) {
        System.clearProperty("batch.security.ssrf-guard.allow-private");
      } else {
        System.setProperty("batch.security.ssrf-guard.allow-private", previousAllowPrivate);
      }
    }
  }

  private CompensationSourceInput prepareCompensationSourceInput(
      String suffix,
      String customerNo,
      String repairedImportContent,
      boolean sourceInitiallyFailed) {
    if (!sourceInitiallyFailed) {
      Map<String, Object> inlineLaunchParams = Map.of(
          "fileFormatType", "JSON",
          "templateCode", "IMP-CUSTOMER-JSON-ARRAY",
          "bizType", "CUSTOMER",
          "content", repairedImportContent);
      return new CompensationSourceInput(inlineLaunchParams, null, null, null);
    }

    String storageBucket = s3Bucket();
    String storagePath = "self-service-compensation/" + TENANT + "/" + suffix + ".json";
    byte[] initialInvalidContent = "this is not valid JSON".getBytes(StandardCharsets.UTF_8);
    byte[] repairedFileContent = repairedImportContent.getBytes(StandardCharsets.UTF_8);
    ensureBucket(storageBucket);
    putObject(storageBucket, storagePath, initialInvalidContent);

    String fileName = customerNo + ".json";
    String templateCode = "IMP-CUSTOMER-JSON-ARRAY";
    Map<String, Object> objectStoreLaunchParams = Map.of(
        "fileName", fileName,
        "originalFileName", fileName,
        "fileFormatType", "JSON",
        "charset", "UTF-8",
        "bizType", "CUSTOMER",
        "storageType", "S3",
        "storageBucket", storageBucket,
        "storagePath", storagePath,
        "templateCode", templateCode);
    return new CompensationSourceInput(
        objectStoreLaunchParams,
        storageBucket,
        storagePath,
        repairedFileContent);
  }

  private record CompensationSourceInput(
      Map<String, Object> launchParams,
      String storageBucket,
      String storagePath,
      byte[] repairedFileContent) {}

  private void configureManualSkipWaitDag(LaunchSeed seed, String serializedWaitNodeParams) {
    long workflowDefinitionId = seed.workflowDefinitionId();
    jdbc.update(UPDATE_WORKFLOW_DEFINITION_FOR_DAG_SQL, TENANT, workflowDefinitionId);
    jdbc.update(MOVE_END_NODE_FOR_DAG_SQL, TENANT, workflowDefinitionId);
    jdbc.update(
        INSERT_WAIT_NODE_FOR_DAG_SQL,
        TENANT,
        workflowDefinitionId,
        serializedWaitNodeParams);
    jdbc.update(DELETE_WORKFLOW_EDGES_FOR_DAG_SQL, TENANT, workflowDefinitionId);
    jdbc.update(
        INSERT_WORKFLOW_EDGES_FOR_DAG_SQL,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId,
        TENANT,
        workflowDefinitionId);
  }

  private URI buildSkipNodeUri(String consoleBaseUrl, long workflowRunId, String nodeCode) {
    UriComponentsBuilder skipNodeUriBuilder = UriComponentsBuilder.fromUriString(consoleBaseUrl);
    skipNodeUriBuilder
        .path("/api/console/workflow-runs/{id}/skip-node")
        .queryParam("tenantId", TENANT)
        .queryParam("nodeCode", nodeCode)
        .queryParam("reason", "skip failed DAG node after operator review");
    UriComponents expandedSkipNodeUri = skipNodeUriBuilder.buildAndExpand(workflowRunId);
    UriComponents encodedSkipNodeUri = expandedSkipNodeUri.encode();
    return encodedSkipNodeUri.toUri();
  }

  private ConfigurableApplicationContext startSecuredConsole() {
    Map<String, Object> consoleProperties = new HashMap<>();
    BiConsumer<String, Supplier<?>> propertyRegistrar = (name, supplier) -> {
      Object propertyValue = supplier.get();
      consoleProperties.put(name, propertyValue);
    };
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
        "batch.security.internal-secret", "self-service-e2e-internal-secret-32chars");
    String orchestratorBaseUrl = "http://127.0.0.1:" + orchestratorPort;
    consoleProperties.put("batch.console.orchestrator.base-url", orchestratorBaseUrl);
    consoleProperties.put("batch.console.trigger.base-url", orchestratorBaseUrl);
    String[] consoleArguments = new String[consoleProperties.size()];
    int argumentIndex = 0;
    for (Map.Entry<String, Object> property : consoleProperties.entrySet()) {
      String consoleArgument = "--" + property.getKey() + "=" + property.getValue();
      consoleArguments[argumentIndex] = consoleArgument;
      argumentIndex++;
    }

    SpringApplicationBuilder consoleApplication =
        new SpringApplicationBuilder(BatchConsoleApiApplication.class);
    consoleApplication.profiles("test", "e2e");
    return consoleApplication.run(consoleArguments);
  }

  private WebTestClient.ResponseSpec postSkipNode(
      WebTestClient client, Actor actor, URI skipNodeUri) {
    WebTestClient.RequestHeadersSpec<?> skipRequest = client
        .post()
        .uri(skipNodeUri)
        .cookie("batch_console_token", actor.jwt())
        .cookie("XSRF-TOKEN", actor.csrf())
        .header("X-XSRF-TOKEN", actor.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "spoofed-operator")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "manual-skip-" + UUID.randomUUID());
    return skipRequest.exchange();
  }

  private Actor authenticate(
      WebTestClient client, ConsoleJwtService jwtService, String username, String role) {
    ConsoleAuthTokenResponse issuedToken = jwtService.issueToken(username, TENANT, Set.of(role));
    String jwt = issuedToken.accessToken();
    WebTestClient.ResponseSpec authCheckResponse = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .exchange()
        .expectStatus()
        .isNoContent();
    var authCheckResult = authCheckResponse.returnResult(Void.class);
    var responseCookies = authCheckResult.getResponseCookies();
    var cookie = responseCookies.getFirst("XSRF-TOKEN");
    assertThat(cookie).isNotNull();
    return new Actor(jwt, cookie.getValue());
  }

  private WebTestClient.ResponseSpec post(
      WebTestClient client, Actor actor, String path, Map<String, Object> body) {
    String idempotencyKey = "self-service-" + UUID.randomUUID();
    return client
        .post()
        .uri(path)
        .cookie("batch_console_token", actor.jwt())
        .cookie("XSRF-TOKEN", actor.csrf())
        .header("X-XSRF-TOKEN", actor.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, TENANT)
        .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "spoofed-operator")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private JsonNode response(WebTestClient.ResponseSpec response) throws Exception {
    WebTestClient.BodyContentSpec responseBody = response.expectBody();
    byte[] body = responseBody.returnResult().getResponseBody();
    assertThat(body).isNotNull();
    return objectMapper.readTree(body);
  }

  private record Actor(String jwt, String csrf) {}
}
