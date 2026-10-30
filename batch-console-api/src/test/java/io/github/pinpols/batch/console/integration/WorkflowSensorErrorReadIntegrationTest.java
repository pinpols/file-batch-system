package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Workflow 传感器错误的 Console 回读")
class WorkflowSensorErrorReadIntegrationTest extends AbstractMutationIntegrationTest {
  private final MessageSource messages;

  @Autowired
  WorkflowSensorErrorReadIntegrationTest(MessageSource messages) {
    this.messages = messages;
  }

  @Test
  @DisplayName("节点详情按请求语言渲染已净化的错误参数并隔离租户")
  void shouldRenderSanitizedSensorErrorInBothLocales() throws Exception {
    assertThat(messages.getMessage(
            "error.workflow.sensor_probe_failed", new Object[] {"TYPE", "DETAIL"}, Locale.US))
        .as("message source %s", messages.getClass().getName())
        .contains("sensor probe failed");
    String tenant = "sensor-error-" + System.nanoTime();
    String insertDefinition = """
        insert into batch.workflow_definition (tenant_id,workflow_code,workflow_name,workflow_type,version,enabled)
        values (?, 'SENSOR', 'Sensor', 'DAG', 1, true) returning id
        """;
    Long definition = jdbcTemplate.queryForObject(insertDefinition, Long.class, tenant);
    String snapshot = "{\"schemaVersion\":2,\"workflowDefinitionId\":" + definition
        + ",\"workflowVersion\":1,\"nodes\":[{\"nodeCode\":\"WAIT\",\"nodeType\":\"WAIT\"}],\"edges\":[]}";
    String insertRun = """
        insert into batch.workflow_run (tenant_id,workflow_definition_id,workflow_version_snapshot,run_status,biz_date,execution_snapshot)
        values (?, ?, 1, 'FAILED', current_date, ?::jsonb) returning id
        """;
    Long run = jdbcTemplate.queryForObject(insertRun, Long.class, tenant, definition, snapshot);
    String insertNode = """
        insert into batch.workflow_node_run (workflow_run_id,node_code,node_type,run_seq,node_status,error_code,error_key,error_args)
        values (?, 'WAIT', 'WAIT', 1, 'FAILED', 'SENSOR_FAIL', 'error.workflow.sensor_probe_failed',
          '["DB_ROW_EXISTS","db failed password=****"]'::jsonb) returning id
        """;
    Long node = jdbcTemplate.queryForObject(insertNode, Long.class, run);
    ObjectMapper json = new ObjectMapper();
    for (String language : new String[] {"en-US", "zh-CN"}) {
      String body = client
          .get()
          .uri(builder -> builder
              .path("/api/console/queries/workflow-node-runs/{id}")
              .queryParam("tenantId", tenant)
              .build(node))
          .header("Accept-Language", language)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      JsonNode data = json.readTree(body).path("data");
      assertThat(data.path("nodeStatus").asText()).isEqualTo("FAILED");
      assertThat(data.path("errorCode").asText()).isEqualTo("SENSOR_FAIL");
      assertThat(data.path("errorMessage").asText())
          .contains("DB_ROW_EXISTS", "password=****")
          .doesNotContain("sample-secret");
      if (language.equals("en-US"))
        assertThat(data.path("errorMessage").asText()).contains("sensor probe failed");
      else assertThat(data.path("errorMessage").asText()).contains("探测失败");
    }
    client
        .get()
        .uri(builder -> builder
            .path("/api/console/queries/workflow-node-runs/{id}")
            .queryParam("tenantId", tenant + "-other")
            .build(node))
        .exchange()
        .expectStatus()
        .is4xxClientError();
  }
}
