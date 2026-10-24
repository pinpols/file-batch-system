package io.github.pinpols.batch.trigger.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.security.ApiKeyHasher;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.BatchIntegrationTest;
import io.github.pinpols.batch.trigger.BatchTriggerApplication;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyVerifier;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.quartz.Scheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = BatchTriggerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
      "batch.security.bypass-mode=false",
      "batch.security.internal-secret=integration-secret-for-trigger-http",
      "spring.autoconfigure.exclude=org.springframework.boot.quartz.autoconfigure.QuartzAutoConfiguration",
      "batch.trigger.quartz-metrics.enabled=false"
    })
@BatchIntegrationTest
@AutoConfigureMockMvc
@Import(ExternalEventApiKeyHttpIntegrationTest.TestConfig.class)
@DisplayName(
    "外部事件 API Key HTTP 链路：真实 API key、Spring Security filter、Controller、Trigger 与 PostgreSQL")
class ExternalEventApiKeyHttpIntegrationTest {

  @DynamicPropertySource
  public static void registerDynamicProperties(DynamicPropertyRegistry registry) {
    AbstractIntegrationTest.registerDynamicProperties(registry, false);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class TestConfig {
    @Bean
    @Primary
    RestClient testOrchestratorRestClient() {
      RestClient client = Mockito.mock(RestClient.class, Answers.RETURNS_DEEP_STUBS);
      RestClient.ResponseSpec response =
          client.get().uri("/internal/orchestrator/drain/status").retrieve();
      Mockito.doReturn(ResponseEntity.status(HttpStatus.OK).build())
          .when(response)
          .toBodilessEntity();
      return client;
    }

    @Bean
    Scheduler scheduler() {
      return Mockito.mock(Scheduler.class);
    }
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("旧 Job 根字段和双目标请求均拒绝且不写请求或 Outbox")
  void shouldRejectLegacyJobTargetAndDualTargetWithoutWrites() throws Exception {
    final String insertTenantSql = """
        insert into batch.tenant (tenant_id, tenant_name, status) values (?, ?, 'ACTIVE')
    """;
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, calendar_code) values (?, ?, ?, 'DAG', 1, true, 'HTTP_EVENT_CAL') returning id\
        """;
    final String deleteTriggerOutboxEventSql = """
        delete from batch.trigger_outbox_event where tenant_id = ? and request_id in (?, ?, ?)
    """;
    final String deleteTriggerRequestSql = """
        delete from batch.trigger_request where tenant_id = ? and request_id in (?, ?, ?)
    """;
    final String deleteWorkflowDefinitionSql = """
        delete from batch.workflow_definition where tenant_id = ? and id = ?
    """;
    final String deleteTenantSql = """
        delete from batch.tenant where tenant_id = ?
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenantId = "legacy-launch-" + suffix.substring(0, 12);
    String workflowCode = "legacy-launch-wf-" + suffix;
    String legacyRequestId = "legacy-launch-old-" + suffix;
    String dualRequestId = "legacy-launch-dual-" + suffix;
    String forgedParentRequestId = "legacy-launch-parent-" + suffix;
    Long workflowId = null;
    jdbcTemplate.update(
        insertTenantSql,
        tenantId,
        tenantId);
    try {
      workflowId = jdbcTemplate.queryForObject(
          insertWorkflowDefinitionSql,
          Long.class,
          tenantId,
          workflowCode,
          workflowCode);

      String oldJobOnlyRequest = """
          {
            "tenantId": "%s",
            "jobCode": "LEGACY_JOB",
            "bizDate": "2032-05-11",
            "triggerType": "API"
          }
          """.formatted(tenantId);
      mockMvc
          .perform(post("/api/triggers/launch")
              .header(CommonConstants.INTERNAL_SECRET_HEADER, "integration-secret-for-trigger-http")
              .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, legacyRequestId)
              .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, legacyRequestId)
              .contentType(MediaType.APPLICATION_JSON)
              .content(oldJobOnlyRequest))
          .andExpect(status().isBadRequest());

      String dualTargetRequest = """
          {
            "tenantId": "%s",
            "workflowDefinitionId": %d,
            "jobCode": "LEGACY_JOB",
            "bizDate": "2032-05-11",
            "triggerType": "API"
          }
          """.formatted(tenantId, workflowId);
      mockMvc
          .perform(post("/api/triggers/launch")
              .header(CommonConstants.INTERNAL_SECRET_HEADER, "integration-secret-for-trigger-http")
              .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, dualRequestId)
              .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, dualRequestId)
              .contentType(MediaType.APPLICATION_JSON)
              .content(dualTargetRequest))
          .andExpect(status().isBadRequest());

      String forgedParentRequest = """
          {
            "tenantId": "%s",
            "workflowDefinitionId": %d,
            "bizDate": "2032-05-11",
            "triggerType": "API",
            "params": {
              "_parentWorkflowRunId": 900,
              "_parentNodeCode": "FORGED"
            }
          }
          """.formatted(tenantId, workflowId);
      mockMvc
          .perform(post("/api/triggers/launch")
              .header(CommonConstants.INTERNAL_SECRET_HEADER, "integration-secret-for-trigger-http")
              .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, forgedParentRequestId)
              .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, forgedParentRequestId)
              .contentType(MediaType.APPLICATION_JSON)
              .content(forgedParentRequest))
          .andExpect(status().isBadRequest());

      assertThat(countLaunchWrites(tenantId, legacyRequestId)).isZero();
      assertThat(countLaunchWrites(tenantId, dualRequestId)).isZero();
      assertThat(countLaunchWrites(tenantId, forgedParentRequestId)).isZero();
    } finally {
      jdbcTemplate.update(
          deleteTriggerOutboxEventSql,
          tenantId,
          legacyRequestId,
          dualRequestId,
          forgedParentRequestId);
      jdbcTemplate.update(
          deleteTriggerRequestSql,
          tenantId,
          legacyRequestId,
          dualRequestId,
          forgedParentRequestId);
      if (workflowId != null) {
        jdbcTemplate.update(
            deleteWorkflowDefinitionSql,
            tenantId,
            workflowId);
      }
      jdbcTemplate.update(deleteTenantSql, tenantId);
    }
  }

  @Test
  @DisplayName("事件 POST 与状态 GET 均经 API Key 认证，来源绑定 keyName 并落入自动路由")
  void shouldAuthenticateAndRouteExternalEventThroughHttpFilters() throws Exception {
    final String insertWorkflowDefinitionSql = """
        insert into batch.workflow_definition (tenant_id, workflow_code, workflow_name, workflow_type, version,\s\
        enabled, calendar_code) values (?, ?, ?, 'DAG', 1, true, 'HTTP_EVENT_CAL') returning id\
        """;
    final String insertExternalEventExpectationDefinitionSql = """
        insert into batch.external_event_expectation_definition (tenant_id, calendar_code,\s\
        workflow_definition_id, event_code, expected_local_time, enabled)\s\
        values (?, 'HTTP_EVENT_CAL', ?, ?, '08:00', true)\
        """;
    final String insertApiKeySql = """
        insert into batch.api_key (tenant_id, key_name, key_prefix, key_hash, salt, key_hash_algo, scopes,\s\
        enabled) values (?, ?, ?, ?, ?, ?, ?, true)\
        """;
    final String selectEventSourceSql = """
        select event_source from batch.external_event_delivery where tenant_id=? and event_id=?
    """;
    final String selectBizDateSql = """
        select biz_date from batch.trigger_request where tenant_id=? and external_event_id=?
    """;

    String suffix = UUID.randomUUID().toString().replace("-", "");
    String tenantId = "http-event-" + suffix.substring(0, 12);
    String source = "billing-" + suffix.substring(0, 12);
    String rawKey = "bk_" + suffix;
    String idempotencyKey = "http-event-key-" + suffix;
    String eventCode = "HTTP_EVENT_" + suffix;
    LocalDate sourceBizDate = LocalDate.of(2032, 5, 11);
    Long workflowId = jdbcTemplate.queryForObject(
        insertWorkflowDefinitionSql,
        Long.class,
        tenantId,
        "workflow-" + suffix,
        "workflow-" + suffix);
    jdbcTemplate.update(
        insertExternalEventExpectationDefinitionSql,
        tenantId,
        workflowId,
        eventCode);
    ApiKeyHasher.SaltedHash hash = ApiKeyHasher.hashWithSaltKdf(rawKey);
    jdbcTemplate.update(
        insertApiKeySql,
        tenantId,
        source,
        rawKey.substring(0, TriggerApiKeyVerifier.KEY_PREFIX_LENGTH),
        hash.hash(),
        hash.salt(),
        ApiKeyHasher.ALGO_PBKDF2,
        TriggerApiKeyVerifier.EVENT_SCOPE);

    String eventRequest = """
        {
          "tenantId": "%s",
          "externalEventCode": "%s",
          "externalEventId": "payment-close-1",
          "bizDate": "%s",
          "params": {"paymentId": "pay-1"},
          "externalEventSource": "forged-source"
        }
        """.formatted(tenantId, eventCode, sourceBizDate);
    mockMvc
        .perform(post("/api/triggers/events")
            .header(CommonConstants.BATCH_API_KEY_HEADER, "bk_invalid")
            .header(CommonConstants.BATCH_TENANT_ID_HEADER, tenantId)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(eventRequest))
        .andExpect(status().isUnauthorized());

    mockMvc
        .perform(post("/api/triggers/events")
            .header(CommonConstants.BATCH_API_KEY_HEADER, rawKey)
            .header(CommonConstants.BATCH_TENANT_ID_HEADER, tenantId)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .content(eventRequest))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.targets[0].workflowDefinitionId").value(workflowId));

    String selectedEventSource = jdbcTemplate.queryForObject(
        selectEventSourceSql, String.class, tenantId, "payment-close-1");
    assertThat(selectedEventSource).isEqualTo(source);
    LocalDate selectedBizDate =
        jdbcTemplate.queryForObject(selectBizDateSql, LocalDate.class, tenantId, "payment-close-1");
    assertThat(selectedBizDate).isEqualTo(sourceBizDate);

    mockMvc
        .perform(get("/api/triggers/events/status")
            .queryParam("tenantId", tenantId)
            .header(CommonConstants.BATCH_API_KEY_HEADER, rawKey)
            .header(CommonConstants.BATCH_TENANT_ID_HEADER, tenantId)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.eventId").value("payment-close-1"))
        .andExpect(jsonPath("$.data.targets[0].workflowDefinitionId").value(workflowId));
  }

  private int countLaunchWrites(String tenantId, String requestId) {
  final String selectCountSql = """
      select count(*) from batch.trigger_request where tenant_id = ? and request_id = ?
  """;
  final String selectCountSql2 = """
      select count(*) from batch.trigger_outbox_event where tenant_id = ? and request_id = ?
  """;

    Integer requestCount = jdbcTemplate.queryForObject(
        selectCountSql,
        Integer.class,
        tenantId,
        requestId);
    Integer outboxCount = jdbcTemplate.queryForObject(
        selectCountSql2,
        Integer.class,
        tenantId,
        requestId);
    return requestCount + outboxCount;
  }
}
