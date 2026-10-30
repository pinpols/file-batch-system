package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("补跑审批列表:真实 HTTP 与 PostgreSQL 分页")
class PendingCatchUpApprovalPagingIntegrationTest extends AbstractMutationIntegrationTest {
  @Test
  @DisplayName("同一请求多张审批单时,普通分页和游标分页均返回两个请求")
  void shouldReturnEachRequestOnce_whenApprovalsRepeat() throws Exception {
    String tenant = "catchup-http-" + System.nanoTime();
    seedRequest(tenant, "WF_OLD", "old-request");
    seedRequest(tenant, "WF_NEW", "new-request");
    String insert = """
        insert into batch.approval_command
          (tenant_id, approval_no, approval_type, action_type, target_type, target_id,
           payload_json, approval_status, source_idempotency_key)
        values (?, ?, 'CATCH_UP', 'CATCH_UP', 'CATCH_UP', 'new-request', '{}'::jsonb, ?, ?)
        """;
    jdbcTemplate.update(insert, tenant, "old-approval", "REJECTED", "old-key");
    jdbcTemplate.update(insert, tenant, "new-approval", "PENDING", "new-key");
    jdbcTemplate.update(insert, tenant + "-other", "foreign", "PENDING", "foreign-key");
    ObjectMapper json = new ObjectMapper();
    JsonNode offset = json.readTree(query(tenant, null, 2)).path("data").path("items");
    assertThat(offset.size()).isEqualTo(2);
    assertThat(offset.get(0).path("requestId").asText()).isEqualTo("new-request");
    assertThat(offset.get(0).path("approvalNo").asText()).isEqualTo("new-approval");
    assertThat(offset.get(1).path("requestId").asText()).isEqualTo("old-request");
    JsonNode first = json.readTree(query(tenant, "", 1)).path("data");
    assertThat(first.path("items").get(0).path("approvalNo").asText()).isEqualTo("new-approval");
    String next = first.path("nextCursor").asText();
    assertThat(next).isNotBlank();
    JsonNode second = json.readTree(query(tenant, next, 1)).path("data").path("items");
    assertThat(second.size()).isEqualTo(1);
    assertThat(second.get(0).path("requestId").asText()).isEqualTo("old-request");
  }

  private String query(String tenant, String cursor, int pageSize) {
    return client
        .get()
        .uri(builder -> {
          builder
              .path("/api/console/queries/catch-up-approvals")
              .queryParam("tenantId", tenant)
              .queryParam("pageSize", pageSize);
          if (cursor != null) builder.queryParam("cursor", cursor);
          return builder.build();
        })
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  private void seedRequest(String tenant, String workflowCode, String requestId) {
    String insertWorkflowSql = """
        insert into batch.workflow_definition
          (tenant_id,workflow_code,workflow_name,workflow_type,calendar_code)
        values (?, ?, ?, 'DAG', 'DEFAULT') returning id
        """;
    Long workflowId = jdbcTemplate.queryForObject(
        insertWorkflowSql, Long.class, tenant, workflowCode, workflowCode);
    String insertRequestSql = """
        insert into batch.trigger_request
          (tenant_id,request_id,trigger_type,workflow_definition_id,biz_date,dedup_key,request_status)
        values (?, ?, 'CATCH_UP', ?, current_date, ?, 'ACCEPTED')
        """;
    jdbcTemplate.update(insertRequestSql, tenant, requestId, workflowId, requestId);
  }
}
