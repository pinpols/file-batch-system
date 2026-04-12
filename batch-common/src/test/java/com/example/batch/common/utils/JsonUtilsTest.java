package com.example.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonUtilsTest {

  @Test
  @SuppressWarnings("unchecked")
  void shouldRoundTripMap() {
    Map<String, Object> original = Map.of("k", "v", "n", 1);
    String json = JsonUtils.toJson(original);
    Map<String, Object> parsed = JsonUtils.fromJson(json, Map.class);
    assertThat(parsed.get("k")).isEqualTo("v");
    assertThat(parsed.get("n")).isEqualTo(1);
  }

  @Test
  void shouldThrowOnInvalidJson() {
    assertThatThrownBy(() -> JsonUtils.fromJson("{", Map.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Failed to parse JSON");
  }
}
