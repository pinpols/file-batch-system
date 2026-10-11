package io.github.pinpols.batch.console.application.contract.request.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件通道配置传输字段约束")
class FileChannelSpecSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("导入接受通道配置但导出不返回敏感配置")
  void shouldOmitConfigFromExport_whenImportAcceptsConfigJson() throws Exception {
    String configJson = "{\"password\":\"must-not-be-exported\"}";
    String endpoint = "sftp://operator:must-not-be-exported@example.test/inbox?token=secret";
    String requestBody = objectMapper.writeValueAsString(Map.of(
        "channelCode", "settlement-sftp", "configJson", configJson, "targetEndpoint", endpoint));
    FileChannelSpec parsed = objectMapper.readValue(requestBody, FileChannelSpec.class);

    assertThat(parsed.getConfigJson()).isEqualTo(configJson);
    assertThat(parsed.getTargetEndpoint()).isEqualTo(endpoint);

    JsonNode exported = objectMapper.valueToTree(parsed);
    assertThat(exported.has("configJson")).isFalse();
    assertThat(exported.has("targetEndpoint")).isFalse();
    assertThat(exported.toString()).doesNotContain("must-not-be-exported").doesNotContain("secret");

    assertThat(JsonUtils.toMap(parsed)).doesNotContainKeys("configJson", "targetEndpoint");
  }
}
