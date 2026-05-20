package com.example.batch.common.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IdGeneratorTest {

  @Test
  void newTraceIdShouldBe32HexChars() {
    String id = IdGenerator.newTraceId();
    assertThat(id).hasSize(32).matches("[0-9a-f]+");
  }

  @Test
  void newInvocationIdShouldBe32HexChars() {
    String id = IdGenerator.newInvocationId();
    assertThat(id).hasSize(32).matches("[0-9a-f]+");
  }

  @Test
  void newBusinessNoShouldStartWithPrefixAndContainDashes() {
    String no = IdGenerator.newBusinessNo("JOB");
    assertThat(no).startsWith("JOB-");
    assertThat(no.chars().filter(c -> c == '-').count()).isGreaterThanOrEqualTo(2);
  }

  @Test
  void newBusinessNoShouldUseCompactTimestampWithoutColonOrDash() {
    String no = IdGenerator.newBusinessNo("JOB");
    String middle = no.split("-")[1];
    assertThat(middle).matches("\\d{8}T\\d{6}Z");
  }

  @Test
  void newBusinessNoBatchShouldShareTimestampAcrossIds() {
    java.util.List<String> ids = IdGenerator.newBusinessNoBatch("PART", 5);
    assertThat(ids).hasSize(5);
    String ts0 = ids.get(0).split("-")[1];
    for (String id : ids) {
      assertThat(id).startsWith("PART-").contains(ts0);
    }
    // 后缀应彼此不同
    assertThat(ids.stream().map(s -> s.split("-")[2]).distinct().count()).isEqualTo(5);
  }

  @Test
  void newBusinessNoBatchShouldRejectNonPositiveCount() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> IdGenerator.newBusinessNoBatch("X", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(">= 1");
  }

  @Test
  void newIdempotencyKeyShouldBeIdemPrefixedHex() {
    String k = IdGenerator.newIdempotencyKey();
    assertThat(k).startsWith("idem-");
    assertThat(k.substring(5)).hasSize(32).matches("[0-9a-f]+");
  }

  @Test
  void newIdempotencyKeyShouldBeUnique() {
    java.util.Set<String> keys = new java.util.HashSet<>();
    for (int i = 0; i < 1000; i++) {
      keys.add(IdGenerator.newIdempotencyKey());
    }
    assertThat(keys).hasSize(1000);
  }
}
