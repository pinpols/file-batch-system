package io.github.pinpols.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("JSON 工具: 序列化往返保真与非法输入的错误处理")
class JsonUtilsTest {

  @Test
  @DisplayName("映射序列化往返: 键与值原样保留")
  @SuppressWarnings("unchecked")
  void shouldRoundTripMap() {
    Map<String, Object> original = Map.of("k", "v", "n", 1);
    String json = JsonUtils.toJson(original);
    Map<String, Object> parsed = JsonUtils.fromJson(json, Map.class);
    assertThat(parsed).containsEntry("k", "v");
    assertThat(parsed).containsEntry("n", 1);
  }

  @Test
  @DisplayName("非法 JSON 文本: 抛出异常并提示解析失败")
  void shouldThrowOnInvalidJson() {
    assertThatThrownBy(() -> JsonUtils.fromJson("{", Map.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Failed to parse JSON");
  }
}
