package io.github.pinpols.batch.common.constants;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件分区标签:扩展名前插入、路径文件名插入、无扩展名追加与单分区短路")
class BatchFileConstantsPartitionTagTest {

  @Test
  @DisplayName("带扩展名的文件名在扩展名前插入分区标签")
  void shouldInsertTagBeforeExtension() {
    assertThat(BatchFileConstants.insertPartitionTag("data.csv", 2, 4)).isEqualTo("data_p2of4.csv");
  }

  @Test
  @DisplayName("含目录路径时只改写文件名部分,目录保持不变")
  void shouldInsertTagInPathFileName() {
    assertThat(BatchFileConstants.insertPartitionTag("outbound/a/b/data.csv", 1, 3))
        .isEqualTo("outbound/a/b/data_p1of3.csv");
  }

  @Test
  @DisplayName("无扩展名时在文件名末尾追加分区标签")
  void shouldAppendTag_whenNoExtension() {
    assertThat(BatchFileConstants.insertPartitionTag("noext", 2, 4)).isEqualTo("noext_p2of4");
  }

  @Test
  @DisplayName("只有单个分区时文件名保持原样")
  void shouldReturnUnchanged_whenSinglePartition() {
    assertThat(BatchFileConstants.insertPartitionTag("data.csv", 1, 1)).isEqualTo("data.csv");
  }
}
