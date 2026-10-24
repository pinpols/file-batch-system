package io.github.pinpols.batch.orchestrator.application.service.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.common.enums.SystemPipelineTaskKeys;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.task.OrchestratorJobMappers;
import io.github.pinpols.batch.orchestrator.application.workflow.WorkflowParamResolver;
import io.github.pinpols.batch.orchestrator.mapper.FileRecordLookupMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Workflow 节点载荷固定系统 Pipeline 身份")
class WorkflowNodePayloadBuilderSystemPipelineTest {

  private final WorkflowNodePayloadBuilder builder = new WorkflowNodePayloadBuilder(
      mock(OrchestratorJobMappers.class),
      mock(OrchestratorWorkflowMappers.class),
      mock(WorkflowParamResolver.class),
      mock(FileRecordLookupMapper.class));

  @DisplayName("文件 Worker 固定系统 Pipeline 编码与版本")
  @Test
  void shouldPinSystemPipelineCodeAndVersionForFileWorker() {
    String payload =
        builder.pinSystemPipelineIdentity("{\"targetJobCode\":\"IMPORT_A\"}", "IMPORT");

    Map<?, ?> parsed = JsonUtils.fromJson(payload, Map.class);
    assertThat(parsed.get(SystemPipelineTaskKeys.CODE)).isEqualTo("IMPORT");
    assertThat(parsed.get(SystemPipelineTaskKeys.VERSION)).isEqualTo(1);
    assertThat(parsed.get("targetJobCode")).isEqualTo("IMPORT_A");
  }

  @DisplayName("非文件 Worker 不生成文件 Pipeline 身份")
  @Test
  void shouldNotInventFilePipelineForNonFileWorker() {
    Map<?, ?> parsed = JsonUtils.fromJson(
        builder.pinSystemPipelineIdentity("{\"targetJobCode\":\"GENERAL_A\"}", "GENERAL"),
        Map.class);

    assertThat(parsed.containsKey(SystemPipelineTaskKeys.CODE)).isFalse();
    assertThat(parsed.containsKey(SystemPipelineTaskKeys.VERSION)).isFalse();
  }
}
