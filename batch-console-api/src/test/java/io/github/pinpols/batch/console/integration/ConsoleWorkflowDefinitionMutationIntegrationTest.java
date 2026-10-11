package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/workflow-definitions → DB(workflow_definition + workflow_node +
 * workflow_edge)。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 DAG 全字段写入数据库,3 张表行一致
 *   <li>嵌套 nodes[].nodeCode 含空格 → 400(@Valid 下钻生效)
 *   <li>嵌套 edges[].fromNodeCode 含中文 → 400
 *   <li>workflowCode 重复 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("工作流定义写路径: DAG 三表落库,嵌套节点与边编码校验及唯一约束")
class ConsoleWorkflowDefinitionMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String workflowCode, String node1Code, String fromCode) {
    return """
        {
          "tenantId": "int-wf-ta",
          "workflowCode": "%s",
          "workflowName": "wf-it",
          "workflowType": "DAG",
          "enabled": false,
          "nodes": [
            {"nodeCode": "start", "nodeName": "start", "nodeType": "START"},
            {"nodeCode": "%s", "nodeName": "t1", "nodeType": "TASK"},
            {"nodeCode": "end", "nodeName": "end", "nodeType": "END"}
          ],
          "edges": [
            {"fromNodeCode": "%s", "toNodeCode": "%s"},
            {"fromNodeCode": "%s", "toNodeCode": "end"}
          ]
        }
        """.formatted(workflowCode, node1Code, fromCode, node1Code, node1Code)
        .stripTrailing();
  }

  @Test
  @DisplayName("创建 DAG 工作流: 定义,节点与边三张表分别落 1,3,2 行")
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

    Long defId = jdbcTemplate.queryForObject(
        "SELECT id FROM batch.workflow_definition WHERE workflow_code = ?", Long.class, wfCode);
    assertThat(defId).isNotNull();

    Long nodeCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.workflow_node WHERE workflow_definition_id = ?",
        Long.class,
        defId);
    assertThat(nodeCount).isEqualTo(3L);

    Long edgeCount = jdbcTemplate.queryForObject(
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
  @DisplayName("工作流敏感节点参数读取脱敏且掩码更新保留原凭据")
  void shouldMaskAndPreserveSensitiveNodeParams() {
    String workflowCode = "int_wf_secret_" + System.currentTimeMillis();
    Long definitionId = null;
    try {
      String createBody = body(workflowCode, "task1", "start")
          .replace(
              "\"nodeType\": \"TASK\"",
              "\"nodeType\": \"TASK\", \"nodeParams\": "
                  + "\"{\\\"apiKey\\\":\\\"workflow-secret\\\","
                  + "\\\"label\\\":\\\"old\\\"}\"");
      String created = client
          .post()
          .uri("/api/console/workflow-definitions")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-secret-" + workflowCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(createBody)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();

      definitionId = jdbcTemplate.queryForObject(
          "SELECT id FROM batch.workflow_definition WHERE tenant_id = ? AND workflow_code = ?",
          Long.class,
          "int-wf-ta",
          workflowCode);
      assertThat(created).contains("****").doesNotContain("workflow-secret");
      String detail = client
          .get()
          .uri(
              "/api/console/workflow-definitions/{id}?tenantId={tenantId}",
              definitionId,
              "int-wf-ta")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      assertThat(detail).contains("****").doesNotContain("workflow-secret");

      String updateBody = createBody.replace("workflow-secret", "****").replace("old", "new");
      String updated = client
          .put()
          .uri("/api/console/workflow-definitions/{id}", definitionId)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
              "idem-wf-secret-update-" + workflowCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(updateBody)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();

      assertThat(updated).contains("****").doesNotContain("workflow-secret");
      var storedParams = jdbcTemplate.queryForMap(
          "SELECT node_params->>'apiKey' AS api_key, node_params->>'label' AS label "
              + "FROM batch.workflow_node WHERE workflow_definition_id = ? AND node_code = ?",
          definitionId,
          "task1");
      assertThat(storedParams).containsEntry("api_key", "workflow-secret");
      assertThat(storedParams).containsEntry("label", "new");
    } finally {
      if (definitionId != null) {
        jdbcTemplate.update(
            "DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?", definitionId);
        jdbcTemplate.update(
            "DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?", definitionId);
        jdbcTemplate.update("DELETE FROM batch.workflow_definition WHERE id = ?", definitionId);
      }
    }
  }

  @Test
  @DisplayName("租户管理员不能在工作流节点上指定物理 Worker 分组")
  void shouldRejectTenantAdminAssigningNodeWorkerGroup() {
    String workflowCode = "int_wf_tenant_route_" + System.currentTimeMillis();
    String routedBody = body(workflowCode, "task1", "start")
        .replace("\"nodeType\": \"TASK\"", "\"nodeType\": \"TASK\", \"workerGroup\": \"IMPORT\"");

    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-route-" + workflowCode)
        .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(routedBody)
        .exchange()
        .expectStatus()
        .isForbidden();

    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.workflow_definition WHERE tenant_id = ? AND workflow_code = ?",
        Long.class,
        "int-wf-ta",
        workflowCode);
    assertThat(count).isZero();
  }

  @Test
  @DisplayName("租户管理员更新工作流时省略物理路由字段不清除平台分配")
  void shouldPreserveNodeWorkerGroup_whenTenantAdminUpdatesWorkflow() {
    String workflowCode = "int_wf_route_preserve_" + System.currentTimeMillis();
    String routedBody = body(workflowCode, "task1", "start")
        .replace("\"nodeType\": \"TASK\"", "\"nodeType\": \"TASK\", \"workerGroup\": \"IMPORT\"");
    client
        .post()
        .uri("/api/console/workflow-definitions")
        .header(
            CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-wf-route-seed-" + workflowCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(routedBody)
        .exchange()
        .expectStatus()
        .isOk();

    Long definitionId = jdbcTemplate.queryForObject(
        "SELECT id FROM batch.workflow_definition WHERE tenant_id = ? AND workflow_code = ?",
        Long.class,
        "int-wf-ta",
        workflowCode);
    try {
      client
          .put()
          .uri("/api/console/workflow-definitions/{id}", definitionId)
          .header(
              CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER,
              "idem-wf-route-update-" + workflowCode)
          .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
          .header(CommonConstants.DEFAULT_TENANT_ID_HEADER, "int-wf-ta")
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(body(workflowCode, "task1", "start"))
          .exchange()
          .expectStatus()
          .isOk();

      String workerGroup = jdbcTemplate.queryForObject(
          "SELECT worker_group FROM batch.workflow_node WHERE workflow_definition_id = ? AND node_code = ?",
          String.class,
          definitionId,
          "task1");
      assertThat(workerGroup).isEqualTo("IMPORT");
    } finally {
      jdbcTemplate.update(
          "DELETE FROM batch.workflow_edge WHERE workflow_definition_id = ?", definitionId);
      jdbcTemplate.update(
          "DELETE FROM batch.workflow_node WHERE workflow_definition_id = ?", definitionId);
      jdbcTemplate.update("DELETE FROM batch.workflow_definition WHERE id = ?", definitionId);
    }
  }

  @Test
  @DisplayName("嵌套节点编码含空格: 返回校验错误且不落库")
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

    // 守护:异常数据不入库
    Long cnt = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.workflow_definition WHERE workflow_code = ?",
        Long.class,
        "int_wf_bad_node");
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("嵌套边起点编码为中文: 返回请求不合法")
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
  @DisplayName("工作流编码重复: 第二次写入失败,不产生重复定义")
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
    Long defId = jdbcTemplate.queryForObject(
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
  @DisplayName("工作流编码含空格: 返回请求不合法")
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
