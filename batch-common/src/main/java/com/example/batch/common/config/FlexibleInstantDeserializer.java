package com.example.batch.common.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Instant 反序列化的"宽松"版本，按以下顺序解析输入字符串：
 *
 * <ol>
 *   <li>纯数字 → epoch milliseconds
 *   <li>含 Z / 显式 offset 的 ISO-8601（如 {@code 2026-04-28T00:00:00Z} / {@code
 *       2026-04-28T00:00:00+08:00}）→ 走原生 {@link Instant#parse} / {@link OffsetDateTime#parse}
 *   <li>无 zone 的 naive 形式（如 {@code 2026-04-28T00:00:00} / {@code 2026-04-28}）→ 按 {@link
 *       BatchTimezoneProvider#defaultZone()} 当前区解释为本地时间，再转 Instant
 * </ol>
 *
 * <p>背景：默认 {@code JavaTimeModule.InstantDeserializer} 严格要求时区信息，前端发 {@code
 * 2026-04-28T00:00:00}（用户选择日期 + 默认零时）就 400。本类在 BatchJsonAutoConfiguration 注册 后接管 Instant
 * 的反序列化，前端容忍三种常见写法都能正确入库。
 *
 * <p>序列化仍走 JavaTimeModule 默认（标准 ISO + 'Z'），保持出库格式稳定。
 */
public class FlexibleInstantDeserializer extends StdScalarDeserializer<Instant> {

  private static final long serialVersionUID = 1L;

  private final transient BatchTimezoneProvider timezoneProvider;

  public FlexibleInstantDeserializer(BatchTimezoneProvider timezoneProvider) {
    super(Instant.class);
    this.timezoneProvider = timezoneProvider;
  }

  @Override
  public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    if (p.currentToken().isNumeric()) {
      return Instant.ofEpochMilli(p.getLongValue());
    }
    String raw = p.getValueAsString();
    if (raw == null || raw.isBlank()) {
      return null;
    }
    String text = raw.trim();
    // 1) 数字字符串 → epoch ms
    if (text.chars().allMatch(Character::isDigit)) {
      try {
        return Instant.ofEpochMilli(Long.parseLong(text));
      } catch (NumberFormatException ignored) {
        // 继续尝试下一种解析方式
      }
    }
    // 2) 含 Z / offset 的 ISO
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException ignored) {
      // 继续尝试下一种解析方式
    }
    try {
      return OffsetDateTime.parse(text).toInstant();
    } catch (DateTimeParseException ignored) {
      // 继续尝试下一种解析方式
    }
    // 3) naive LocalDateTime + 默认时区
    ZoneId zone = timezoneProvider.defaultZone();
    try {
      return LocalDateTime.parse(text).atZone(zone).toInstant();
    } catch (DateTimeParseException ignored) {
      // 继续尝试下一种解析方式
    }
    // 4) 仅日期 (YYYY-MM-DD)
    try {
      return LocalDate.parse(text).atStartOfDay(zone).toInstant();
    } catch (DateTimeParseException ignored) {
      // 继续尝试下一种解析方式
    }
    return (Instant) ctxt.handleWeirdStringValue(Instant.class, text, "无法解析为 Instant 的字符串");
  }
}
