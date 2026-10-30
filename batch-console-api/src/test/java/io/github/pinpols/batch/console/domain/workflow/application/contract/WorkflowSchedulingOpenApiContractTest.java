package io.github.pinpols.batch.console.domain.workflow.application.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.response.ops.ConsoleTriggerActionResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsoleWorkflowNodeRunResponse;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Workflow 调度契约：公开响应字段和强制版本与 OpenAPI 对齐")
class WorkflowSchedulingOpenApiContractTest {

  @ParameterizedTest
  @MethodSource("responseSchemas")
  @DisplayName("节点运行和调度动作响应不得在生成类型中丢字段")
  void shouldExposeEveryResponseField_whenGeneratingPublicSchemas(
      Class<?> responseType, String schemaName) throws IOException {
    JsonNode properties = schemas().path(schemaName).path("properties");
    List<String> publishedFields = new ArrayList<>();
    properties.fieldNames().forEachRemaining(publishedFields::add);
    List<String> responseFields = Arrays.stream(responseType.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();

    assertThat(publishedFields).containsExactlyInAnyOrderElementsOf(responseFields);
  }

  @Test
  @DisplayName("全量保存必须声明版本必填，不允许客户端生成可缺省版本的请求")
  void shouldRequireExpectedVersion_whenPublishingFullUpdate() throws IOException {
    JsonNode required = schemas().path("WorkflowDefinitionFullUpdateRequest").path("required");
    List<String> requiredFields = new ArrayList<>();
    required.forEach(field -> requiredFields.add(field.asText()));

    assertThat(requiredFields).containsExactlyInAnyOrder("definition", "expectedVersion");
  }

  @Test
  @DisplayName("四个调度操作路由必须引用现有的固定动作响应")
  void shouldReferenceActionSchema_whenPublishingTriggerOperations() throws IOException {
    JsonNode document = openApi();
    for (String operation : List.of("register", "unregister", "pause", "resume")) {
      String path = "/api/console/ops/triggers/{workflowCode}/" + operation;
      String responseRef = document
          .path("paths")
          .path(path)
          .path("post")
          .path("responses")
          .path("200")
          .path("content")
          .path("application/json")
          .path("schema")
          .path("$ref")
          .asText();
      assertThat(responseRef).isEqualTo("#/components/schemas/CommonResponseTriggerAction");
    }
    String payloadRef = document
        .path("components")
        .path("schemas")
        .path("CommonResponseTriggerAction")
        .path("allOf")
        .path(1)
        .path("properties")
        .path("data")
        .path("$ref")
        .asText();
    assertThat(payloadRef).isEqualTo("#/components/schemas/TriggerActionResponse");
  }

  private static Stream<Arguments> responseSchemas() {
    return Stream.of(
        Arguments.of(ConsoleWorkflowNodeRunResponse.class, "ConsoleWorkflowNodeRunResponse"),
        Arguments.of(ConsoleTriggerActionResponse.class, "TriggerActionResponse"));
  }

  private static JsonNode schemas() throws IOException {
    return openApi().path("components").path("schemas");
  }

  private static JsonNode openApi() throws IOException {
    for (Path current = Path.of("").toAbsolutePath();
        EmptyChecks.isNotNull(current);
        current = current.getParent()) {
      Path schema = current.resolve("docs/api/console-api.openapi.yaml");
      if (Files.isRegularFile(schema)) {
        return new YAMLMapper().readTree(Files.readString(schema));
      }
    }
    throw new IllegalStateException("找不到 Console OpenAPI 权威文件");
  }
}
