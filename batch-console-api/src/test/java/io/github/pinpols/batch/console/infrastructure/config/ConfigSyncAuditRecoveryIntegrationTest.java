package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.BatchWindowSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.ResourceQueueSpec;
import io.github.pinpols.batch.console.domain.job.infrastructure.BatchDayCatchUpOperationStore;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "batch.console.security.single-session-enabled=false",
      "batch.console.ai.enabled=false",
      "batch.console.security.rate-limit.expensive-op-user-limit-per-minute=100"
    })
@Import(ConfigSyncAuditRecoveryIntegrationTest.SecurityMode.class)
@DisplayName("配置同步收据恢复: PostgreSQL 提交边界、认证恢复、重复请求与在途对账")
class ConfigSyncAuditRecoveryIntegrationTest extends AbstractIntegrationTest {

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMode {
    @Bean
    static BeanPostProcessor requireAuthentication() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          if (bean instanceof BatchSecurityProperties security) {
            security.setBypassMode(false);
          }
          return bean;
        }
      };
    }
  }

  @LocalServerPort
  private int port;

  private final JdbcTemplate jdbc;
  private final ConsoleJwtService jwtService;
  private final ObjectMapper objectMapper;

  @Autowired
  ConfigSyncAuditRecoveryIntegrationTest(
      JdbcTemplate jdbc, ConsoleJwtService jwtService, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.jwtService = jwtService;
    this.objectMapper = objectMapper;
  }

  @MockitoSpyBean
  private BatchDayCatchUpOperationStore batchDayOperations;

  @MockitoSpyBean
  private ConfigSyncImportExecutor importExecutor;

  @MockitoSpyBean
  private TenantConfigInitApplyHandlers initApplyHandlers;

  @MockitoSpyBean
  private OrchestratorApprovalClient approvalClient;

  private final List<Fixture> ownedFixtures = new ArrayList<>();

  @AfterEach
  void removeOwnedFixtures() {
    String deleteBatchReceiptsSql =
        "delete from batch.config_batch_target_receipt where audit_tenant_id = ?";
    String deleteBatchOperationsSql =
        "delete from batch.config_batch_operation where audit_tenant_id = ?";
    String deleteReceiptsSql =
        "delete from batch.config_sync_target_receipt where audit_tenant_id = ?";
    String deleteAuditSql = "delete from batch.config_sync_log where tenant_id = ?";
    String deleteQueuesSql = "delete from batch.resource_queue where tenant_id = ?";
    String deleteChannelsSql = """
        delete from batch.file_channel_config where tenant_id = ?
        """;
    String deleteEdgesSql = """
        delete from batch.workflow_edge where tenant_id = ?
        """;
    String deleteNodesSql = """
        delete from batch.workflow_node where tenant_id = ?
        """;
    String deleteVersionsSql = """
        delete from batch.workflow_definition_version where tenant_id = ?
        """;
    String deleteWorkflowsSql = """
        delete from batch.workflow_definition where tenant_id = ?
        """;
    String deleteRevisionSql =
        "delete from batch.tenant_configuration_revision where tenant_id = ?";
    String deleteIdempotencySql = "delete from batch.idempotency_record where tenant_id = ?";
    String deleteTenantSql = "delete from batch.tenant where tenant_id = ?";
    String deleteOperationAuditSql =
        "delete from batch.console_operation_audit where tenant_id = ? and action = ?";
    for (Fixture fixture : ownedFixtures) {
      jdbc.update(deleteBatchReceiptsSql, fixture.auditTenant());
      jdbc.update(deleteBatchOperationsSql, fixture.auditTenant());
      jdbc.update(deleteOperationAuditSql, fixture.auditTenant(), "batch_day.catch_up_reconcile");
      jdbc.update(deleteOperationAuditSql, fixture.auditTenant(), "batch_day.catch_up_continue");
      jdbc.update(deleteReceiptsSql, fixture.auditTenant());
      jdbc.update(deleteAuditSql, fixture.auditTenant());
      for (String tenant : List.of(
          fixture.auditTenant(), fixture.targets().getFirst(), fixture.targets().getLast())) {
        jdbc.update(deleteQueuesSql, tenant);
        jdbc.update(deleteChannelsSql, tenant);
        jdbc.update(deleteEdgesSql, tenant);
        jdbc.update(deleteNodesSql, tenant);
        jdbc.update(deleteVersionsSql, tenant);
        jdbc.update(deleteWorkflowsSql, tenant);
        jdbc.update(deleteRevisionSql, tenant);
        jdbc.update(deleteIdempotencySql, tenant);
        jdbc.update(deleteTenantSql, tenant);
      }
    }
    ownedFixtures.clear();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  @DisplayName("无效目标在认证入口返回参数错误且不留下同步审计")
  void shouldRejectMissingTargetsBeforeCreatingAudit(String invalidTarget) {
    Fixture fixture = fixture();
    ConfigSyncImportRequest request = fixture.request();
    request.setTargetTenantIds(Collections.singletonList(invalidTarget));
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");

    FluxExchangeResult<String> response = post(
            client,
            admin,
            "/api/console/config/sync/import",
            "invalid-target-" + fixture.auditTenant(),
            request)
        .returnResult(String.class);
    String auditCountSql = """
        select count(*) from batch.config_sync_log where tenant_id = ?
        """;
    Integer auditCount = jdbc.queryForObject(auditCountSql, Integer.class, fixture.auditTenant());
    int httpStatus = response.getStatus().value();
    List<Integer> actualBoundary = List.of(httpStatus, auditCount);
    assertThat(actualBoundary).as("参数拒绝的 HTTP 状态及审计行数").containsExactly(400, 0);
  }

  @Test
  @DisplayName("认证配置同步演练逐租户报告非法作业编码且不写入作业")
  void shouldReportInvalidJobCodeInAuthenticatedSyncDryRun() throws Exception {
    Fixture fixture = fixture();
    JobDefinitionSpec invalid = new JobDefinitionSpec();
    invalid.setJobCode("A:B");
    invalid.setJobName("invalid job");
    invalid.setJobType("IMPORT");
    invalid.setScheduleType("MANUAL");
    JobDefinitionSpec valid = new JobDefinitionSpec();
    valid.setJobCode("A_B");
    valid.setJobName("valid job");
    valid.setJobType("IMPORT");
    valid.setScheduleType("MANUAL");
    fixture.request().getBundle().setJobDefinitions(List.of(invalid, valid));
    fixture.request().getBundle().setResourceQueues(List.of());
    fixture.request().setDryRun(true);
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");

    String body = post(
            client,
            admin,
            "/api/console/config/sync/import",
            "job-code-" + fixture.auditTenant(),
            fixture.request())
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode results = objectMapper.readTree(body).path("data").path("result").path("results");
    assertThat(results).hasSize(2);
    for (JsonNode result : results) {
      assertThat(result.path("jobDefinitions").path("failed").asInt()).isEqualTo(1);
      assertThat(result.path("jobDefinitions").path("created").asInt()).isEqualTo(1);
    }
    String countSql =
        "select count(*) from batch.job_definition where tenant_id = ? and job_code in ('A:B', 'A_B')";
    for (String target : fixture.targets()) {
      assertThat(jdbc.queryForObject(countSql, Integer.class, target)).isZero();
    }
  }

  @Test
  @DisplayName("原请求已进入校验但尚未冻结时，围栏阻止后续提交")
  void shouldFenceAuthenticatedRequestPausedBeforePreparation() throws Exception {
    Fixture fixture = fixture();
    String tenant = fixture.auditTenant();
    String calendar = "FENCE_CAL";
    String insertCalendarSql = """
        insert into batch.business_calendar
          (tenant_id, calendar_code, calendar_name, timezone, cutoff_time, catch_up_policy, enabled)
        values (?, ?, 'calendar', 'UTC', '00:00:00', 'MANUAL_APPROVAL', true)
        """;
    String deleteCalendarSql =
        "delete from batch.business_calendar where tenant_id = ? and calendar_code = ?";
    String deleteWorkflowSql =
        "delete from batch.workflow_definition where tenant_id = ? and workflow_code = 'FENCE_WORKFLOW'";
    String deleteJobSql =
        "delete from batch.job_definition where tenant_id = ? and job_code = 'FENCE_JOB'";
    String deleteOperationSql = """
        delete from batch.batch_day_catchup_operation where tenant_id = ? and request_key_hash = ?
        """;
    String deleteFenceAuditSql = """
        delete from batch.console_operation_audit
        where tenant_id = ? and action = 'batch_day.catch_up_abandon_unprepared'
        """;
    jdbc.update(insertCalendarSql, tenant, calendar);
    String insertJobSql = """
        insert into batch.job_definition
          (tenant_id, job_code, job_name, job_type, schedule_type, timezone, calendar_code)
        values (?, 'FENCE_JOB', 'fence test', 'GENERAL', 'MANUAL', 'UTC', ?) returning id
        """;
    Long jobId = jdbc.queryForObject(insertJobSql, Long.class, tenant, calendar);
    String insertWorkflowSql = """
        insert into batch.workflow_definition
          (tenant_id, workflow_code, workflow_name, workflow_type, single_job_definition_id, enabled)
        values (?, 'FENCE_WORKFLOW', 'fence test', 'DAG', ?, true)
        """;
    jdbc.update(insertWorkflowSql, tenant, jobId);
    WebTestClient client = client();
    Auth admin = authenticate(client, tenant, "ROLE_ADMIN");
    String key = "inflight-fence-" + UUID.randomUUID();
    CountDownLatch preparing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    doAnswer(invocation -> {
          BatchDayCatchUpOperationStore.Prepare input = invocation.getArgument(0);
          if (key.equals(input.requestKey())) {
            preparing.countDown();
            if (!release.await(30, TimeUnit.SECONDS))
              throw new IllegalStateException("test timeout");
          }
          return invocation.callRealMethod();
        })
        .when(batchDayOperations)
        .prepare(any());
    try {
      var original = executor.submit(() -> {
        post(
                client,
                admin,
                "/api/console/jobs/batch-days/2026-10-14/catchup",
                key,
                Map.of(
                    "tenantId", tenant, "calendarCode", calendar, "jobCodes", List.of("FENCE_JOB")))
            .expectStatus()
            .isEqualTo(409);
        return true;
      });
      assertThat(preparing.await(20, TimeUnit.SECONDS)).isTrue();
      client
          .post()
          .uri("/api/console/jobs/batch-days/catchup/requests/abandon")
          .cookie("batch_console_token", admin.jwt())
          .cookie("XSRF-TOKEN", admin.csrf())
          .header("X-XSRF-TOKEN", admin.csrf())
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, tenant)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "fence-" + UUID.randomUUID())
          .header("X-Original-Idempotency-Key", key)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(Map.of("tenantId", tenant, "bizDate", "2026-10-14", "calendarCode", calendar))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.operationStatus")
          .isEqualTo("ABANDONED");
      release.countDown();
      assertThat(original.get(30, TimeUnit.SECONDS)).isTrue();
      batchDayStatus(client, admin, tenant, key)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.operationStatus")
          .isEqualTo("ABANDONED");
      String countApprovalsSql = "select count(*) from batch.approval_command where tenant_id = ?";
      assertThat(jdbc.queryForObject(countApprovalsSql, Integer.class, tenant)).isZero();
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
      var operation = batchDayOperations.findByRequestKey(tenant, key);
      if (operation != null) jdbc.update(deleteOperationSql, tenant, operation.requestKeyHash());
      jdbc.update(deleteFenceAuditSql, tenant);
      jdbc.update(deleteWorkflowSql, tenant);
      jdbc.update(deleteJobSql, tenant);
      jdbc.update(deleteCalendarSql, tenant, calendar);
    }
  }

  @Test
  @DisplayName("管理员围栏未建档批次后原键不能再提交")
  void shouldFenceRejectedBatchDayRequestAtAuthenticatedHttpBoundary() {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "abandon-batch-day-" + UUID.randomUUID();
    post(
            client,
            admin,
            "/api/console/jobs/batch-days/2026-10-14/catchup",
            key,
            Map.of("tenantId", fixture.auditTenant(), "calendarCode", "CAL"))
        .expectStatus()
        .isNotFound();
    Auth reader = authenticate(client, fixture.auditTenant(), "ROLE_TENANT_ADMIN");
    client
        .post()
        .uri("/api/console/jobs/batch-days/catchup/requests/abandon")
        .cookie("batch_console_token", reader.jwt())
        .cookie("XSRF-TOKEN", reader.csrf())
        .header("X-XSRF-TOKEN", reader.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, fixture.auditTenant())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "fence-reader-" + UUID.randomUUID())
        .header("X-Original-Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "tenantId", fixture.auditTenant(), "bizDate", "2026-10-14", "calendarCode", "CAL"))
        .exchange()
        .expectStatus()
        .isForbidden();
    batchDayStatus(client, admin, fixture.auditTenant(), key)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.found")
        .isEqualTo(false);
    client
        .post()
        .uri("/api/console/jobs/batch-days/catchup/requests/abandon")
        .cookie("batch_console_token", admin.jwt())
        .cookie("XSRF-TOKEN", admin.csrf())
        .header("X-XSRF-TOKEN", admin.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, fixture.auditTenant())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "fence-" + UUID.randomUUID())
        .header("X-Original-Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "tenantId", fixture.auditTenant(), "bizDate", "2026-10-14", "calendarCode", "CAL"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.operationStatus")
        .isEqualTo("ABANDONED");
    batchDayStatus(client, admin, fixture.auditTenant(), key)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.found")
        .isEqualTo(true)
        .jsonPath("$.data.operationStatus")
        .isEqualTo("ABANDONED");
    post(
            client,
            admin,
            "/api/console/jobs/batch-days/2026-10-14/catchup",
            key,
            Map.of("tenantId", fixture.auditTenant(), "calendarCode", "CAL"))
        .expectStatus()
        .isEqualTo(409);
  }

  @Test
  @DisplayName("批日补跑在日历校验拒绝后原请求状态仍显示未找到")
  void shouldReportNotFoundAfterRejectedBatchDayBeforeOperationIsPrepared() {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "rejected-batch-day-" + UUID.randomUUID();
    String uri = "/api/console/jobs/batch-days/2026-10-14/catchup";
    Map<String, Object> request =
        Map.of("tenantId", fixture.auditTenant(), "calendarCode", "missing-calendar");

    post(client, admin, uri, key, request).expectStatus().is4xxClientError();
    batchDayStatus(client, admin, fixture.auditTenant(), key)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.found")
        .isEqualTo(false);
  }

  @Test
  @DisplayName("认证批日补跑成功后同键重试被拦截且不能返回原批次结果")
  void shouldRejectCompletedBatchDayCatchUpAtAuthenticatedHttpBoundary() {
    Fixture fixture = fixture();
    String insertCalendarSql = """
        insert into batch.business_calendar (tenant_id, calendar_code, calendar_name, timezone,
            cutoff_time, catch_up_policy, enabled)
        values (?, 'CAL', 'calendar', 'UTC', '00:00:00', 'MANUAL_APPROVAL', true)
        """;
    String deleteCalendarSql =
        "delete from batch.business_calendar where tenant_id = ? and calendar_code = 'CAL'";
    jdbc.update(insertCalendarSql, fixture.auditTenant());
    try {
      WebTestClient client = client();
      Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
      String key = "batch-day-http-" + fixture.auditTenant();
      String uri = "/api/console/jobs/batch-days/2026-10-14/catchup";
      Map<String, Object> request =
          Map.of("tenantId", fixture.auditTenant(), "calendarCode", "CAL");
      batchDayStatus(client, admin, fixture.auditTenant(), key)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.found")
          .isEqualTo(false);
      post(client, admin, uri, key, request)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.items")
          .isArray()
          .jsonPath("$.data.items.length()")
          .isEqualTo(0);
      batchDayStatus(client, admin, fixture.auditTenant(), key)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.found")
          .isEqualTo(true)
          .jsonPath("$.data.operationStatus")
          .isEqualTo("COMPLETE")
          .jsonPath("$.data.bizDate")
          .isEqualTo("2026-10-14")
          .jsonPath("$.data.calendarCode")
          .isEqualTo("CAL")
          .jsonPath("$.data.items.length()")
          .isEqualTo(0);
      batchDayStatus(client, admin, fixture.targets().getFirst(), key)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.found")
          .isEqualTo(false);
      Auth reader = authenticate(client, fixture.auditTenant(), "ROLE_TENANT_ADMIN");
      batchDayStatus(client, reader, fixture.auditTenant(), key).expectStatus().isForbidden();
      post(client, admin, uri, key, request)
          .expectStatus()
          .isEqualTo(409)
          .expectBody(String.class)
          .value(body -> assertThat(body).contains("duplicate request"));
    } finally {
      jdbc.update(deleteCalendarSql, fixture.auditTenant());
    }
  }

  @Test
  @DisplayName("原请求状态查询只读展示已确认和未知项目，不暴露来源键")
  void shouldReadPartialBatchDayStatusWithoutReexecution() {
    Fixture fixture = fixture();
    String tenant = fixture.auditTenant();
    String requestKey = "batch-partial-" + UUID.randomUUID();
    String snapshot = """
        {"bizDate":"2026-10-14","calendarCode":"CAL","catchUpPolicy":"MANUAL_APPROVAL",
         "targets":[{"jobCode":"JOB_A","sourceKey":"secret-key-a"},
                    {"jobCode":"JOB_B","sourceKey":"secret-key-b"}]}
        """;
    String items = """
        [{"jobCode":"JOB_A","sourceKey":"secret-key-a","status":"PENDING","referenceNo":null},
         {"jobCode":"JOB_B","sourceKey":"secret-key-b","status":"PENDING","referenceNo":null}]
        """;
    var prepared = batchDayOperations.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, requestKey, "{}", snapshot, items));
    batchDayOperations.startItem(tenant, prepared.requestKeyHash(), 0, "secret-key-a");
    batchDayOperations.acceptItem(tenant, prepared.requestKeyHash(), 1, "secret-key-a", "apr-a");
    batchDayOperations.startItem(tenant, prepared.requestKeyHash(), 2, "secret-key-b");
    batchDayOperations.markItemUnknown(tenant, prepared.requestKeyHash(), 3, "secret-key-b");
    WebTestClient client = client();
    Auth admin = authenticate(client, tenant, "ROLE_ADMIN");
    batchDayStatus(client, admin, tenant, requestKey)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.operationStatus")
        .isEqualTo("UNKNOWN")
        .jsonPath("$.data.items[0].jobCode")
        .isEqualTo("JOB_A")
        .jsonPath("$.data.items[0].status")
        .isEqualTo("ACCEPTED")
        .jsonPath("$.data.items[0].referenceNo")
        .isEqualTo("apr-a")
        .jsonPath("$.data.items[1].jobCode")
        .isEqualTo("JOB_B")
        .jsonPath("$.data.items[1].status")
        .isEqualTo("UNKNOWN")
        .jsonPath("$.data.items[1].sourceKey")
        .doesNotExist();
    String selectRevisionSql =
        "select revision from batch.batch_day_catchup_operation where tenant_id = ?";
    assertThat(jdbc.queryForObject(selectRevisionSql, Long.class, tenant)).isEqualTo(4);
  }

  @Test
  @DisplayName("管理员按原请求对账未知审批，其他角色及错误租户不能确认")
  void shouldReconcileUnknownApprovalAtAuthenticatedBoundary() throws Exception {
    Fixture fixture = fixture();
    String tenant = fixture.auditTenant();
    String requestKey = "batch-reconcile-" + UUID.randomUUID();
    String sourceKey = "source-" + UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(Map.of(
        "tenantId",
        tenant,
        "jobCode",
        "JOB_A",
        "bizDate",
        "2026-10-14",
        "batchDayCalendarCode",
        "CAL"));
    var command = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("original-operator")
        .idempotencyKey(sourceKey)
        .build();
    String snapshot = objectMapper.writeValueAsString(Map.of(
        "bizDate",
        "2026-10-14",
        "calendarCode",
        "CAL",
        "catchUpPolicy",
        "MANUAL_APPROVAL",
        "targets",
        List.of(Map.of("jobCode", "JOB_A", "sourceKey", sourceKey, "approval", command))));
    String items = objectMapper.writeValueAsString(
        List.of(Map.of("jobCode", "JOB_A", "sourceKey", sourceKey, "status", "PENDING")));
    var prepared = batchDayOperations.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, requestKey, "{}", snapshot, items));
    batchDayOperations.startItem(tenant, prepared.requestKeyHash(), 0, sourceKey);
    batchDayOperations.markItemUnknown(tenant, prepared.requestKeyHash(), 1, sourceKey);
    Mockito.doReturn(OrchestratorApprovalClient.ApprovalSourceRecord.builder()
            .tenantId(tenant)
            .sourceIdempotencyKey(sourceKey)
            .approvalNo("apr-original")
            .build())
        .when(approvalClient)
        .lookupVerifiedSubmission(any());
    WebTestClient client = client();
    Auth reader = authenticate(client, tenant, "ROLE_TENANT_ADMIN");
    batchDayReconcile(client, reader, tenant, requestKey, "JOB_A")
        .expectStatus()
        .isForbidden();
    Auth admin = authenticate(client, tenant, "ROLE_ADMIN");
    batchDayReconcile(client, admin, fixture.targets().getFirst(), requestKey, "JOB_A")
        .expectStatus()
        .isEqualTo(409);
    batchDayReconcile(client, admin, tenant, requestKey, "JOB_A")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.operationStatus")
        .isEqualTo("COMPLETE")
        .jsonPath("$.data.items[0].status")
        .isEqualTo("ACCEPTED")
        .jsonPath("$.data.items[0].referenceNo")
        .isEqualTo("apr-original")
        .jsonPath("$.data.items[0].sourceKey")
        .doesNotExist();
    Mockito.verify(approvalClient, Mockito.times(1)).lookupVerifiedSubmission(any());
    String auditSql = """
        select count(*) from batch.console_operation_audit
        where tenant_id = ? and action = ? and aggregate_id = ? and result = 'SUCCESS' and params is null
        """;
    assertThat(jdbc.queryForObject(
            auditSql, Integer.class, tenant, "batch_day.catch_up_reconcile", "JOB_A"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("认证入口可核验在途审批且不重新提交")
  void shouldReconcileInFlightApprovalAtAuthenticatedBoundary() throws Exception {
    Fixture fixture = fixture();
    String tenant = fixture.auditTenant();
    String requestKey = "batch-inflight-" + UUID.randomUUID();
    String sourceKey = "source-" + UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(Map.of(
        "tenantId",
        tenant,
        "jobCode",
        "JOB_A",
        "bizDate",
        "2026-10-14",
        "batchDayCalendarCode",
        "CAL"));
    var command = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("original-operator")
        .idempotencyKey(sourceKey)
        .build();
    String snapshot = objectMapper.writeValueAsString(Map.of(
        "bizDate",
        "2026-10-14",
        "calendarCode",
        "CAL",
        "catchUpPolicy",
        "MANUAL_APPROVAL",
        "targets",
        List.of(Map.of("jobCode", "JOB_A", "sourceKey", sourceKey, "approval", command))));
    String items = objectMapper.writeValueAsString(
        List.of(Map.of("jobCode", "JOB_A", "sourceKey", sourceKey, "status", "PENDING")));
    var prepared = batchDayOperations.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, requestKey, "{}", snapshot, items));
    batchDayOperations.startItem(tenant, prepared.requestKeyHash(), 0, sourceKey);
    Mockito.doReturn(OrchestratorApprovalClient.ApprovalSourceRecord.builder()
            .tenantId(tenant)
            .sourceIdempotencyKey(sourceKey)
            .approvalNo("apr-original")
            .build())
        .when(approvalClient)
        .lookupVerifiedSubmission(any());
    WebTestClient client = client();
    Auth reader = authenticate(client, tenant, "ROLE_TENANT_ADMIN");
    batchDayReconcile(client, reader, tenant, requestKey, "JOB_A")
        .expectStatus()
        .isForbidden();
    Auth admin = authenticate(client, tenant, "ROLE_ADMIN");
    batchDayReconcile(client, admin, tenant, requestKey, "JOB_A")
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.operationStatus")
        .isEqualTo("COMPLETE")
        .jsonPath("$.data.items[0].status")
        .isEqualTo("ACCEPTED")
        .jsonPath("$.data.items[0].referenceNo")
        .isEqualTo("apr-original");
    Mockito.verify(approvalClient, Mockito.never()).submitApproval(any());
    String auditSql = """
        select count(*) from batch.console_operation_audit
        where tenant_id = ? and action = ? and aggregate_id = ? and result = 'SUCCESS' and params is null
        """;
    assertThat(jdbc.queryForObject(
            auditSql, Integer.class, tenant, "batch_day.catch_up_reconcile", "JOB_A"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("管理员按原请求接续待处理项目并记录审计")
  void shouldContinuePendingBatchAtAuthenticatedBoundary() throws Exception {
    Fixture fixture = fixture();
    String tenant = fixture.auditTenant();
    String requestKey = "batch-continue-" + UUID.randomUUID();
    String sourceKey = "source-" + UUID.randomUUID();
    String payload = objectMapper.writeValueAsString(Map.of(
        "tenantId",
        tenant,
        "jobCode",
        "JOB_A",
        "bizDate",
        "2026-10-14",
        "batchDayCalendarCode",
        "CAL",
        "reason",
        "reason"));
    var command = ApprovalSubmitCommand.builder()
        .tenantId(tenant)
        .approvalType("CATCH_UP")
        .actionType("CATCH_UP")
        .targetType("CATCH_UP")
        .payloadJson(payload)
        .requesterId("original-operator")
        .approvalReason("reason")
        .idempotencyKey(sourceKey)
        .build();
    String snapshot = objectMapper.writeValueAsString(Map.of(
        "bizDate",
        "2026-10-14",
        "calendarCode",
        "CAL",
        "catchUpPolicy",
        "MANUAL_APPROVAL",
        "targets",
        List.of(Map.of(
            "jobCode",
            "JOB_A",
            "sourceKey",
            sourceKey,
            "workflowDefinitionId",
            1,
            "approval",
            command))));
    String items = objectMapper.writeValueAsString(
        List.of(Map.of("jobCode", "JOB_A", "sourceKey", sourceKey, "status", "PENDING")));
    String identity = objectMapper.writeValueAsString(Map.of(
        "bizDate",
        "2026-10-14",
        "calendarCode",
        "CAL",
        "jobCodes",
        List.of("JOB_A"),
        "reason",
        "reason"));
    batchDayOperations.prepare(
        new BatchDayCatchUpOperationStore.Prepare(tenant, requestKey, identity, snapshot, items));
    Mockito.doReturn("apr-original").when(approvalClient).submitApproval(any());
    WebTestClient client = client();
    Auth reader = authenticate(client, tenant, "ROLE_TENANT_ADMIN");
    batchDayContinue(client, reader, tenant, requestKey).expectStatus().isForbidden();
    Auth admin = authenticate(client, tenant, "ROLE_ADMIN");
    batchDayContinue(client, admin, fixture.targets().getFirst(), requestKey)
        .expectStatus()
        .isEqualTo(409);
    batchDayContinue(client, admin, tenant, requestKey)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.operationStatus")
        .isEqualTo("COMPLETE")
        .jsonPath("$.data.items[0].status")
        .isEqualTo("ACCEPTED")
        .jsonPath("$.data.items[0].referenceNo")
        .isEqualTo("apr-original")
        .jsonPath("$.data.items[0].sourceKey")
        .doesNotExist();
    Mockito.verify(approvalClient, Mockito.times(1)).submitApproval(command);
    String auditSql = """
        select count(*) from batch.console_operation_audit
        where tenant_id = ? and action = ? and result = 'SUCCESS' and params is null
        """;
    assertThat(jdbc.queryForObject(auditSql, Integer.class, tenant, "batch_day.catch_up_continue"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("持续审计故障修复后经认证接口补录原结果, 同键重提不执行目标且非管理员不能恢复")
  void shouldReconcileCommittedTargets_whenAuditStorageRecovers() throws Exception {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "import-" + fixture.auditTenant();
    String fault = installFault(fixture, false);
    Long logId;
    try {
      post(client, admin, "/api/console/config/sync/import", key, fixture.request())
          .expectStatus()
          .is5xxServerError();
      logId = logId(fixture);
      assertPersisted(fixture, 2, 2, "RUNNING");
      requestStatus(client, admin, fixture.auditTenant(), key)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.found")
          .isEqualTo(true)
          .jsonPath("$.data.audit.id")
          .isEqualTo(logId.intValue())
          .jsonPath("$.data.audit.syncStatus")
          .isEqualTo("RUNNING");
      requestStatus(client, admin, fixture.targets().getFirst(), key)
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.data.found")
          .isEqualTo(false);

      post(client, admin, "/api/console/config/sync/import", key, fixture.request())
          .expectStatus()
          .isEqualTo(409);
      assertPersisted(fixture, 2, 2, "RUNNING");
      String uri = reconcileUri(fixture.auditTenant(), logId);
      post(client, admin, uri, "still-fault-" + key, Map.of()).expectStatus().is5xxServerError();
      assertPersisted(fixture, 2, 2, "RUNNING");
    } finally {
      removeFault(fault, false);
    }

    String uri = reconcileUri(fixture.auditTenant(), logId);
    Auth reader = authenticate(client, fixture.auditTenant(), "ROLE_TENANT_ADMIN");
    requestStatus(client, reader, fixture.auditTenant(), key).expectStatus().isForbidden();
    post(client, reader, uri, "denied-" + key, Map.of()).expectStatus().isForbidden();
    String wrongTenantUri = reconcileUri(fixture.targets().getFirst(), logId);
    post(client, admin, wrongTenantUri, "wrong-tenant-" + key, Map.of())
        .expectStatus()
        .isNotFound();
    String body = post(client, admin, uri, "recover-" + key, Map.of())
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
    JsonNode data = objectMapper.readTree(body).path("data");
    assertThat(data.path("syncStatus").asText()).isEqualTo("SUCCESS");
    assertThat(data.path("successItems").asInt()).isEqualTo(2);
    assertThat(data.path("skippedItems").asInt()).isZero();
    assertPersisted(fixture, 2, 2, "SUCCESS");
    requestStatus(client, admin, fixture.auditTenant(), key)
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.audit.syncStatus")
        .isEqualTo("SUCCESS");
    String auditSql = """
        select detail_json->>'reconciledBy' from batch.config_sync_log where id = ?
        """;
    String reconciledBy = jdbc.queryForObject(auditSql, String.class, logId);
    assertThat(reconciledBy).isEqualTo(admin.username());
    post(client, admin, uri, "repeat-recover-" + key, Map.of()).expectStatus().isOk();
    assertPersisted(fixture, 2, 2, "SUCCESS");
  }

  @Test
  @DisplayName("目标已提交但协调调用中断后并发对账返回同一终态, 不重复执行目标")
  void shouldReconcileConcurrently_whenCoordinatorStopsAfterCommit() throws Exception {
    Fixture fixture = fixture();
    doAnswer(invocation -> {
          invocation.callRealMethod();
          throw new SimulatedCoordinatorStop();
        })
        .when(importExecutor)
        .execute(any(), any());
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    post(
            client,
            admin,
            "/api/console/config/sync/import",
            "stop-" + fixture.auditTenant(),
            fixture.request())
        .expectStatus()
        .is5xxServerError();
    assertPersisted(fixture, 2, 2, "RUNNING");
    Long logId = logId(fixture);
    String uri = reconcileUri(fixture.auditTenant(), logId);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> reconcileAfterRelease(
          client, admin, uri, "recover-first-" + fixture.auditTenant(), ready, release));
      var second = executor.submit(() -> reconcileAfterRelease(
          client, admin, uri, "recover-second-" + fixture.auditTenant(), ready, release));
      try {
        boolean bothReady = ready.await(20, TimeUnit.SECONDS);
        assertThat(bothReady).isTrue();
      } finally {
        release.countDown();
      }
      String firstBody = first.get(30, TimeUnit.SECONDS);
      String secondBody = second.get(30, TimeUnit.SECONDS);
      JsonNode firstData = objectMapper.readTree(firstBody).path("data");
      JsonNode secondData = objectMapper.readTree(secondBody).path("data");
      assertThat(firstData).isEqualTo(secondData);
      assertThat(firstData.path("syncStatus").asText()).isEqualTo("SUCCESS");
      assertThat(firstData.path("successItems").asInt()).isEqualTo(2);
      assertThat(firstData.path("skippedItems").asInt()).isZero();
    }
    assertPersisted(fixture, 2, 2, "SUCCESS");
  }

  @Test
  @DisplayName("导入仍执行时对账保持未确认, 放行后原请求正常完成且没有重复配置")
  void shouldKeepUnknown_whenOriginalImportIsStillActive() throws Exception {
    Fixture fixture = fixture();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(invocation -> {
          entered.countDown();
          if (!release.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test release barrier timed out");
          }
          return invocation.callRealMethod();
        })
        .when(importExecutor)
        .execute(any(), any());
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "active-" + fixture.auditTenant();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var pending = executor.submit(
          () -> post(client, admin, "/api/console/config/sync/import", key, fixture.request())
              .expectStatus()
              .isOk());
      try {
        boolean started = entered.await(20, TimeUnit.SECONDS);
        assertThat(started).isTrue();
        Long logId = logId(fixture);
        String uri = reconcileUri(fixture.auditTenant(), logId);
        post(client, admin, uri, "inspect-" + key, Map.of())
            .expectStatus()
            .isOk()
            .expectBody()
            .jsonPath("$.data.syncStatus")
            .isEqualTo("RUNNING");
        assertPersisted(fixture, 0, 0, "RUNNING");
      } finally {
        release.countDown();
      }
      pending.get(30, TimeUnit.SECONDS);
    }
    assertPersisted(fixture, 2, 2, "SUCCESS");
  }

  @Test
  @DisplayName("目标收据写入失败时配置与收据一起回滚, 对账不伪造结果")
  void shouldRollbackTargetChanges_whenReceiptCannotCommit() {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String fault = installFault(fixture, true);
    try {
      post(
              client,
              admin,
              "/api/console/config/sync/import",
              "receipt-fail-" + fixture.auditTenant(),
              fixture.request())
          .expectStatus()
          .is5xxServerError();
      assertPersisted(fixture, 0, 0, "RUNNING");
    } finally {
      removeFault(fault, true);
    }
    Long logId = logId(fixture);
    String uri = reconcileUri(fixture.auditTenant(), logId);
    post(client, admin, uri, "receipt-check-" + fixture.auditTenant(), Map.of())
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.data.syncStatus")
        .isEqualTo("RUNNING");
    assertPersisted(fixture, 0, 0, "RUNNING");
  }

  @Test
  @DisplayName("复制源同时作为目标时拒绝请求，原渠道端点与配置保持不变")
  void shouldRejectCopyWhenSourceIsAlsoTargetWithoutChangingChannel() {
    Fixture fixture = fixture();
    String endpoint = "https://fixture.invalid/source";
    String insertChannelSql = """
        insert into batch.file_channel_config
            (tenant_id, channel_code, channel_name, channel_type, target_endpoint, config_json)
        values (?, 'source-channel', 'Source channel', 'API', ?, '{"sourceMarker":"source-only"}'::jsonb)
        """;
    jdbc.update(insertChannelSql, fixture.auditTenant(), endpoint);
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    Map<String, Object> request = Map.of(
        "sourceTenantId", fixture.auditTenant(),
        "targetTenantIds", List.of(fixture.auditTenant()),
        "configTypes", List.of("FILE_CHANNEL"),
        "mode", "UPSERT");

    FluxExchangeResult<String> response = post(
            client,
            admin,
            "/api/console/config/tenant-copy",
            "self-copy-" + fixture.auditTenant(),
            request)
        .returnResult(String.class);

    String selectChannelSql = """
        select target_endpoint, config_json->>'sourceMarker' as source_marker
        from batch.file_channel_config where tenant_id = ? and channel_code = 'source-channel'
        """;
    Map<String, Object> state = jdbc.queryForMap(selectChannelSql, fixture.auditTenant());
    state.put("http_status", response.getStatus().value());
    assertThat(state)
        .containsExactlyInAnyOrderEntriesOf(Map.of(
            "target_endpoint", endpoint, "source_marker", "source-only", "http_status", 400));
  }

  @ParameterizedTest
  @MethodSource("invalidConfigurationIdentities")
  @DisplayName("复制、预览与矩阵在认证入口拒绝缺失的租户或作业元素")
  void shouldRejectMissingConfigurationIdentityBeforeProcessing(
      String kind, String field, String invalidValue) {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    Map<String, Object> request = new LinkedHashMap<>();
    boolean matrix = "matrix".equals(kind);
    String uri = matrix
        ? "/api/console/config/tenant-config-matrix"
        : "preview".equals(kind)
            ? "/api/console/config/tenant-copy/preview"
            : "/api/console/config/tenant-copy";
    if (matrix) {
      request.put("tenantIds", List.of(fixture.auditTenant()));
      request.put("jobCodes", List.of("JOB_A"));
    } else {
      request.put("sourceTenantId", fixture.auditTenant());
      request.put("targetTenantIds", fixture.targets());
    }
    request.put(field, Collections.singletonList(invalidValue));

    post(client, admin, uri, "invalid-identity-" + fixture.auditTenant(), request)
        .expectStatus()
        .isBadRequest();
  }

  static Stream<Arguments> invalidConfigurationIdentities() {
    List<Arguments> cases = new ArrayList<>();
    for (String kind : List.of("copy", "preview", "matrix")) {
      String tenantField = "matrix".equals(kind) ? "tenantIds" : "targetTenantIds";
      for (String field : List.of(tenantField, "jobCodes")) {
        cases.add(Arguments.of(kind, field, null));
        cases.add(Arguments.of(kind, field, " "));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest
  @ValueSource(strings = {"copy", "preview", "sync-export", "sync-preview"})
  @DisplayName("配置类型中的null元素在认证入口拒绝，不返回成功或500")
  void shouldRejectNullConfigTypeBeforeProcessing(String kind) {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    Map<String, Object> request = new LinkedHashMap<>();
    request.put("sourceTenantId", fixture.auditTenant());
    request.put("configTypes", Collections.singletonList(null));
    if (kind.startsWith("sync-")) {
      request.put("sourceEnv", "dev");
      request.put("targetEnv", "prod");
      if ("sync-preview".equals(kind)) request.put("tenantId", fixture.targets().getFirst());
    } else {
      request.put("targetTenantIds", fixture.targets());
    }
    String uri =
        switch (kind) {
          case "sync-export" -> "/api/console/config/sync/export";
          case "sync-preview" -> "/api/console/config/sync/preview";
          case "preview" -> "/api/console/config/tenant-copy/preview";
          default -> "/api/console/config/tenant-copy";
        };

    post(client, admin, uri, "invalid-type-" + fixture.auditTenant(), request)
        .expectStatus()
        .isBadRequest();
  }

  @Test
  @DisplayName("已有Workflow升版后不能再次创建相同编码的独立定义")
  void shouldRejectDuplicateWorkflowCodeAfterCurrentVersionAdvanced() {
    Fixture fixture = fixture();
    String workflowCode = "ADVANCED_WORKFLOW";
    String insertWorkflowSql = """
        insert into batch.workflow_definition
            (tenant_id, workflow_code, workflow_name, workflow_type, version)
        values (?, ?, 'Existing workflow', 'DAG', 2) returning id
        """;
    Long existingId =
        jdbc.queryForObject(insertWorkflowSql, Long.class, fixture.auditTenant(), workflowCode);
    String insertExistingNodesSql = """
        insert into batch.workflow_node
            (tenant_id, workflow_definition_id, node_code, node_name, node_type)
        select ?, ?, item.code, item.code, item.kind
        from (values ('start', 'START'), ('end', 'END')) item(code, kind)
        """;
    String insertExistingEdgeSql = """
        insert into batch.workflow_edge
            (tenant_id, workflow_definition_id, from_node_code, to_node_code)
        values (?, ?, 'start', 'end')
        """;
    jdbc.update(insertExistingNodesSql, fixture.auditTenant(), existingId);
    jdbc.update(insertExistingEdgeSql, fixture.auditTenant(), existingId);
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    Map<String, Object> request = Map.of(
        "tenantId",
        fixture.auditTenant(),
        "workflowCode",
        workflowCode,
        "workflowName",
        "Duplicate workflow",
        "workflowType",
        "DAG",
        "nodes",
        List.of(
            Map.of("nodeCode", "start", "nodeName", "Start", "nodeType", "START"),
            Map.of("nodeCode", "end", "nodeName", "End", "nodeType", "END")),
        "edges",
        List.of(Map.of("fromNodeCode", "start", "toNodeCode", "end", "edgeType", "SUCCESS")));

    FluxExchangeResult<String> response = post(
            client,
            admin,
            "/api/console/workflow-definitions",
            "duplicate-workflow-" + fixture.auditTenant(),
            request)
        .returnResult(String.class);

    String countDefinitionsSql = """
        select count(*) from batch.workflow_definition where tenant_id = ? and workflow_code = ?
        """;
    Integer definitions = jdbc.queryForObject(
        countDefinitionsSql, Integer.class, fixture.auditTenant(), workflowCode);
    List<Integer> boundary = List.of(response.getStatus().value(), definitions);
    assertThat(boundary).containsExactly(409, 1);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  @DisplayName("作业包目标含非法元素时整次请求先拒绝，不先提交合法目标")
  void shouldRejectInvalidJobBundleTargetsBeforeAnyTargetWasCommitted(String invalidTarget) {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    List<String> targets = new ArrayList<>();
    targets.add(fixture.targets().getFirst());
    targets.add(invalidTarget);
    Map<String, Object> request = Map.of(
        "tenantId",
        fixture.auditTenant(),
        "targetTenantIds",
        targets,
        "bundle",
        fixture.request().getBundle());

    FluxExchangeResult<String> response = post(
            client,
            admin,
            "/api/console/jobs/bundle/import",
            "invalid-job-bundle-" + fixture.auditTenant(),
            request)
        .returnResult(String.class);

    String countWrittenTargetsSql = """
        select count(*) from batch.resource_queue where tenant_id = ? and queue_code = 'sync_receipt_queue'
        """;
    Integer committedTargets = jdbc.queryForObject(
        countWrittenTargetsSql, Integer.class, fixture.targets().getFirst());
    List<Integer> boundary = List.of(response.getStatus().value(), committedTargets);
    assertThat(boundary).containsExactly(400, 0);
  }

  @Test
  @DisplayName("严格作业包回滚已写配置后，原键回查返回已确认失败回执")
  void shouldRecoverConfirmedStrictRollbackAfterTargetWrite() throws Exception {
    Fixture fixture = fixture();
    String target = fixture.targets().getFirst();
    String queueSql = """
        select count(*) from batch.resource_queue
        where tenant_id = ? and queue_code = 'sync_receipt_queue'
        """;
    String windowSql = """
        select count(*) from batch.batch_window
        where tenant_id = ? and window_code = 'invalid_window'
        """;
    Integer initialQueues = jdbc.queryForObject(queueSql, Integer.class, target);
    assertThat(initialQueues).isZero();
    BatchWindowSpec invalidWindow = new BatchWindowSpec();
    invalidWindow.setWindowCode("invalid_window");
    invalidWindow.setWindowName("invalid window");
    invalidWindow.setTimezone("UTC");
    invalidWindow.setStartTime("02:00");
    invalidWindow.setEndTime("01:00");
    invalidWindow.setAllowCrossDay(false);
    fixture.request().getBundle().setBatchWindows(List.of(invalidWindow));
    AtomicInteger queuesBeforeRollback = new AtomicInteger(-1);
    doAnswer(invocation -> {
          Object result = invocation.callRealMethod();
          TenantConfigInitApplyHandlers.ApplyContext context = invocation.getArgument(1);
          if (target.equals(context.tenantId())) {
            Integer count = jdbc.queryForObject(queueSql, Integer.class, target);
            queuesBeforeRollback.set(count);
          }
          return result;
        })
        .when(initApplyHandlers)
        .applyResourceQueues(any(), any());
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "strict-recovery-" + UUID.randomUUID();
    Map<String, Object> request = Map.of(
        "tenantId", fixture.auditTenant(),
        "targetTenantIds", List.of(target),
        "bundle", fixture.request().getBundle());

    JsonNode original =
        responseData(post(client, admin, "/api/console/jobs/bundle/import", key, request));
    JsonNode originalResult = original.path("result");
    JsonNode results = originalResult.path("results");
    assertThat(originalResult.path("successTenants").asInt()).isZero();
    assertThat(originalResult.path("failureTenants").asInt()).isEqualTo(1);
    assertThat(results).hasSize(1);
    JsonNode failure = results.get(0);
    assertThat(failure.path("tenantId").asText()).isEqualTo(target);
    assertThat(failure.path("success").asBoolean()).isFalse();
    assertThat(failure.path("errorMessage").asText()).isNotBlank();
    assertThat(queuesBeforeRollback.get()).isEqualTo(1);
    Integer committedQueues = jdbc.queryForObject(queueSql, Integer.class, target);
    Integer committedWindows = jdbc.queryForObject(windowSql, Integer.class, target);
    assertThat(committedQueues).isZero();
    assertThat(committedWindows).isZero();

    JsonNode recovered = responseData(
        batchOperationStatus(client, admin, fixture.auditTenant(), "JOB_BUNDLE_IMPORT", key));
    assertThat(recovered.path("operationId").asText())
        .isEqualTo(originalResult.path("batchOperationId").asText());
    assertThat(recovered.path("status").asText()).isEqualTo("COMPLETE");
    JsonNode targets = recovered.path("targets");
    assertThat(targets).hasSize(1);
    assertThat(targets.get(0).path("tenantId").asText()).isEqualTo(target);
    assertThat(targets.get(0).path("status").asText()).isEqualTo("COMPLETE");
    assertThat(targets.get(0).path("result")).isEqualTo(failure);
    assertThat(targets.get(0).path("result").path("success").asBoolean()).isFalse();
  }

  @Test
  @DisplayName("作业包按原键回查重复目标顺序，未确认回执保持未知且不会重复写入")
  void shouldRecoverAuthenticatedJobBundleImportWithRepeatedTargets() throws Exception {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "bundle-recovery-" + UUID.randomUUID();
    String uri = "/api/console/jobs/bundle/import";
    String first = fixture.targets().getFirst();
    String second = fixture.targets().getLast();
    List<String> orderedTargets = List.of(first, second, first);
    Map<String, Object> request = Map.of(
        "tenantId",
        fixture.auditTenant(),
        "targetTenantIds",
        orderedTargets,
        "mode",
        "UPSERT",
        "bundle",
        fixture.request().getBundle());

    JsonNode original = responseData(post(client, admin, uri, key, request));
    JsonNode originalResults = original.path("result").path("results");
    String operationId = original.path("result").path("batchOperationId").asText();
    assertThat(operationId).isNotBlank();
    assertThat(originalResults).hasSize(3);
    assertThat(original.path("result").path("successTenants").asInt()).isEqualTo(3);
    assertThat(original.path("result").path("failureTenants").asInt()).isZero();
    for (JsonNode item : originalResults) {
      assertThat(item.path("success").asBoolean()).isTrue();
      assertThat(item.path("resourceQueues").path("failed").asInt()).isZero();
    }
    assertThat(originalResults.get(0).path("resourceQueues").path("created").asInt())
        .isEqualTo(1);
    assertThat(originalResults.get(1).path("resourceQueues").path("created").asInt())
        .isEqualTo(1);
    assertThat(originalResults.get(2).path("resourceQueues").path("updated").asInt())
        .isEqualTo(1);
    String queueSql = """
        select queue_name from batch.resource_queue
        where tenant_id = ? and queue_code = 'sync_receipt_queue'
        """;
    for (String tenant : fixture.targets()) {
      String queueName = jdbc.queryForObject(queueSql, String.class, tenant);
      assertThat(queueName).isEqualTo("sync receipt queue");
    }
    List<Long> revisions = targetRevisions(fixture);
    assertThat(revisions.getFirst()).isGreaterThan(revisions.getLast());

    JsonNode recovered = responseData(
        batchOperationStatus(client, admin, fixture.auditTenant(), "JOB_BUNDLE_IMPORT", key));
    assertThat(recovered.path("operationId").asText()).isEqualTo(operationId);
    assertThat(recovered.path("status").asText()).isEqualTo("COMPLETE");
    assertThat(recovered
            .path("responseMetadata")
            .path("summary")
            .path("resourceQueues")
            .asInt())
        .isEqualTo(1);
    assertRecoveredTargets(recovered, originalResults, orderedTargets);
    JsonNode reread = responseData(
        batchOperationStatus(client, admin, fixture.auditTenant(), "JOB_BUNDLE_IMPORT", key));
    assertThat(reread).isEqualTo(recovered);
    post(client, admin, uri, key, request).expectStatus().isEqualTo(409);
    assertThat(targetRevisions(fixture)).containsExactlyElementsOf(revisions);

    Auth reader = authenticate(client, fixture.auditTenant(), "ROLE_TENANT_ADMIN");
    batchOperationStatus(client, reader, fixture.auditTenant(), "JOB_BUNDLE_IMPORT", key)
        .expectStatus()
        .isForbidden();
    batchOperationStatus(client, admin, fixture.auditTenant(), "JOB_BUNDLE_IMPORT", key + "-wrong")
        .expectStatus()
        .isNotFound();
    batchOperationStatus(client, admin, first, "JOB_BUNDLE_IMPORT", key)
        .expectStatus()
        .isNotFound();
    Auth otherHeader = authenticate(client, first, "ROLE_ADMIN");
    batchOperationStatus(client, otherHeader, fixture.auditTenant(), "JOB_BUNDLE_IMPORT", key)
        .expectStatus()
        .isNotFound();

    // 仅模拟某条已登记回执缺失；真实目标事务仍已提交，不能将它解释为业务回滚。
    String removeReceiptSql = """
        delete from batch.config_batch_target_receipt
        where audit_tenant_id = ? and operation_id = ? and target_ordinal = ?
        """;
    assertThat(jdbc.update(removeReceiptSql, fixture.auditTenant(), operationId, 1))
        .isEqualTo(1);
    JsonNode incomplete = responseData(
        batchOperationStatus(client, admin, fixture.auditTenant(), "JOB_BUNDLE_IMPORT", key));
    assertThat(incomplete.path("status").asText()).isEqualTo("UNKNOWN");
    assertThat(incomplete.path("targets").get(1).path("status").asText()).isEqualTo("UNKNOWN");
    assertThat(incomplete.path("targets").get(1).path("result").isNull()).isTrue();
    assertThat(incomplete.path("targets").get(0).path("result")).isEqualTo(originalResults.get(0));
    assertThat(incomplete.path("targets").get(2).path("result")).isEqualTo(originalResults.get(2));
    assertThat(targetRevisions(fixture)).containsExactlyElementsOf(revisions);
  }

  @Test
  @DisplayName("认证租户复制使用真实源队列，原键回查不重新写入目标")
  void shouldRecoverAuthenticatedTenantCopyWithoutRepeatingTargetWrites() throws Exception {
    Fixture fixture = fixture();
    String insertSourceSql = """
        insert into batch.resource_queue (tenant_id, queue_code, queue_name, queue_type)
        values (?, 'source_recovery_queue', 'source recovery queue', 'MIXED')
        """;
    jdbc.update(insertSourceSql, fixture.auditTenant());
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "copy-recovery-" + UUID.randomUUID();
    String uri = "/api/console/config/tenant-copy";
    Map<String, Object> request = Map.of(
        "sourceTenantId", fixture.auditTenant(),
        "targetTenantIds", fixture.targets(),
        "configTypes", List.of("RESOURCE_QUEUE"),
        "mode", "UPSERT");

    JsonNode original = responseData(post(client, admin, uri, key, request));
    String operationId = original.path("batchOperationId").asText();
    assertThat(operationId).isNotBlank();
    assertThat(original.path("successTenants").asInt()).isEqualTo(2);
    assertThat(original.path("failureTenants").asInt()).isZero();
    JsonNode originalResults = original.path("results");
    assertThat(originalResults).hasSize(2);
    for (JsonNode item : originalResults) {
      assertThat(item.path("success").asBoolean()).isTrue();
      assertThat(item.path("resourceQueues").path("created").asInt()).isEqualTo(1);
      assertThat(item.path("resourceQueues").path("failed").asInt()).isZero();
    }
    String queueSql = """
        select queue_name from batch.resource_queue
        where tenant_id = ? and queue_code = 'source_recovery_queue'
        """;
    for (String tenant : fixture.targets()) {
      String queueName = jdbc.queryForObject(queueSql, String.class, tenant);
      assertThat(queueName).isEqualTo("source recovery queue");
    }
    List<Long> revisions = targetRevisions(fixture);
    JsonNode recovered = responseData(
        batchOperationStatus(client, admin, fixture.auditTenant(), "TENANT_COPY", key));
    assertThat(recovered.path("operationId").asText()).isEqualTo(operationId);
    assertThat(recovered.path("status").asText()).isEqualTo("COMPLETE");
    assertRecoveredTargets(recovered, original.path("results"), fixture.targets());
    assertThat(responseData(
            batchOperationStatus(client, admin, fixture.auditTenant(), "TENANT_COPY", key)))
        .isEqualTo(recovered);
    post(client, admin, uri, key, request).expectStatus().isEqualTo(409);
    assertThat(targetRevisions(fixture)).containsExactlyElementsOf(revisions);
  }

  @Test
  @DisplayName("作业包创建的原键回查返回原操作和目标结果")
  void shouldRecoverAuthenticatedJobBundleCreate() throws Exception {
    Fixture fixture = fixture();
    WebTestClient client = client();
    Auth admin = authenticate(client, fixture.auditTenant(), "ROLE_ADMIN");
    String key = "create-recovery-" + UUID.randomUUID();
    String uri = "/api/console/jobs/bundle/create";
    Map<String, Object> request =
        Map.of("tenantId", fixture.auditTenant(), "bundle", fixture.request().getBundle());
    JsonNode original = responseData(post(client, admin, uri, key, request));
    String operationId = original.path("result").path("batchOperationId").asText();
    assertThat(operationId).isNotBlank();
    JsonNode originalResult = original.path("result");
    assertThat(originalResult.path("successTenants").asInt()).isEqualTo(1);
    assertThat(originalResult.path("failureTenants").asInt()).isZero();
    JsonNode originalResults = originalResult.path("results");
    assertThat(originalResults).hasSize(1);
    assertThat(originalResults.get(0).path("success").asBoolean()).isTrue();
    assertThat(originalResults.get(0).path("resourceQueues").path("created").asInt())
        .isEqualTo(1);
    assertThat(originalResults.get(0).path("resourceQueues").path("failed").asInt())
        .isZero();
    String queueSql = """
        select queue_name from batch.resource_queue
        where tenant_id = ? and queue_code = 'sync_receipt_queue'
        """;
    String queueName = jdbc.queryForObject(queueSql, String.class, fixture.auditTenant());
    assertThat(queueName).isEqualTo("sync receipt queue");
    String revisionSql =
        "select revision from batch.tenant_configuration_revision where tenant_id = ?";
    Long revision = jdbc.queryForObject(revisionSql, Long.class, fixture.auditTenant());
    JsonNode recovered = responseData(
        batchOperationStatus(client, admin, fixture.auditTenant(), "JOB_BUNDLE_CREATE", key));
    assertThat(recovered.path("operationId").asText()).isEqualTo(operationId);
    assertThat(recovered.path("status").asText()).isEqualTo("COMPLETE");
    assertRecoveredTargets(
        recovered, original.path("result").path("results"), List.of(fixture.auditTenant()));
    assertThat(responseData(
            batchOperationStatus(client, admin, fixture.auditTenant(), "JOB_BUNDLE_CREATE", key)))
        .isEqualTo(recovered);
    post(client, admin, uri, key, request).expectStatus().isEqualTo(409);
    assertThat(jdbc.queryForObject(revisionSql, Long.class, fixture.auditTenant()))
        .isEqualTo(revision);
  }

  private JsonNode responseData(WebTestClient.ResponseSpec response) throws Exception {
    String body =
        response.expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
    return objectMapper.readTree(body).path("data");
  }

  private void assertRecoveredTargets(
      JsonNode recovered, JsonNode original, List<String> expected) {
    JsonNode targets = recovered.path("targets");
    assertThat(targets).hasSize(expected.size());
    assertThat(original).hasSize(expected.size());
    for (int ordinal = 0; ordinal < expected.size(); ordinal++) {
      JsonNode target = targets.get(ordinal);
      assertThat(target.path("ordinal").asInt()).isEqualTo(ordinal);
      assertThat(target.path("tenantId").asText()).isEqualTo(expected.get(ordinal));
      assertThat(target.path("status").asText()).isEqualTo("COMPLETE");
      assertThat(target.path("result")).isEqualTo(original.get(ordinal));
    }
  }

  private List<Long> targetRevisions(Fixture fixture) {
    String revisionSql =
        "select revision from batch.tenant_configuration_revision where tenant_id = ?";
    return fixture.targets().stream()
        .map(tenant -> jdbc.queryForObject(revisionSql, Long.class, tenant))
        .toList();
  }

  private WebTestClient.ResponseSpec batchOperationStatus(
      WebTestClient client, Auth auth, String auditTenantId, String operationKind, String key) {
    return client
        .get()
        .uri(uri -> uri.path("/api/console/config/batch-operation")
            .queryParam("auditTenantId", auditTenantId)
            .queryParam("operationKind", operationKind)
            .build())
        .cookie("batch_console_token", auth.jwt())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, auth.tenant())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, key)
        .exchange();
  }

  private Fixture fixture() {
    String suffix = UUID.randomUUID().toString().replace("-", "");
    String audit = "sync-audit-" + suffix;
    List<String> targets = List.of("sync-a-" + suffix, "sync-b-" + suffix);
    String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
        """;
    ResourceQueueSpec queue = new ResourceQueueSpec();
    queue.setQueueCode("sync_receipt_queue");
    queue.setQueueName("sync receipt queue");
    queue.setQueueType("MIXED");
    ConfigSyncBundlePayload bundle = new ConfigSyncBundlePayload();
    bundle.setResourceQueues(List.of(queue));
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId(audit);
    request.setSourceEnv("test-source");
    request.setTargetEnv("test-target");
    request.setTargetTenantIds(targets);
    request.setBundle(bundle);
    Fixture fixture = new Fixture(audit, targets, request);
    ownedFixtures.add(fixture);
    for (String tenant : List.of(audit, targets.getFirst(), targets.getLast())) {
      jdbc.update(insertTenantSql, tenant, tenant);
    }
    return fixture;
  }

  private String reconcileAfterRelease(
      WebTestClient client,
      Auth auth,
      String uri,
      String key,
      CountDownLatch ready,
      CountDownLatch release)
      throws InterruptedException {
    ready.countDown();
    boolean released = release.await(30, TimeUnit.SECONDS);
    assertThat(released).isTrue();
    return post(client, auth, uri, key, Map.of())
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private String installFault(Fixture fixture, boolean receipt) {
    String fault = "sync_fault_" + UUID.randomUUID().toString().replace("-", "");
    String createFunctionSql = """
        create function batch.%s() returns trigger language plpgsql as $$
        begin raise exception 'test config sync persistence failure' using errcode = '23514'; end;
        $$
        """.formatted(fault);
    String table = receipt ? "config_sync_target_receipt" : "config_sync_log";
    String event = receipt ? "insert" : "update";
    String tenantColumn = receipt ? "audit_tenant_id" : "tenant_id";
    String createTriggerSql =
        """
        create trigger %s before %s on batch.%s for each row
        when (NEW.%s = '%s') execute function batch.%s()
        """.formatted(fault, event, table, tenantColumn, fixture.auditTenant(), fault);
    jdbc.execute(createFunctionSql);
    try {
      jdbc.execute(createTriggerSql);
      return fault;
    } catch (RuntimeException failure) {
      String dropFunctionSql = "drop function if exists batch.%s()".formatted(fault);
      try {
        jdbc.execute(dropFunctionSql);
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private void removeFault(String fault, boolean receipt) {
    String table = receipt ? "config_sync_target_receipt" : "config_sync_log";
    String dropTriggerSql = "drop trigger if exists %s on batch.%s".formatted(fault, table);
    String dropFunctionSql = "drop function if exists batch.%s()".formatted(fault);
    jdbc.execute(dropTriggerSql);
    jdbc.execute(dropFunctionSql);
  }

  private Long logId(Fixture fixture) {
    String selectLogSql = "select id from batch.config_sync_log where tenant_id = ?";
    return jdbc.queryForObject(selectLogSql, Long.class, fixture.auditTenant());
  }

  private void assertPersisted(Fixture fixture, int configs, int receipts, String status) {
    String selectConfigurationsSql = """
        select count(*) from batch.resource_queue where tenant_id in (?, ?)
        """;
    String selectReceiptsSql = """
        select count(*) from batch.config_sync_target_receipt where audit_tenant_id = ?
        """;
    String selectAuditSql = """
        select sync_status from batch.config_sync_log where tenant_id = ?
        """;
    Integer actualConfigs = jdbc.queryForObject(
        selectConfigurationsSql,
        Integer.class,
        fixture.targets().getFirst(),
        fixture.targets().getLast());
    Integer actualReceipts =
        jdbc.queryForObject(selectReceiptsSql, Integer.class, fixture.auditTenant());
    String actualStatus = jdbc.queryForObject(selectAuditSql, String.class, fixture.auditTenant());
    assertThat(actualConfigs).isEqualTo(configs);
    assertThat(actualReceipts).isEqualTo(receipts);
    assertThat(actualStatus).isEqualTo(status);
  }

  private WebTestClient client() {
    return WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(60))
        .build();
  }

  private Auth authenticate(WebTestClient client, String tenant, String role) {
    String username = "sync-operator-" + role;
    String jwt = jwtService.issueToken(username, tenant, Set.of(role)).accessToken();
    ResponseCookie csrfCookie = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .exchange()
        .expectStatus()
        .isNoContent()
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(csrfCookie).isNotNull();
    return new Auth(username, tenant, jwt, csrfCookie.getValue());
  }

  private WebTestClient.ResponseSpec post(
      WebTestClient client, Auth auth, String uri, String key, Object body) {
    return client
        .post()
        .uri(uri)
        .cookie("batch_console_token", auth.jwt())
        .cookie("XSRF-TOKEN", auth.csrf())
        .header("X-XSRF-TOKEN", auth.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, auth.tenant())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, key)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private WebTestClient.ResponseSpec requestStatus(
      WebTestClient client, Auth auth, String tenantId, String originalKey) {
    return client
        .get()
        .uri(uri -> uri.path("/api/console/config/sync/requests/status")
            .queryParam("tenantId", tenantId)
            .build())
        .cookie("batch_console_token", auth.jwt())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, auth.tenant())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, originalKey)
        .exchange();
  }

  private WebTestClient.ResponseSpec batchDayStatus(
      WebTestClient client, Auth auth, String tenantId, String originalKey) {
    return client
        .get()
        .uri(uri -> uri.path("/api/console/jobs/batch-days/catchup/requests/status")
            .queryParam("tenantId", tenantId)
            .build())
        .cookie("batch_console_token", auth.jwt())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, auth.tenant())
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, originalKey)
        .exchange();
  }

  private WebTestClient.ResponseSpec batchDayReconcile(
      WebTestClient client, Auth auth, String tenantId, String originalKey, String jobCode) {
    return client
        .post()
        .uri("/api/console/jobs/batch-days/catchup/requests/reconcile")
        .cookie("batch_console_token", auth.jwt())
        .cookie("XSRF-TOKEN", auth.csrf())
        .header("X-XSRF-TOKEN", auth.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, auth.tenant())
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
        .header("X-Original-Idempotency-Key", originalKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", tenantId, "jobCode", jobCode))
        .exchange();
  }

  private WebTestClient.ResponseSpec batchDayContinue(
      WebTestClient client, Auth auth, String tenantId, String originalKey) {
    return client
        .post()
        .uri("/api/console/jobs/batch-days/catchup/requests/continue")
        .cookie("batch_console_token", auth.jwt())
        .cookie("XSRF-TOKEN", auth.csrf())
        .header("X-XSRF-TOKEN", auth.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, auth.tenant())
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString())
        .header("X-Original-Idempotency-Key", originalKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", tenantId))
        .exchange();
  }

  private static String reconcileUri(String tenant, Long logId) {
    return "/api/console/config/sync/logs/" + logId + "/reconcile?tenantId=" + tenant;
  }

  private record Fixture(
      String auditTenant, List<String> targets, ConfigSyncImportRequest request) {}

  private record Auth(String username, String tenant, String jwt, String csrf) {}

  private static final class SimulatedCoordinatorStop extends Error {}
}
