package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("分区与 Workflow 节点游标：真实 HTTP 和 PostgreSQL")
class ExecutionCursorOrderIntegrationTest extends AbstractMutationIntegrationTest {
  @ParameterizedTest
  @ValueSource(strings = {"partitions", "workflow-node-runs"})
  @DisplayName("业务排序与 ID 顺序不同的游标分页不应漏数据")
  void shouldNotSkipRows_whenBusinessOrderDiffersFromIdOrder(String resource) throws Exception {
    String tenant = "exec-cursor-" + Long.toUnsignedString(System.nanoTime());
    List<Long> expected =
        "partitions".equals(resource) ? seedPartitions(tenant) : seedNodes(tenant);
    ObjectMapper json = new ObjectMapper();
    List<Long> actual = new ArrayList<>();
    String token = "";
    for (int page = 0; page < 4; page++) {
      String cursor = token;
      String body = client
          .get()
          .uri(builder -> builder
              .path("/api/console/queries/" + resource)
              .queryParam("tenantId", tenant)
              .queryParam("pageSize", 1)
              .queryParam("cursor", cursor)
              .build())
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      JsonNode data = json.readTree(body).path("data");
      for (JsonNode item : data.path("items")) actual.add(item.path("id").asLong());
      JsonNode next = data.path("nextCursor");
      if (next.isNull() || next.isMissingNode()) break;
      token = next.asText();
    }
    assertThat(actual).containsExactlyElementsOf(expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"partitions", "workflow-node-runs"})
  @DisplayName("普通分页保留业务排序且两种分页都隔离租户")
  void shouldPreserveOffsetOrderAndTenantIsolation(String resource) throws Exception {
    String tenant = "exec-order-" + Long.toUnsignedString(System.nanoTime());
    List<Long> expected =
        "partitions".equals(resource) ? seedPartitions(tenant) : seedNodes(tenant);
    if ("partitions".equals(resource)) seedPartitions(tenant + "-other");
    else seedNodes(tenant + "-other");
    ObjectMapper json = new ObjectMapper();
    for (boolean cursorMode : List.of(false, true)) {
      String body = client
          .get()
          .uri(builder -> {
            builder
                .path("/api/console/queries/" + resource)
                .queryParam("tenantId", tenant)
                .queryParam("pageSize", 1);
            if (cursorMode) builder.queryParam("cursor", "");
            return builder.build();
          })
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      JsonNode items = json.readTree(body).path("data").path("items");
      assertThat(items.size()).isEqualTo(1);
      assertThat(items.get(0).path("id").asLong())
          .isEqualTo(cursorMode ? expected.getFirst() : expected.getLast());
    }
  }

  private List<Long> seedPartitions(String tenant) {
    String insertDefinitionSql = """
        insert into batch.job_definition (tenant_id, job_code, job_name, job_type, schedule_type, timezone)
        values (?, 'cursor_job', 'cursor job', 'GENERAL', 'MANUAL', 'UTC') returning id
        """;
    Long definition = jdbcTemplate.queryForObject(insertDefinitionSql, Long.class, tenant);
    String insertInstanceSql = """
        insert into batch.job_instance (tenant_id,job_definition_id,job_code,instance_no,biz_date,
            trigger_type,instance_status,dedup_key)
        values (?, ?, 'cursor_job', 'cursor-instance', current_date, 'MANUAL', 'RUNNING', 'cursor-dedup') returning id
        """;
    Long instance = jdbcTemplate.queryForObject(insertInstanceSql, Long.class, tenant, definition);
    List<Long> ids = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String insertPartitionSql = """
          insert into batch.job_partition (tenant_id,job_instance_id,job_instance_biz_date,partition_no,partition_status)
          values (?, ?, current_date, ?, 'READY') returning id
          """;
      Long id = jdbcTemplate.queryForObject(insertPartitionSql, Long.class, tenant, instance, i);
      ids.addFirst(id);
    }
    return ids;
  }

  private List<Long> seedNodes(String tenant) {
    String insertDefinitionSql = """
        insert into batch.workflow_definition (tenant_id,workflow_code,workflow_name,workflow_type,version,enabled)
        values (?, 'cursor_workflow', 'cursor workflow', 'DAG', 1, true) returning id
        """;
    Long definition = jdbcTemplate.queryForObject(insertDefinitionSql, Long.class, tenant);
    String snapshot = "{\"schemaVersion\":2,\"workflowDefinitionId\":" + definition
        + ",\"workflowVersion\":1,\"nodes\":[{\"nodeCode\":\"A\",\"nodeType\":\"JOB\"}],\"edges\":[]}";
    String insertWorkflowRunSql = """
        insert into batch.workflow_run (tenant_id,workflow_definition_id,workflow_version_snapshot,run_status,biz_date,execution_snapshot)
        values (?, ?, 1, 'RUNNING', current_date, ?::jsonb) returning id
        """;
    Long run =
        jdbcTemplate.queryForObject(insertWorkflowRunSql, Long.class, tenant, definition, snapshot);
    List<Long> ids = new ArrayList<>();
    for (int i = 3; i >= 1; i--) {
      String insertNodeRunSql = """
          insert into batch.workflow_node_run (workflow_run_id,node_code,node_type,run_seq,node_status)
          values (?, 'A', 'JOB', ?, 'READY') returning id
          """;
      Long id = jdbcTemplate.queryForObject(insertNodeRunSql, Long.class, run, i);
      ids.addFirst(id);
    }
    return ids;
  }
}
