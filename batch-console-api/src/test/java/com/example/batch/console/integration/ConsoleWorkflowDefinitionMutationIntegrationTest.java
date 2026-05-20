package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 写路径端到端集成测试:POST /api/console/workflow-definitions → DB(workflow_definition + workflow_node +
 * workflow_edge)。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 DAG 全字段落库,3 张表行一致
 *   <li>嵌套 nodes[].nodeCode 含空格 → 400(@Valid 下钻生效)
 *   <li>嵌套 edges[].fromNodeCode 含中文 → 400
 *   <li>workflowCode 重复 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleWorkflowDefinitionMutationIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private JdbcTemplate jdbcTemplate;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
  }

  private String body(String workflowCode, String node1Code, String fromCode) {
    return "{"
        + "\"tenantId\":\"int-wf-ta\","
        + "\"workflowCode\":\""
        + workflowCode
        + "\","
        + "\"workflowName\":\"wf-it\","
        + "\"workflowType\":\"DAG\","
        + "\"enabled\":false,"
        + "\"nodes\":["
        + "{\"nodeCode\":\"start\",\"nodeName\":\"start\",\"nodeType\":\"START\"},"
        + "{\"nodeCode\":\""
        + node1Code
        + "\",\"nodeName\":\"t1\",\"nodeType\":\"TASK\"},"
        + "{\"nodeCode\":\"end\",\"nodeName\":\"end\",\"nodeType\":\"END\"}"
        + "],\"edges\":["
        + "{\"fromNodeCode\":\""
        + fromCode
        + "\",\"toNodeCode\":\""
        + node1Code
        + "\"},"
        + "{\"fromNodeCode\":\""
        + node1Code
        + "\",\"toNodeCode\":\"end\"}"
        + "]}";
  }

  @Test
  void shouldCreateWorkflowWithAllThreeTables() {
    String wfCode = "int_wf_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-" + wfCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(wfCode, "task1", "start"))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("\"code\":\"SUCCESS\""));

    Long defId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM batch.workflow_definition WHERE workflow_code = ?", Long.class, wfCode);
    assertThat(defId).isNotNull();

    Long nodeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.workflow_node WHERE workflow_definition_id = ?",
            Long.class,
            defId);
    assertThat(nodeCount).isEqualTo(3L);

    Long edgeCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.workflow_edge WHERE workflow_definition_id = ?",
            Long.class,
            defId);
    assertThat(edgeCount).isEqualTo(2L);

    // 清理(按外键反向顺序)
    jdbcTemplate.update("DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?", defId);
    jdbcTemplate.update("DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?", defId);
    jdbcTemplate.update("DELETE FROM batch.workflow_definition WHERE id = ?", defId);
  }

  @Test
  void shouldRejectNestedInvalidNodeCode() {
    // 嵌套 nodes[].nodeCode 含空格 → @Valid 下钻 → 400
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("int_wf_bad_node", "bad node", "start"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));

    // 守护:脏数据不入库
    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.workflow_definition WHERE workflow_code = ?",
            Long.class,
            "int_wf_bad_node");
    assertThat(cnt).isZero();
  }

  @Test
  void shouldRejectNestedChineseEdgeNodeCode() {
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("int_wf_bad_edge", "task1", "中文"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectDuplicateWorkflowCode() {
    String wfCode = "int_wf_dup_" + System.currentTimeMillis();
    // 首次成功
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-dup-1-" + wfCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(wfCode, "task1", "start"))
        .exchange()
        .expectStatus()
        .isOk();

    // 重复创建 → 撞 (tenant_id, workflow_code) 唯一约束
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-dup-2-" + wfCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(wfCode, "task1", "start"))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    // 清理
    Long defId =
        jdbcTemplate.queryForObject(
            "SELECT id FROM batch.workflow_definition WHERE workflow_code = ?", Long.class, wfCode);
    if (defId != null) {
      jdbcTemplate.update(
          "DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?", defId);
      jdbcTemplate.update(
          "DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?", defId);
      jdbcTemplate.update("DELETE FROM batch.workflow_definition WHERE id = ?", defId);
    }
  }

  @Test
  void shouldRejectInvalidWorkflowCode() {
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-bad-code")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q", "task1", "start"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
