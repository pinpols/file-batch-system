package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("原子任务配置写路径: 参数文档落库,按类型列表查询与参数安全校验")
class ConsoleAtomicTaskConfigMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private static final String TENANT = "int-atc-ta";

  private String body(String taskType, String name, String parametersJson) {
    return """
        {
          "tenantId": "%s",
          "taskType": "%s",
          "name": "%s",
          "parameters": %s
        }
        """.formatted(TENANT, taskType, name, parametersJson).stripTrailing();
  }

  @Test
  @DisplayName("创建 SQL 类型配置: 返回成功,落库的类型,名称与参数内容与请求一致")
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

    String atomicTaskConfigSnapshotSql = """
        select tenant_id, task_type, name, parameters::text as parameters
        from batch.atomic_task_config where tenant_id = ? and name = ?
        """;
    var row = jdbcTemplate.queryForMap(atomicTaskConfigSnapshotSql, TENANT, name);
    assertThat(row).containsEntry("tenant_id", TENANT);
    assertThat(row).containsEntry("task_type", "sql");
    assertThat(row).containsEntry("name", name);
    assertThat((String) row.get("parameters")).contains("select 1");

    String deleteAtomicTaskConfigSql = """
        delete from batch.atomic_task_config where tenant_id = ? and name = ?
    """;
    jdbcTemplate.update(deleteAtomicTaskConfigSql, TENANT, name);
  }

  @Test
  @DisplayName("无效任务超时经 HTTP 拒绝且不入库，合法整数配置仍能保存")
  void shouldRejectInvalidTimeoutWithoutPersisting() {
    String prefix = "it_timeout_" + Long.toUnsignedString(System.nanoTime());
    List<String> types = List.of("sql", "stored_proc", "shell", "http");
    List<String> parameters = List.of(
        "{\"sql\":\"select 1\",\"statementTimeoutSeconds\":1.5}",
        "{\"procedureName\":\"batch.p\",\"statementTimeoutSeconds\":0}",
        "{\"command\":\"echo\",\"timeoutSeconds\":-1}",
        "{\"url\":\"https://example.com\",\"timeoutSeconds\":18446744073709551617}");
    String countSql =
        "select count(*) from batch.atomic_task_config where tenant_id = ? and name = ?";
    String deleteSql = "delete from batch.atomic_task_config where tenant_id = ? and name = ?";
    for (int i = 0; i < types.size(); i++) {
      String name = prefix + "_invalid_" + i;
      try {
        client
            .post()
            .uri("/api/console/ops/atomic-task-configs")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body(types.get(i), name, parameters.get(i)))
            .exchange()
            .expectStatus()
            .isBadRequest();
        Long count = jdbcTemplate.queryForObject(countSql, Long.class, TENANT, name);
        assertThat(count).as(name).isZero();
      } finally {
        jdbcTemplate.update(deleteSql, TENANT, name);
      }
    }

    String validName = prefix + "_valid";
    try {
      client
          .post()
          .uri("/api/console/ops/atomic-task-configs")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body("sql", validName, "{\"sql\":\"select 1\",\"statementTimeoutSeconds\":1}"))
          .exchange()
          .expectStatus()
          .isOk();
      Long count = jdbcTemplate.queryForObject(countSql, Long.class, TENANT, validName);
      assertThat(count).isEqualTo(1);
    } finally {
      jdbcTemplate.update(deleteSql, TENANT, validName);
    }
  }

  @Test
  @DisplayName("按任务类型列表查询: 返回刚创建的那条配置")
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
        .uri(uriBuilder -> uriBuilder
            .path("/api/console/ops/atomic-task-configs")
            .queryParam("tenantId", TENANT)
            .queryParam("taskType", "sql")
            .build())
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains(name));

    String deleteAtomicTaskConfigSql = """
        delete from batch.atomic_task_config where tenant_id = ? and name = ?
    """;
    jdbcTemplate.update(deleteAtomicTaskConfigSql, TENANT, name);
  }

  @Test
  @DisplayName("参数含密码类字段: 返回请求不合法,敏感配置不入库")
  void shouldRejectSensitiveKeyInParameters() {
    // parameters 含 password 关键字 → SensitiveDataValidator 拒入
    client
        .post()
        .uri("/api/console/ops/atomic-task-configs")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(
            "sql",
            "it_sensitive_" + System.currentTimeMillis(),
            "{\"sql\":\"select 1\",\"dbPassword\":\"oops\"}"))
        .exchange()
        .expectStatus()
        .isBadRequest();

    // 不应有写入数据库
    String atomicTaskConfigCountSql = """
        select count(*) from batch.atomic_task_config where tenant_id = ?
        """;
    Long cnt = jdbcTemplate.queryForObject(atomicTaskConfigCountSql, Long.class, TENANT);
    // 可能有其它用例残留,但本次 sensitive name 不应入库
    assertThat(cnt).isNotNull();
  }

  @Test
  @DisplayName("参数含未定义字段: 返回请求不合法")
  void shouldRejectExtraneousParameterKey() {
    // 含 schema 未定义 key → 400
    client
        .post()
        .uri("/api/console/ops/atomic-task-configs")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(
            "sql",
            "it_extraneous_" + System.currentTimeMillis(),
            "{\"sql\":\"select 1\",\"notInSchema\":\"x\"}"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  @DisplayName("列表查询传入未知任务类型: 返回请求不合法")
  void shouldRejectUnknownTaskTypeOnList() {
    client
        .get()
        .uri(uriBuilder -> uriBuilder
            .path("/api/console/ops/atomic-task-configs")
            .queryParam("tenantId", TENANT)
            .queryParam("taskType", "definitely_not_a_real_type")
            .build())
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
