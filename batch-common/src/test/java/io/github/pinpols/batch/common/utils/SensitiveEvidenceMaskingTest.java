package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("只读证据脱敏: 自由文本、JSON 与端点凭据")
class SensitiveEvidenceMaskingTest {

  @Test
  @DisplayName("自由文本保留业务内容并遮蔽端点凭据")
  void shouldMaskEndpointInFreeText() {
    assertThat(SensitiveEvidenceMasking.maskText("调用 sftp://operator:raw@example.test/inbox 失败"))
        .contains("sftp://****@example.test/inbox")
        .doesNotContain("raw");
  }

  @Test
  @DisplayName("JSON 文本递归遮蔽敏感键和端点，普通字段保留")
  void shouldMaskJsonOrText() {
    String masked = SensitiveEvidenceMasking.maskJsonOrText(
        "{\"reason\":\"failed\",\"auth\":{\"password\":\"raw\"},"
            + "\"endpoint\":\"https://user:pass@example.test/run\"}");

    assertThat(masked)
        .contains("\"reason\":\"failed\"")
        .contains("\"password\":\"****\"")
        .contains("https://****@example.test/run")
        .doesNotContain("raw");
  }

  @Test
  @DisplayName("非法 JSON 按自由文本兜底，不原样返回凭据")
  void shouldFallbackToTextForInvalidJson() {
    assertThat(SensitiveEvidenceMasking.maskJsonOrText("{\"password\":\"raw\""))
        .doesNotContain("\"raw\"");
  }

  @Test
  @DisplayName("Map/List 动态负载递归遮蔽敏感键")
  void shouldMaskDynamicMetadata() {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("jobCode", "DAILY_PNL");
    metadata.put("apiToken", "raw-token");
    metadata.put("items", List.of(Map.of("clientSecret", "raw-secret")));

    Map<String, Object> masked = SensitiveEvidenceMasking.maskMetadata(metadata);

    assertThat(masked).containsEntry("jobCode", "DAILY_PNL");
    assertThat(masked).containsEntry("apiToken", SecretMasking.MASKED_VALUE);
    assertThat(masked.get("items").toString())
        .contains("clientSecret=" + SecretMasking.MASKED_VALUE)
        .doesNotContain("raw-secret");
  }
}
