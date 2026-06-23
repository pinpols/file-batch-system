package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/quota-policies → batch.tenant_quota_policy。
 *
 * <p>守护 policyCode 字段校验 + 写入 + 唯一约束 + 数字字段 @Min 边界。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleQuotaPolicyMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code, int maxJobs) {
    return "{"
        + "\"tenantId\":\"int-qp-ta\","
        + "\"policyCode\":\""
        + code
        + "\","
        + "\"maxRunningJobsPerTenant\":"
        + maxJobs
        + ","
        + "\"maxPartitionsPerTenant\":100,"
        + "\"maxQpsPerTenant\":10,"
        + "\"fairShareWeight\":1,"
        + "\"enabled\":false"
        + "}";
  }

  @Test
  void shouldCreateQuotaPolicyWithValidCode() {
    String code = "int_qp_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/quota-policies")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-qp-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, 10))
        .exchange()
        .expectStatus()
        .isOk();

    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, policy_code, max_running_jobs_per_tenant"
                + " FROM batch.tenant_quota_policy WHERE policy_code = ?",
            code);
    assertThat(row.get("tenant_id")).isEqualTo("int-qp-ta");
    assertThat(row.get("policy_code")).isEqualTo(code);
    assertThat(((Number) row.get("max_running_jobs_per_tenant")).intValue()).isEqualTo(10);

    jdbcTemplate.update("DELETE FROM batch.tenant_quota_policy WHERE policy_code = ?", code);
  }

  @Test
  void shouldRejectInvalidPolicyCodeWithSpaces() {
    client
        .post()
        .uri("/api/console/quota-policies")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-qp-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q", 10))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));
  }

  @Test
  void shouldRejectChinesePolicyCode() {
    client
        .post()
        .uri("/api/console/quota-policies")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-qp-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("中文配额", 10))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectNegativeMaxJobs() {
    // @Min(0) 拦截
    client
        .post()
        .uri("/api/console/quota-policies")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-qp-bad-3")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("int_qp_neg", -5))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectDuplicatePolicyCode() {
    String code = "int_qp_dup_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/quota-policies")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-qp-dup-1-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, 10))
        .exchange()
        .expectStatus()
        .isOk();

    client
        .post()
        .uri("/api/console/quota-policies")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-qp-dup-2-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, 10))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    jdbcTemplate.update("DELETE FROM batch.tenant_quota_policy WHERE policy_code = ?", code);
  }
}
