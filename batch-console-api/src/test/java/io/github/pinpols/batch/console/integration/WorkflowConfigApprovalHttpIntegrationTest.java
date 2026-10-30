package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.HtmlUtils;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.console.security.single-session-enabled=false"})
@Import(WorkflowConfigApprovalHttpIntegrationTest.SecurityMode.class)
@DisplayName("Workflow 发布审批的真实认证、版本变化拒绝与审计接口回读")
class WorkflowConfigApprovalHttpIntegrationTest extends AbstractIntegrationTest {

  private static final String TENANT = "t1";
  private static final String SELECT_WORKFLOW_NAME_SQL = """
      select workflow_name from batch.workflow_definition where tenant_id=? and workflow_code=?
      """;
  private static final String SELECT_CONFIG_STATUS_SQL = """
      select config_status from batch.config_release where tenant_id=? and id=?
      """;
  private static final String SELECT_APPROVAL_STATUS_SQL = """
      select approval_status from batch.config_approval where tenant_id=? and release_id=?
      """;
  private static final String COUNT_WORKFLOW_DEFINITIONS_SQL = """
      select count(*) from batch.workflow_definition where tenant_id=? and workflow_code=?
      """;

  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMode {
    @Bean
    static BeanPostProcessor requireAuthentication() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
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

  @Autowired
  private ConsoleJwtService jwtService;

  @Autowired
  private BatchSecurityProperties security;

  @Autowired
  private ObjectMapper objectMapper;

  @Autowired
  private JdbcTemplate jdbc;

  private WebTestClient client;
  private Credentials maker;
  private Credentials checker;
  private Credentials auditor;

  @BeforeEach
  void authenticateActors() {
    assertThat(security.isBypassMode()).isFalse();
    client = WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .responseTimeout(Duration.ofSeconds(30))
        .build();
    maker = authenticate("wf-maker", TENANT, "ROLE_ADMIN");
    checker = authenticate("wf-checker", TENANT, "ROLE_ADMIN");
    auditor = authenticate("wf-auditor", TENANT, "ROLE_AUDITOR");
  }

  @Test
  @DisplayName("申请人不能自批且审计员只读，独立审批者发布后可回读身份、理由与状态")
  void shouldPublishAndReadAudit_whenIndependentAdministratorApproves() throws Exception {
    String code = workflowCode();
    long releaseId = createRelease(code, "Approved workflow");
    long approvalId = submit(releaseId);
    String path = "/api/console/config/approvals/" + approvalId + "/approve";
    Map<String, Object> action = Map.of("tenantId", TENANT, "reason", "reviewed frozen graph");

    post(maker, path, action).expectStatus().isEqualTo(409);
    post(auditor, path, action).expectStatus().isForbidden();
    assertPendingWithoutRuntimeDefinition(releaseId, code);

    JsonNode approved = response(post(checker, path, action).expectStatus().isOk());
    assertThat(approved.path("data").path("configStatus").asText()).isEqualTo("PUBLISHED");
    JsonNode approval = approved.path("data").path("approval");
    assertThat(approval.path("requestedBy").asText()).isEqualTo("wf-maker");
    assertThat(approval.path("reviewedBy").asText()).isEqualTo("wf-checker");
    assertThat(approval.path("reviewComment").asText()).isEqualTo("reviewed frozen graph");
    String selected_WORKFLOW_NAME_ =
        jdbc.queryForObject(SELECT_WORKFLOW_NAME_SQL, String.class, TENANT, code);
    assertThat(selected_WORKFLOW_NAME_).isEqualTo("Approved workflow");

    JsonNode changes = response(get(
                auditor,
                "/api/console/config/change-logs?tenantId=t1&configType=WORKFLOW&configKey=" + code)
            .expectStatus()
            .isOk())
        .path("data");
    assertThat(changes.isArray()).isTrue();
    JsonNode approvalChange = null;
    for (JsonNode change : changes) {
      assertThat(change.path("configKey").asText()).isEqualTo(code);
      if ("APPROVE".equals(change.path("changeAction").asText())) {
        approvalChange = change;
      }
    }
    assertThat(approvalChange).isNotNull();
    assertThat(approvalChange.path("operatorId").asText()).isEqualTo("wf-checker");
    assertThat(approvalChange.path("changeResult").asText()).isEqualTo("SUCCESS");
    // ConfigChangeLogsTab 通过 decodeHtmlEntities 解码展示字段后再解析，不改变现有 API 编码契约。
    String summaryJson =
        HtmlUtils.htmlUnescape(approvalChange.path("changeSummaryJson").asText());
    JsonNode summary = objectMapper.readTree(summaryJson);
    assertThat(summary.path("reason").asText()).isEqualTo("reviewed frozen graph");
    assertThat(summary.path("detail").path("approvalId").asLong()).isEqualTo(approvalId);
    assertThat(summary.path("detail").path("nextStatus").asText()).isEqualTo("PUBLISHED");
    assertThat(approvalChange.path("traceId").asText()).isNotBlank();
  }

  @Test
  @DisplayName("提交后出现新版本时拒绝过期审批，审批与运行定义均无成功副作用")
  void shouldRejectStaleApproval_whenNewDefinitionVersionExists() throws Exception {
    String code = workflowCode();
    long releaseId = createRelease(code, "Original graph");
    long approvalId = submit(releaseId);
    long newerReleaseId = createRelease(code, "Changed graph");
    assertThat(newerReleaseId).isNotEqualTo(releaseId);

    post(
            checker,
            "/api/console/config/approvals/" + approvalId + "/approve",
            Map.of("tenantId", TENANT, "reason", "stale graph must not publish"))
        .expectStatus()
        .isEqualTo(409);
    assertPendingWithoutRuntimeDefinition(releaseId, code);
    String selected_CONFIG_STATUS_ =
        jdbc.queryForObject(SELECT_CONFIG_STATUS_SQL, String.class, TENANT, newerReleaseId);
    assertThat(selected_CONFIG_STATUS_).isEqualTo("DRAFT");
    JsonNode changes = response(get(
                auditor,
                "/api/console/config/change-logs?tenantId=t1&configType=WORKFLOW&configKey=" + code
                    + "&changeAction=APPROVE")
            .expectStatus()
            .isOk())
        .path("data");
    assertThat(changes.isArray()).isTrue();
    assertThat(changes.size()).isZero();
  }

  @Test
  @DisplayName("租户管理员不能借请求体或审批编号读取及批准其他租户的 Workflow")
  void shouldRejectCrossTenantReference_whenTenantAdministratorChangesRequestTenant()
      throws Exception {
    String code = workflowCode();
    long releaseId = createRelease(code, "Tenant isolated graph");
    long approvalId = submit(releaseId);
    Credentials otherTenant = authenticate("wf-other-admin", "t2", "ROLE_TENANT_ADMIN");
    post(
            otherTenant,
            "/api/console/config/approvals/" + approvalId + "/approve",
            Map.of("tenantId", TENANT, "reason", "cross tenant denied"))
        .expectStatus()
        .isForbidden();
    get(otherTenant, "/api/console/config/releases/" + releaseId + "/approval?tenantId=t1")
        .expectStatus()
        .isForbidden();
    get(otherTenant, "/api/console/config/releases/" + releaseId + "/approval?tenantId=t2")
        .expectStatus()
        .isNotFound();
    assertPendingWithoutRuntimeDefinition(releaseId, code);
  }

  private long createRelease(String code, String name) throws Exception {
    String graph = objectMapper.writeValueAsString(Map.of(
        "workflowCode",
        code,
        "workflowName",
        name,
        "workflowType",
        "DAG",
        "enabled",
        true,
        "scheduleEnabled",
        false,
        "scheduleType",
        "MANUAL",
        "timezone",
        "UTC",
        "triggerMode",
        "MANUAL",
        "nodes",
        List.of(
            Map.of(
                "nodeCode",
                "START",
                "nodeName",
                "Start",
                "nodeType",
                "START",
                "nodeOrder",
                1,
                "enabled",
                true),
            Map.of(
                "nodeCode",
                "END",
                "nodeName",
                "End",
                "nodeType",
                "END",
                "nodeOrder",
                2,
                "enabled",
                true)),
        "edges",
        List.of(Map.of(
            "fromNodeCode",
            "START",
            "toNodeCode",
            "END",
            "edgeType",
            "SUCCESS",
            "enabled",
            true))));
    long version = response(post(
                maker,
                "/api/console/config/releases",
                Map.of(
                    "tenantId",
                    TENANT,
                    "configType",
                    "WORKFLOW",
                    "configKey",
                    code,
                    "configName",
                    name,
                    "configPayloadJson",
                    graph,
                    "reason",
                    "approve graph change"))
            .expectStatus()
            .isOk())
        .path("data")
        .asLong();
    assertThat(version).isPositive();
    JsonNode releases = response(get(
                maker,
                "/api/console/config/releases?tenantId=t1&configType=WORKFLOW&configKey=" + code
                    + "&versionNo=" + version)
            .expectStatus()
            .isOk())
        .path("data");
    assertThat(releases.isArray()).isTrue();
    assertThat(releases.size()).isOne();
    long releaseId = releases.get(0).path("id").asLong();
    assertThat(releaseId).isPositive();
    return releaseId;
  }

  private long submit(long releaseId) throws Exception {
    return response(post(
                maker,
                "/api/console/config/releases/" + releaseId + "/submit-approval",
                Map.of("tenantId", TENANT, "reason", "review graph before publishing"))
            .expectStatus()
            .isOk())
        .path("data")
        .path("approval")
        .path("id")
        .asLong();
  }

  private void assertPendingWithoutRuntimeDefinition(long releaseId, String code) {
    String selected_CONFIG_STATUS_ =
        jdbc.queryForObject(SELECT_CONFIG_STATUS_SQL, String.class, TENANT, releaseId);
    assertThat(selected_CONFIG_STATUS_).isEqualTo("PENDING_APPROVAL");
    String selected_APPROVAL_STATUS_ =
        jdbc.queryForObject(SELECT_APPROVAL_STATUS_SQL, String.class, TENANT, releaseId);
    assertThat(selected_APPROVAL_STATUS_).isEqualTo("PENDING");
    Long selected_WORKFLOW_DEFINITIONS_ =
        jdbc.queryForObject(COUNT_WORKFLOW_DEFINITIONS_SQL, Long.class, TENANT, code);
    assertThat(selected_WORKFLOW_DEFINITIONS_).isZero();
  }

  private Credentials authenticate(String username, String tenant, String role) {
    String jwt = jwtService.issueToken(username, tenant, Set.of(role)).accessToken();
    var csrf = client
        .get()
        .uri("/api/console/auth/check")
        .cookie("batch_console_token", jwt)
        .exchange()
        .expectStatus()
        .isNoContent()
        .returnResult(Void.class)
        .getResponseCookies()
        .getFirst("XSRF-TOKEN");
    assertThat(csrf).isNotNull();
    return new Credentials(jwt, csrf.getValue(), tenant);
  }

  private WebTestClient.ResponseSpec post(
      Credentials actor, String path, Map<String, Object> body) {
    return client
        .post()
        .uri(path)
        .cookie("batch_console_token", actor.jwt())
        .cookie("XSRF-TOKEN", actor.csrf())
        .header("X-XSRF-TOKEN", actor.csrf())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, actor.tenant())
        .header(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "spoofed-operator")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "wf-approval-" + UUID.randomUUID())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .exchange();
  }

  private WebTestClient.ResponseSpec get(Credentials actor, String path) {
    return client
        .get()
        .uri(path)
        .cookie("batch_console_token", actor.jwt())
        .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, actor.tenant())
        .exchange();
  }

  private JsonNode response(WebTestClient.ResponseSpec response) throws Exception {
    byte[] body = response.expectBody().returnResult().getResponseBody();
    assertThat(body).isNotNull();
    return objectMapper.readTree(body);
  }

  private String workflowCode() {
    return "WF_APPROVAL_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  private record Credentials(String jwt, String csrf, String tenant) {}
}
