package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class BatchClockConfigTest {

  private static final String OFFSET_PROPERTY = "batch.testing.clock-offset";

  @AfterEach
  void clearOffset() {
    System.clearProperty(OFFSET_PROPERTY);
  }

  @Test
  void usesUtcClockByDefault() {
    System.clearProperty(OFFSET_PROPERTY);

    Clock clock = new BatchClockConfig().batchClock();

    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(Duration.between(Instant.now(), clock.instant()).abs())
        .isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void appliesExplicitOffsetForLocalSoakRuns() {
    System.setProperty(OFFSET_PROPERTY, "+12h");
    Instant before = Instant.now();

    Clock clock = new BatchClockConfig().batchClock();

    assertThat(
            Duration.between(before.plus(Duration.ofHours(12)), clock.instant()).abs())
        .isLessThan(Duration.ofSeconds(1));
  }

  @Test
  void rejectsMalformedOffset() {
    System.setProperty(OFFSET_PROPERTY, "tomorrow");

    assertThatThrownBy(() -> new BatchClockConfig().batchClock())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(OFFSET_PROPERTY);
  }
}
