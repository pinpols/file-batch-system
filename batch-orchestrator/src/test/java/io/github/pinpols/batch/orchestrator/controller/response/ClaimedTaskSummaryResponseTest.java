package io.github.pinpols.batch.orchestrator.controller.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Worker 已认领任务响应只返回安全摘要")
class ClaimedTaskSummaryResponseTest {

  @Test
  @DisplayName("序列化不包含任务载荷、运行参数、结果正文或错误消息")
  void shouldExcludeTaskAndResultBodies() throws Exception {
    JobTaskEntity entity = new JobTaskEntity();
    entity.setId(12L);
    entity.setJobInstanceId(20L);
    entity.setJobPartitionId(30L);
    entity.setTaskType("ATOMIC");
    entity.setTaskSeq(1);
    entity.setTaskStatus("RUNNING");
    entity.setAssignedWorkerCode("worker-a");
    entity.setTaskPayload("private-task-payload");
    entity.setEffectiveParameters("private-effective-parameters");
    entity.setHeartbeatDetails("private-heartbeat-details");
    entity.setResultSummary("private-result-body");
    entity.setErrorCode("TASK_ERROR");
    entity.setErrorMessage("private-error-message");

    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    String json = objectMapper.writeValueAsString(ClaimedTaskSummaryResponse.from(entity));

    assertThat(json)
        .contains("\"id\":12", "\"taskStatus\":\"RUNNING\"", "\"errorCode\":\"TASK_ERROR\"")
        .doesNotContain(
            "taskPayload",
            "effectiveParameters",
            "heartbeatDetails",
            "resultSummary",
            "errorMessage",
            "private-");
  }
}
