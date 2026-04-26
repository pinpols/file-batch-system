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
 * <p><b>线程安全</b>:{@link CronExpression} 实例本身不是线程安全的,但 next() 是只读 + 局部变量,实际并发场景没问题(也未观察到 Quartz
 * 报告);本类用 {@link ConcurrentHashMap} 缓存解析结果,缓存命中时返回的是同一个 {@link CronExpression} 实例,严格说有理论 race;
 * 量小可以接受,如真要 100% 线程安全可改 ThreadLocal 或每次 new。
 */
@Slf4j
public class CronExpressionAdapter {

  /** 缓存 {cronExpr → CronExpression};无 timezone 维度,timezone 在 next() 时注入。 */
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
    CronExpression expression = parseOrCache(cronExpr);
    expression.setTimeZone(TimeZone.getTimeZone(zone));
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

  private CronExpression parseOrCache(String cronExpr) {
    return cache.computeIfAbsent(
        cronExpr,
        expr -> {
          try {
            return new CronExpression(expr);
          } catch (ParseException e) {
            throw new IllegalArgumentException("invalid cron expression: " + expr, e);
          }
        });
  }

  /** trigger schedule_expr 修改时,从缓存清掉旧 cron(防内存累积)。 */
  public void evict(String cronExpr) {
    cache.remove(cronExpr);
  }
}
