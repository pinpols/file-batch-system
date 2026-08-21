package io.github.pinpols.batch.common.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
/** 提供统一 UTC 时钟，避免业务逻辑直接依赖系统默认时区。 */
public class BatchClockConfig {

  private static final String TESTING_CLOCK_OFFSET_PROPERTY = "batch.testing.clock-offset";
  private static final Pattern TESTING_OFFSET_PATTERN = Pattern.compile("^([+-]?\\d+)([smhd])$");

  /**
   * 技术时间统一使用 UTC Clock。
   *
   * <p>业务时区不在 Clock 里表达，而是在 BatchTimezoneProvider / BatchTimeSupport 中表达。
   */
  @Bean
  public Clock batchClock() {
    Clock systemClock = Clock.systemUTC();
    String rawOffset = System.getProperty(TESTING_CLOCK_OFFSET_PROPERTY);
    if (EmptyChecks.isBlank(rawOffset)) {
      return systemClock;
    }
    return Clock.offset(systemClock, parseTestingOffset(rawOffset));
  }

  private static Duration parseTestingOffset(String rawOffset) {
    Matcher matcher = TESTING_OFFSET_PATTERN.matcher(rawOffset.trim().toLowerCase(Locale.ROOT));
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid " + TESTING_CLOCK_OFFSET_PROPERTY
          + ": expected [+|-]<number><s|m|h|d>, got " + rawOffset);
    }
    long amount = Long.parseLong(matcher.group(1));
    return switch (matcher.group(2)) {
      case "s" -> Duration.ofSeconds(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      case "d" -> Duration.ofDays(amount);
      default ->
        throw new IllegalArgumentException("Unsupported clock offset unit: " + matcher.group(2));
    };
  }
}
