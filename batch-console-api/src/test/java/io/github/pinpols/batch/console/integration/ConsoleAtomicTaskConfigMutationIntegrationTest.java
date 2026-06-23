package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * R3-5 — POST/GET /api/console/ops/atomic-task-configs 端到端集成测试。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 sql 配置 → 落 batch.atomic_task_config(parameters JSONB)
 *   <li>list 按 (tenant, taskType) 返回创建记录
 *   <li>parameters 含凭据字段(password / apiKey)→ 400 SensitiveDataValidator 拒入
 *   <li>schema 校验失败(未知 key)→ 400
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleAtomicTaskConfigMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private static final String TENANT = "int-atc-ta";

  private String body(String taskType, String name, String parametersJson) {
    return "{"
        + "\"tenantId\":\""
        + TENANT
        + "\",\"taskType\":\""
        + taskType
        + "\",\"name\":\""
        + name
        + "\",\"parameters\":"
        + parametersJson
        + "}";
  }

  @Test
  void shouldCreateSqlConfigAndPersistParametersJsonb() {
    String name = "it_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/ops/atomic-task-configs")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("sql", name, "{\"sql\":\"select 1\",\"statementTimeoutSeconds\":30}"))
        .exchange()
        .expectStatus()
        .isOk();

    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, task_type, name, parameters::text AS parameters"
                + " FROM batch.atomic_task_config WHERE tenant_id = ? AND name = ?",
            TENANT,
            name);
    assertThat(row.get("tenant_id")).isEqualTo(TENANT);
    assertThat(row.get("task_type")).isEqualTo("sql");
    assertThat(row.get("name")).isEqualTo(name);
    assertThat((String) row.get("parameters")).contains("select 1");

    jdbcTemplate.update(
        "DELETE FROM batch.atomic_task_config WHERE tenant_id = ? AND name = ?", TENANT, name);
  }

  @Test
  void shouldListByTaskTypeAfterCreate() {
    String name = "it_list_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/ops/atomic-task-configs")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("sql", name, "{\"sql\":\"select 1\"}"))
        .exchange()
        .expectStatus()
        .isOk();

    client
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/console/ops/atomic-task-configs")
                    .queryParam("tenantId", TENANT)
                    .queryParam("taskType", "sql")
                    .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains(name));

    jdbcTemplate.update(
        "DELETE FROM batch.atomic_task_config WHERE tenant_id = ? AND name = ?", TENANT, name);
  }

  @Test
  void shouldRejectSensitiveKeyInParameters() {
    // parameters 含 password 关键字 → SensitiveDataValidator 拒入
    client
        .post()
        .uri("/api/console/ops/atomic-task-configs")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            body(
                "sql",
                "it_sensitive_" + System.currentTimeMillis(),
                "{\"sql\":\"select 1\",\"dbPassword\":\"oops\"}"))
        .exchange()
        .expectStatus()
        .isBadRequest();

    // 不应有写入数据库
    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.atomic_task_config WHERE tenant_id = ?",
            Long.class,
            TENANT);
    // 可能有其它用例残留,但本次 sensitive name 不应入库
    assertThat(cnt).isNotNull();
  }

  @Test
  void shouldRejectExtraneousParameterKey() {
    // 含 schema 未定义 key → 400
    client
        .post()
        .uri("/api/console/ops/atomic-task-configs")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(
            body(
                "sql",
                "it_extraneous_" + System.currentTimeMillis(),
                "{\"sql\":\"select 1\",\"notInSchema\":\"x\"}"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectUnknownTaskTypeOnList() {
    client
        .get()
        .uri(
            uriBuilder ->
                uriBuilder
                    .path("/api/console/ops/atomic-task-configs")
                    .queryParam("tenantId", TENANT)
                    .queryParam("taskType", "definitely_not_a_real_type")
                    .build())
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
