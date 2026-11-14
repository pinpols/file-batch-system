package io.github.pinpols.batch.console.application.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.pinpols.batch.console.domain.file.application.contract.response.ConsoleFilePipelineResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobDefinitionResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleJobStepInstanceResponse;
import io.github.pinpols.batch.console.domain.notification.application.contract.response.ConsoleAlertRoutingResponse;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleTenantResponse;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@DisplayName("Console 公开响应与 OpenAPI schema 字段一致")
class ConsoleResponseOpenApiSchemaTest {

  @ParameterizedTest
  @MethodSource("responseSchemas")
  @DisplayName("响应 record 字段与 OpenAPI properties 完全一致, 文档不得漏字段或多字段")
  void shouldMatchOpenApiProperties(Class<?> responseType, String schemaName) throws IOException {
    JsonNode properties = schemas().path(schemaName).path("properties");
    List<String> documentedFields = new ArrayList<>();
    properties.fieldNames().forEachRemaining(documentedFields::add);
    List<String> serializedFields = Arrays.stream(responseType.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();

    assertThat(documentedFields)
        .as("%s 与 %s 字段漂移", schemaName, responseType.getSimpleName())
        .containsExactlyInAnyOrderElementsOf(serializedFields);
  }

  private static Stream<Arguments> responseSchemas() {
    return Stream.of(
        Arguments.of(ConsoleAlertRoutingResponse.class, "ConsoleAlertRoutingResponse"),
        Arguments.of(ConsoleFilePipelineResponse.class, "ConsoleFilePipelineResponse"),
        Arguments.of(ConsoleJobDefinitionResponse.class, "ConsoleJobDefinitionResponse"),
        Arguments.of(ConsoleJobStepInstanceResponse.class, "ConsoleJobStepInstanceResponse"),
        Arguments.of(ConsoleTenantResponse.class, "ConsoleTenantResponse"));
  }

  private static JsonNode schemas() throws IOException {
    return openApi().path("components").path("schemas");
  }

  private static JsonNode openApi() throws IOException {
    for (Path current = Path.of("").toAbsolutePath();
        current != null;
        current = current.getParent()) {
      Path schema = current.resolve("docs/api/console-api.openapi.yaml");
      if (Files.isRegularFile(schema)) {
        return new YAMLMapper().readTree(Files.readString(schema));
      }
    }
    throw new IllegalStateException("找不到 Console OpenAPI 权威文件");
  }
}
