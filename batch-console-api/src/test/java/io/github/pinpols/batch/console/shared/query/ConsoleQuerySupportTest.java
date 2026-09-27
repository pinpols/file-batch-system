package io.github.pinpols.batch.console.shared.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class ConsoleQuerySupportTest {

  @Test
  void dateEndUsesLastDatabaseMicrosecond() {
    Instant end = ConsoleQuerySupport.parseFlexibleInstantEndOfDay(
        "2026-09-27", "toTime", ZoneId.of("Asia/Shanghai"));

    assertThat(end).isEqualTo(Instant.parse("2026-09-27T15:59:59.999999Z"));
  }

  @Test
  void dateEndUsesNextLocalMidnightAcrossDstTransition() {
    Instant end = ConsoleQuerySupport.parseFlexibleInstantEndOfDay(
        "2026-03-08", "toTime", ZoneId.of("America/New_York"));

    assertThat(end).isEqualTo(Instant.parse("2026-03-09T03:59:59.999999Z"));
  }

  @Test
  void explicitInstantRemainsUnchanged() {
    Instant end = ConsoleQuerySupport.parseFlexibleInstantEndOfDay(
        "2026-09-27T12:34:56.123456Z", "toTime", ZoneId.of("Asia/Shanghai"));

    assertThat(end).isEqualTo(Instant.parse("2026-09-27T12:34:56.123456Z"));
  }
}
