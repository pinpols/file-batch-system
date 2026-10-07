package io.github.pinpols.batch.console.support.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("查询缓存键片段: 安全字符原样保留与超长取值哈希收敛")
class ConsoleQueryCacheServiceTest {

  @Test
  @DisplayName("短且安全的取值原样保留, 便于人工排查")
  void keySegment_keepsShortSafeValuesReadable() {
    assertThat(ConsoleQueryCacheService.keySegment("ta-prod_1")).isEqualTo("ta-prod_1");
  }

  @Test
  @DisplayName("取值中的分隔符被替换为下划线, 并追加 16 位哈希后缀")
  void keySegment_replacesSeparatorsAndAddsHashForChangedValues() {
    String segment = ConsoleQueryCacheService.keySegment("ta:prod/east");

    assertThat(segment)
        .startsWith("ta_prod_east~")
        .doesNotContain(":")
        .hasSize("ta_prod_east~".length() + 16);
  }

  @Test
  @DisplayName("超长取值经哈希收敛后长度不超过 81 个字符")
  void keySegment_boundsLongValuesWithHash() {
    String segment = ConsoleQueryCacheService.keySegment("tenant".repeat(40));

    assertThat(segment).contains("~").hasSizeLessThanOrEqualTo(64 + 1 + 16);
  }
}
