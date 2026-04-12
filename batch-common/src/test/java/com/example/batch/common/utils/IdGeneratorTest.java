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
  void newBusinessNoShouldStartWithPrefixAndContainDashes() {
    String no = IdGenerator.newBusinessNo("JOB");
    assertThat(no).startsWith("JOB-");
    assertThat(no.chars().filter(c -> c == '-').count()).isGreaterThanOrEqualTo(2);
  }
}
