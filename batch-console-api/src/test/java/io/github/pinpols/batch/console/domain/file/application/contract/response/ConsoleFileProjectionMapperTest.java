package io.github.pinpols.batch.console.domain.file.application.contract.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("文件通道响应投影: 数据库时间戳的时区归一")
class ConsoleFileProjectionMapperTest {

  @Test
  @DisplayName("无时区数据库时间戳按协调世界时解析")
  void shouldTreatOffsetlessTimestampAsUtcWhenProjectingChannel() {
    ConsoleFileChannelResponse response = ConsoleFileProjectionMapper.channel(
        Map.of("id", 1L, "created_at", "2026-08-13 04:48:33.838547"));

    assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-08-13T04:48:33.838547Z"));
  }
}
