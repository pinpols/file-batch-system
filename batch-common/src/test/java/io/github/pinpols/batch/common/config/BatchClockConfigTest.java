package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("时钟配置:未配置偏移量时提供协调世界时时钟,配置偏移量后按时钟前移,非法偏移量快速失败")
class BatchClockConfigTest {

  private static final String OFFSET_PROPERTY = "batch.testing.clock-offset";

  @AfterEach
  void clearOffset() {
    System.clearProperty(OFFSET_PROPERTY);
  }

  @Test
  @DisplayName("未配置时钟偏移量时返回协调世界时时区的时钟,且当前时刻与系统时间相差不足一秒")
  void shouldUseUtcClock_whenOffsetNotConfigured() {
    System.clearProperty(OFFSET_PROPERTY);

    Clock clock = new BatchClockConfig().batchClock();

    assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    assertThat(Duration.between(Instant.now(), clock.instant()).abs())
        .isLessThan(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("配置十二小时偏移量后返回的时钟当前时刻前移十二小时,偏差在一秒以内")
  void shouldApplyExplicitOffset_whenOffsetPropertyConfigured() {
    System.setProperty(OFFSET_PROPERTY, "+12h");
    Instant before = Instant.now();

    Clock clock = new BatchClockConfig().batchClock();

    assertThat(
            Duration.between(before.plus(Duration.ofHours(12)), clock.instant()).abs())
        .isLessThan(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("偏移量文本无法解析时抛出非法参数异常,并在异常信息中指明所属配置项")
  void shouldThrowIllegalArgument_whenOffsetPropertyMalformed() {
    System.setProperty(OFFSET_PROPERTY, "tomorrow");

    assertThatThrownBy(() -> new BatchClockConfig().batchClock())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(OFFSET_PROPERTY);
  }
}
