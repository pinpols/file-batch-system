package com.example.batch.common.utils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 日期/时间工具类，基于系统默认时区的 {@link java.time.Clock} 统一提供当前时间。
 * 集中使用单例 Clock 的目的是便于测试时替换时钟实现，避免散落调用 {@code LocalDate.now()} / {@code Instant.now()}。
 */
public final class DateUtils {

  private static final Clock CLOCK = Clock.systemDefaultZone();

  private DateUtils() {}

  public static LocalDate today() {
    return LocalDate.now(CLOCK);
  }

  public static Instant now() {
    return Instant.now(CLOCK);
  }

  public static ZoneId zoneId() {
    return CLOCK.getZone();
  }
}
