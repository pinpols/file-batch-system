package com.example.batch.console.support.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConsoleQueryCacheServiceTest {

  @Test
  void keySegment_keepsShortSafeValuesReadable() {
    assertThat(ConsoleQueryCacheService.keySegment("ta-prod_1")).isEqualTo("ta-prod_1");
  }

  @Test
  void keySegment_replacesSeparatorsAndAddsHashForChangedValues() {
    String segment = ConsoleQueryCacheService.keySegment("ta:prod/east");

    assertThat(segment).startsWith("ta_prod_east~");
    assertThat(segment).doesNotContain(":");
    assertThat(segment).hasSize("ta_prod_east~".length() + 16);
  }

  @Test
  void keySegment_boundsLongValuesWithHash() {
    String segment = ConsoleQueryCacheService.keySegment("tenant".repeat(40));

    assertThat(segment).contains("~");
    assertThat(segment).hasSizeLessThanOrEqualTo(64 + 1 + 16);
  }
}
