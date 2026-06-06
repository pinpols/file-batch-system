package com.example.batch.common.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BatchFileConstantsPartitionTagTest {

  @Test
  void shouldInsertTagBeforeExtension() {
    assertThat(BatchFileConstants.insertPartitionTag("data.csv", 2, 4)).isEqualTo("data_p2of4.csv");
  }

  @Test
  void shouldInsertTagInPathFileName() {
    assertThat(BatchFileConstants.insertPartitionTag("outbound/a/b/data.csv", 1, 3))
        .isEqualTo("outbound/a/b/data_p1of3.csv");
  }

  @Test
  void shouldAppendTag_whenNoExtension() {
    assertThat(BatchFileConstants.insertPartitionTag("noext", 2, 4)).isEqualTo("noext_p2of4");
  }

  @Test
  void shouldReturnUnchanged_whenSinglePartition() {
    assertThat(BatchFileConstants.insertPartitionTag("data.csv", 1, 1)).isEqualTo("data.csv");
  }
}
