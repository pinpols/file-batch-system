package io.github.pinpols.batch.common.time;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 批量系统统一日期时间入口。
 *
 * <p>设计原则：
 *
 * <ul>
 *   <li>技术时间：使用 Instant，基于 UTC Clock，不依赖机器默认时区。
 *   <li>业务日期：使用 LocalDate，基于平台默认业务时区。
 *   <li>无时区时间：LocalDateTime / LocalTime 必须按平台默认业务时区解释。
 *   <li>展示 / 文件名：从 Instant 转换到指定时区后格式化。
 * </ul>
 *
 * <p>业务代码优先依赖本类，不直接使用：
 *
 * <ul>
 *   <li>直接获取系统当前 Instant
 *   <li>LocalDate.now()
 *   <li>LocalDateTime.now()
 *   <li>ZonedDateTime.now()
 *   <li>OffsetDateTime.now()
 *   <li>ZoneId.systemDefault()
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class BatchDateTimeSupport {

  private static final DateTimeFormatter FILE_TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private static final DateTimeFormatter BIZ_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

  private static final DateTimeFormatter DISPLAY_DATETIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final Clock clock;
  private final BatchTimezoneProvider timezoneProvider;

  /**
   * 静态 UTC 技术时间入口。
   *
   * <p>优先用于无法注入 Spring Bean 的值对象、静态工具、轻量回调。Spring 管理的业务组件仍优先注入 {@link BatchDateTimeSupport} 并调用
   * {@link #nowInstant()}。
   *
   * <p>截断到微秒：PostgreSQL {@code TIMESTAMP} 仅支持 6 位精度，Java 9+ 在 Linux 上 {@code Clock.systemUTC()}
   * 返回纳秒精度（9 位），不截断会导致 in-memory Instant 与 DB 读回值不相等（精度不对齐 → 哈希 / 等值断言 / 乐观锁 CAS 误判）。
   */
  public static Instant utcNow() {
    return Clock.systemUTC().instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** 静态 UTC epoch milliseconds 入口，语义同 {@link #currentEpochMillis()}。 */
  public static long utcEpochMillis() {
    return Clock.systemUTC().millis();
  }

  // ---------------------------------------------------------------------------
  // 1. 技术时间：事件时间、创建时间、更新时间、锁、租约、超时、消息时间
  // ---------------------------------------------------------------------------

  /**
   * 当前技术时间。
   *
   * <p>返回绝对时间点，不包含时区语义。
   *
   * <p>适用于：
   *
   * <ul>
   *   <li>createdAt
   *   <li>updatedAt
   *   <li>emittedAt
   *   <li>registeredAt
   *   <li>heartbeatAt
   *   <li>jwtIssuedAt
   *   <li>messageCreatedAt
   * </ul>
   */
  public Instant nowInstant() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  /** 当前 UTC epoch milliseconds。适用于限流窗口、唯一技术 key、缓存过期等技术时间。 */
  public long currentEpochMillis() {
    return clock.millis();
  }

  /**
   * 当前技术时间加指定持续时间。
   *
   * <p>适用于：
   *
   * <ul>
   *   <li>leaseUntil
   *   <li>timeoutAt
   *   <li>nextRetryAt
   *   <li>expireAt
   * </ul>
   */
  public Instant instantAfter(Duration duration) {
    Objects.requireNonNull(duration, "duration must not be null");
    return nowInstant().plus(duration);
  }

  /** 当前技术时间加秒数。 */
  public Instant instantAfterSeconds(long seconds) {
    return nowInstant().plusSeconds(seconds);
  }

  /**
   * 当前 UTC OffsetDateTime。
   *
   * <p>仅用于兼容历史字段仍然是 OffsetDateTime 的场景。新字段优先使用 Instant。
   */
  public OffsetDateTime nowOffsetUtc() {
    return OffsetDateTime.ofInstant(nowInstant(), ZoneOffset.UTC);
  }

  // ---------------------------------------------------------------------------
  // 2. 默认业务时区
  // ---------------------------------------------------------------------------

  /**
   * 平台默认业务时区。
   *
   * <p>来自 {@link BatchTimezoneProvider#defaultZone()}。
   */
  public ZoneId defaultBusinessZone() {
    return timezoneProvider.defaultZone();
  }

  /**
   * 当前时间在平台默认业务时区下的 ZonedDateTime。
   *
   * <p>不建议用于持久化技术时间；持久化技术时间请使用 {@link #nowInstant()}。
   */
  public ZonedDateTime nowInDefaultBusinessZone() {
    return nowInstant().atZone(defaultBusinessZone());
  }

  /**
   * 当前平台默认业务时区下的自然日期。
   *
   * <p>注意：这只是自然日，不一定等于最终批量日。
   */
  public LocalDate todayInDefaultBusinessZone() {
    return nowInDefaultBusinessZone().toLocalDate();
  }

  // ---------------------------------------------------------------------------
  // 3. 业务日期 / 批量日
  // ---------------------------------------------------------------------------

  /**
   * 当前业务日。
   *
   * <p>当前默认等于平台默认业务时区下的自然日。
   *
   * <p>后续可接入 BusinessCalendarService，支持：
   *
   * <ul>
   *   <li>节假日
   *   <li>工作日
   *   <li>T+1 / T-1
   *   <li>顺延规则
   * </ul>
   */
  public LocalDate currentBusinessDate() {
    return todayInDefaultBusinessZone();
  }

  /**
   * 当前批量日。
   *
   * <p>当前默认等于业务日。
   *
   * <p>后续可接入 BatchDayService，支持：
   *
   * <ul>
   *   <li>批量日切换
   *   <li>跨日跑批
   *   <li>跳批次
   *   <li>补批次
   * </ul>
   */
  public LocalDate currentBatchDate() {
    return currentBusinessDate();
  }

  /** 如果外部已传业务日，则使用外部业务日；否则使用当前业务日。 */
  public LocalDate bizDateOrCurrent(LocalDate bizDate) {
    return bizDate == null ? currentBusinessDate() : bizDate;
  }

  /** 如果外部已传批量日，则使用外部批量日；否则使用当前批量日。 */
  public LocalDate batchDateOrCurrent(LocalDate batchDate) {
    return batchDate == null ? currentBatchDate() : batchDate;
  }

  // ---------------------------------------------------------------------------
  // 4. 无时区时间解释：LocalDateTime / LocalDate + LocalTime -> Instant
  // ---------------------------------------------------------------------------

  /**
   * 将无时区的 LocalDateTime 按平台默认业务时区解释为 Instant。
   *
   * <p>例如 {@code 2026-05-05T10:00:00} 会被解释为 defaultBusinessZone 下的 10 点。
   */
  public Instant interpretLocalDateTimeInDefaultZone(LocalDateTime localDateTime) {
    if (localDateTime == null) {
      return null;
    }
    return localDateTime.atZone(defaultBusinessZone()).toInstant();
  }

  /** 将业务日期 + 本地时间按平台默认业务时区解释为 Instant。 */
  public Instant interpretDateAndTimeInDefaultZone(LocalDate date, LocalTime time) {
    return interpretDateAndTime(date, time, defaultBusinessZone());
  }

  /** 将业务日期 + 本地时间按指定时区解释为 Instant。 */
  public Instant interpretDateAndTime(LocalDate date, LocalTime time, ZoneId zoneId) {
    if (date == null || time == null) {
      return null;
    }
    ZoneId resolvedZone = zoneId == null ? defaultBusinessZone() : zoneId;
    return date.atTime(time).atZone(resolvedZone).toInstant();
  }

  // ---------------------------------------------------------------------------
  // 5. Instant 转本地时区时间：展示、Cron 辅助、排查
  // ---------------------------------------------------------------------------

  /** 将 Instant 转成平台默认业务时区的 ZonedDateTime。 */
  public ZonedDateTime toDefaultBusinessZonedDateTime(Instant instant) {
    if (instant == null) {
      return null;
    }
    return instant.atZone(defaultBusinessZone());
  }

  /** 将 Instant 转成指定时区的 ZonedDateTime。 */
  public ZonedDateTime toZonedDateTime(Instant instant, ZoneId zoneId) {
    if (instant == null) {
      return null;
    }
    ZoneId resolvedZone = zoneId == null ? defaultBusinessZone() : zoneId;
    return instant.atZone(resolvedZone);
  }

  // ---------------------------------------------------------------------------
  // 6. 格式化：展示时间、文件名时间
  // ---------------------------------------------------------------------------

  /** 默认展示时间：yyyy-MM-dd HH:mm:ss。 */
  public String formatForDisplay(Instant instant) {
    return formatForDisplay(instant, defaultBusinessZone());
  }

  /** 指定时区展示时间：yyyy-MM-dd HH:mm:ss。 */
  public String formatForDisplay(Instant instant, ZoneId zoneId) {
    if (instant == null) {
      return null;
    }
    ZoneId resolvedZone = zoneId == null ? defaultBusinessZone() : zoneId;
    return DISPLAY_DATETIME_FORMATTER.withZone(resolvedZone).format(instant);
  }

  /** 文件名时间戳：yyyyMMddHHmmss。 */
  public String formatForFileTimestamp(Instant instant) {
    if (instant == null) {
      return null;
    }
    return FILE_TIMESTAMP_FORMATTER.withZone(defaultBusinessZone()).format(instant);
  }

  /** 当前文件名时间戳：yyyyMMddHHmmss。 */
  public String currentFileTimestamp() {
    return formatForFileTimestamp(nowInstant());
  }

  /** 业务日期文件名片段：yyyyMMdd。 */
  public String formatBizDateForFileName(LocalDate bizDate) {
    if (bizDate == null) {
      return null;
    }
    return BIZ_DATE_FORMATTER.format(bizDate);
  }

  /**
   * 构造带当前时间戳的文件名。
   *
   * <p>示例：workflow-maintenance-20260505143022.xlsx
   */
  public String buildTimestampFileName(String prefix, String extension) {
    return sanitizeFileNamePart(prefix, "file")
        + "-"
        + currentFileTimestamp()
        + normalizeExtension(extension);
  }

  /**
   * 构造带业务日期 + 当前时间戳的文件名。
   *
   * <p>示例：export-20260505-20260505143022.xlsx
   */
  public String buildBizDateTimestampFileName(String prefix, LocalDate bizDate, String extension) {
    LocalDate resolvedBizDate = bizDateOrCurrent(bizDate);

    return sanitizeFileNamePart(prefix, "file")
        + "-"
        + formatBizDateForFileName(resolvedBizDate)
        + "-"
        + currentFileTimestamp()
        + normalizeExtension(extension);
  }

  private String normalizeExtension(String extension) {
    if (extension == null || extension.isBlank()) {
      return "";
    }
    String text = extension.trim();
    return text.startsWith(".") ? text : "." + text;
  }

  private String sanitizeFileNamePart(String value, String defaultValue) {
    String text = value == null ? "" : value.trim();
    if (text.isEmpty()) {
      return defaultValue;
    }

    // Windows / Linux / macOS 文件名常见非法字符统一替换。
    return text.replaceAll("[\\\\/:*?\"<>|\\s]+", "-");
  }
}
