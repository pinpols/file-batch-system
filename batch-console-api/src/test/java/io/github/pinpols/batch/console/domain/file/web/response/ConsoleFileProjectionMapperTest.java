package io.github.pinpols.batch.console.domain.file.web.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsoleFileProjectionMapperTest {

  @Test
  void channelShouldTreatOffsetlessPostgresTimestampAsUtc() {
    ConsoleFileChannelResponse response = ConsoleFileProjectionMapper.channel(
        Map.of("id", 1L, "created_at", "2026-08-13 04:48:33.838547"));

    assertThat(response.createdAt()).isEqualTo(Instant.parse("2026-08-13T04:48:33.838547Z"));
  }
}
