package com.example.batch.trigger.wheel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.quartz.CronExpression;

/**
 * CronExpressionAdapter 的两层测试:
 *
 * <ol>
 *   <li>基本算法正确(next 计算 / isValid / IAE)
 *   <li><b>关键:与 Quartz CronExpression 直接调用 next-fire-time 序列完全一致</b> —
 *       切换 wheel scheduler 前的 sanity check,保证业务侧 cron 表达式 0 改动 + fire 时刻
 *       跟 Quartz 时代完全相同
 * </ol>
 *
 * <p>测试覆盖常见 cron 模式 + Quartz 扩展字符 (L / W / #) + 跨夏令时 / 跨年。
 */
class CronExpressionAdapterTest {

  private final CronExpressionAdapter adapter = new CronExpressionAdapter();
  private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

  // ── 基础算法 ────────────────────────────────────────────

  @Test
  void nextSimpleHourly() {
    Instant base = LocalDateTime.of(2026, 4, 26, 10, 0, 0).atZone(SHANGHAI).toInstant();
    Instant next = adapter.next("0 0 * * * ?", SHANGHAI, base);
    assertThat(next).isEqualTo(LocalDateTime.of(2026, 4, 26, 11, 0, 0).atZone(SHANGHAI).toInstant());
  }

  @Test
  void nextRespectsTimezone() {
    Instant base = LocalDateTime.of(2026, 4, 26, 0, 0, 0).atZone(ZoneId.of("UTC")).toInstant();
    Instant nextShanghai = adapter.next("0 0 9 * * ?", SHANGHAI, base);
    Instant nextUtc = adapter.next("0 0 9 * * ?", ZoneId.of("UTC"), base);
    assertThat(nextShanghai).isNotEqualTo(nextUtc);
    // Shanghai 09:00 = UTC 01:00,UTC 09:00 = Shanghai 17:00,差 8 小时
    assertThat(nextUtc.toEpochMilli() - nextShanghai.toEpochMilli())
        .isEqualTo(8 * 3600 * 1000L);
  }

  @Test
  void invalidExpressionThrowsIAE() {
    assertThatThrownBy(() -> adapter.next("invalid", SHANGHAI, Instant.now()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("invalid cron expression");
  }

  @Test
  void isValidReturnsTrueForValidExpressions() {
    assertThat(adapter.isValid("0 0 * * * ?")).isTrue();
    assertThat(adapter.isValid("0 30 4 ? * SUN")).isTrue();
    assertThat(adapter.isValid("0 0 20 L * ?")).isTrue();
  }

  @Test
  void isValidReturnsFalseForInvalid() {
    assertThat(adapter.isValid("")).isFalse();
    assertThat(adapter.isValid(null)).isFalse();
    assertThat(adapter.isValid("xyz")).isFalse();
  }

  @Test
  void evictRemovesFromCache() {
    String expr = "0 0 * * * ?";
    adapter.next(expr, SHANGHAI, Instant.now());  // 进缓存
    adapter.evict(expr);  // 不抛异常即可
  }

  // ── 与 Quartz CronExpression 一致性(切换 sanity check)──

  /**
   * 用一组常见 + 边界 cron,验证 adapter 算出的 next 序列跟直接调 Quartz CronExpression
   * 完全一致。覆盖 24 次 fire — 跨整天 / 跨午夜 / 跨周。
   */
  @ParameterizedTest
  @ValueSource(strings = {
      "0 0 * * * ?",          // 每小时整点
      "0 30 7 * * ?",         // 每天 07:30
      "0 0 0 * * ?",          // 每天午夜
      "0 0 9 ? * MON-FRI",    // 工作日 09:00
      "0 0 0 1 * ?",          // 每月 1 号
      "0 0 20 L * ?",         // 每月最后一天 20:00(L 字符)
      "0 0/15 * * * ?",       // 每 15 分钟
      "30 * * * * ?",         // 每分钟第 30 秒
      "0 0 12 ? * 6#1",       // 每月第一个周六(# 字符;day-of-month 必须 ?)
  })
  void adapterMatchesQuartzNextFireSeries(String cronExpr) throws ParseException {
    Instant base = LocalDateTime.of(2026, 4, 26, 0, 0, 0).atZone(SHANGHAI).toInstant();

    CronExpression quartzExpr = new CronExpression(cronExpr);
    quartzExpr.setTimeZone(TimeZone.getTimeZone(SHANGHAI));

    List<Instant> adapterSeries = new ArrayList<>();
    List<Instant> quartzSeries = new ArrayList<>();

    Instant cursor = base;
    for (int i = 0; i < 24; i++) {
      Instant adapterNext = adapter.next(cronExpr, SHANGHAI, cursor);
      Date quartzNext = quartzExpr.getNextValidTimeAfter(Date.from(cursor));
      if (adapterNext == null || quartzNext == null) {
        // 两者都耗尽才 OK
        assertThat(adapterNext).as("adapter null at iter %d for cron %s", i, cronExpr).isNull();
        assertThat(quartzNext).as("quartz null at iter %d for cron %s", i, cronExpr).isNull();
        break;
      }
      adapterSeries.add(adapterNext);
      quartzSeries.add(quartzNext.toInstant());
      cursor = adapterNext;
    }

    assertThat(adapterSeries)
        .as("cron series mismatch for: %s", cronExpr)
        .containsExactlyElementsOf(quartzSeries);
  }

  /**
   * 跨夏令时(项目用 Asia/Shanghai 不进夏令时,但用 America/New_York 测试 adapter 不会抖动)。
   * 2026-03-08 02:00 美东春令时跳到 03:00。
   */
  @Test
  void daylightSavingTransitionMatchesQuartz() throws ParseException {
    String cron = "0 0 2 * * ?";   // 每天 02:00 — 春令时这天 02:00 不存在
    ZoneId nyc = ZoneId.of("America/New_York");
    Instant base = LocalDateTime.of(2026, 3, 7, 0, 0).atZone(nyc).toInstant();

    CronExpression quartzExpr = new CronExpression(cron);
    quartzExpr.setTimeZone(TimeZone.getTimeZone(nyc));

    Instant cursor = base;
    for (int i = 0; i < 5; i++) {
      Instant adapterNext = adapter.next(cron, nyc, cursor);
      Date quartzNext = quartzExpr.getNextValidTimeAfter(Date.from(cursor));
      assertThat(adapterNext)
          .as("DST iter %d", i)
          .isEqualTo(quartzNext.toInstant());
      cursor = adapterNext;
    }
  }

  /** 跨年的 cron 计算应该正确。 */
  @Test
  void crossYearMatchesQuartz() throws ParseException {
    String cron = "0 0 0 1 1 ?";   // 每年 1 月 1 日 00:00
    Instant base = LocalDateTime.of(2026, 12, 31, 23, 0).atZone(SHANGHAI).toInstant();

    CronExpression quartzExpr = new CronExpression(cron);
    quartzExpr.setTimeZone(TimeZone.getTimeZone(SHANGHAI));

    Instant adapterNext = adapter.next(cron, SHANGHAI, base);
    Date quartzNext = quartzExpr.getNextValidTimeAfter(Date.from(base));

    assertThat(adapterNext).isEqualTo(quartzNext.toInstant());
    assertThat(adapterNext)
        .isEqualTo(LocalDateTime.of(2027, 1, 1, 0, 0).atZone(SHANGHAI).toInstant());
  }
}
