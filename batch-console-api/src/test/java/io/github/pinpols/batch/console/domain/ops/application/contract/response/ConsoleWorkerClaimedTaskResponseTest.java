package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Console Worker 任务响应对上游新增字段保持最小暴露")
class ConsoleWorkerClaimedTaskResponseTest {

  @Test
  @DisplayName("上游载荷字段可被忽略且不会出现在 Console JSON")
  void shouldIgnoreAndNotSerializeSensitiveUpstreamFields() throws Exception {
    String upstreamJson = """
        {"id":12,"jobInstanceId":20,"jobPartitionId":30,"taskType":"ATOMIC",
         "taskSeq":1,"taskStatus":"RUNNING","assignedWorkerCode":"worker-a",
         "errorCode":"TASK_ERROR","taskPayload":"private-task-payload",
         "effectiveParameters":"private-effective-parameters",
         "heartbeatDetails":"private-heartbeat-details","resultSummary":"private-result-body",
         "errorMessage":"private-error-message"}
        """;
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    ConsoleWorkerClaimedTaskResponse response =
        objectMapper.readValue(upstreamJson, ConsoleWorkerClaimedTaskResponse.class);
    String consoleJson = objectMapper.writeValueAsString(response);

    assertThat(response.id()).isEqualTo(12L);
    assertThat(consoleJson)
        .contains("\"taskStatus\":\"RUNNING\"")
        .doesNotContain(
            "taskPayload",
            "effectiveParameters",
            "heartbeatDetails",
            "resultSummary",
            "errorMessage",
            "private-");
  }
}
