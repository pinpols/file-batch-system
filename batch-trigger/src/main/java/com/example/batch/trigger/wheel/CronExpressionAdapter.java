package com.example.batch.trigger.wheel;

import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronExpression;

/**
 * Quartz {@link CronExpression} 包装,提供"按 cron 表达式 + 时区 + 起始时刻 → 下次 fire 时刻"。
 *
 * <p><b>关键决策</b>(详见 quartz-replacement-design.md §7):**沿用 Quartz CronExpression 做计算**, 不引入新解析器(如
 * Spring CronExpression)。理由:
 *
 * <ul>
 *   <li>Quartz 支持 L / W / # 等扩展字符,Spring 不支持
 *   <li>业务侧 cron 表达式 0 改动,降低切换风险
 *   <li>历史 Quartz 实现的 next-fire-time 计算与本适配器一致,迁移期对账无差异
 * </ul>
 *
 * <p><b>线程安全</b>:{@link CronExpression} 实例本身不是线程安全的，{@code setTimeZone} 是写操作。 R4-P0-3：之前缓存键只用
 * cronExpr，多个并发线程拿到同一个 instance 互相覆盖 setTimeZone → 算错 next。 现在缓存键改为 {@code cronExpr + "|" +
 * zone.getId()}，时区在构建时固化进 instance； next() 只调 {@code getNextValidTimeAfter}（只读），无并发污染。
 */
@Slf4j
public class CronExpressionAdapter {

  /** R4-P0-3：缓存键含 zoneId，instance 时区固化，避免跨线程 setTimeZone 污染。 */
  private final ConcurrentMap<String, CronExpression> cache = new ConcurrentHashMap<>();

  /**
   * 计算 cron 表达式在指定时区下、{@code after} 之后的下一次 fire 时刻。
   *
   * @param cronExpr Quartz 6 字段 cron(秒 分 时 日 月 星期);7 字段(年)也支持
   * @param zone 时区(由 BatchTimezoneProvider 解析,从不为 null)
   * @param after 起始时刻(包含,但 Quartz 实际是"之后,不含")
   * @return 下次 fire 时刻;cron 已无未来 fire 时刻(如固定年份已过)返回 {@code null}
   * @throws IllegalArgumentException cron 表达式语法错误
   */
  public Instant next(String cronExpr, ZoneId zone, Instant after) {
    CronExpression expression = parseOrCache(cronExpr, zone);
    // setTimeZone 不再调（已在构建时固化），避免跨线程写
    Date nextFire = expression.getNextValidTimeAfter(Date.from(after));
    return nextFire == null ? null : nextFire.toInstant();
  }

  /** 校验 cron 表达式合法。Reconciler 同步前预检用。 */
  public boolean isValid(String cronExpr) {
    if (cronExpr == null || cronExpr.isBlank()) {
      return false;
    }
    return CronExpression.isValidExpression(cronExpr);
  }

  private CronExpression parseOrCache(String cronExpr, ZoneId zone) {
    String cacheKey = cronExpr + "|" + zone.getId();
    return cache.computeIfAbsent(
        cacheKey,
        k -> {
          try {
            CronExpression expr = new CronExpression(cronExpr);
            expr.setTimeZone(TimeZone.getTimeZone(zone));
            return expr;
          } catch (ParseException e) {
            throw new IllegalArgumentException("invalid cron expression: " + cronExpr, e);
          }
        });
  }

  /** trigger schedule_expr 修改时,从缓存清掉旧 cron(防内存累积)。 R4-P0-3：键含 zone，evict 时枚举所有 zone 变体清掉。 */
  public void evict(String cronExpr) {
    cache.keySet().removeIf(k -> k.startsWith(cronExpr + "|"));
  }
}
