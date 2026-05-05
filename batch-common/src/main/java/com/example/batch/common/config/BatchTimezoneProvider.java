package com.example.batch.common.config;

import com.example.batch.common.utils.Texts;
import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局时区 Provider。
 *
 * <p>职责：
 *
 * <ul>
 *   <li>提供平台默认业务时区
 *   <li>禁止业务代码直接使用 ZoneId.systemDefault()
 *   <li>支持业务侧显式时区优先，例如 business_calendar.timezone、schedule.timezone、tenant.timezone
 * </ul>
 */
@Slf4j
@Component
@EnableConfigurationProperties(BatchTimezoneProperties.class)
public class BatchTimezoneProvider {

  private static final ZoneId FALLBACK_ZONE = ZoneId.of("Asia/Shanghai");

  private final ZoneId defaultZone;
  private final String configuredDefaultZone;

  public BatchTimezoneProvider(BatchTimezoneProperties properties) {
    String raw = properties == null ? null : properties.getDefaultZone();
    this.configuredDefaultZone = Texts.hasText(raw) ? raw.trim() : FALLBACK_ZONE.getId();
    this.defaultZone = parseDefaultZone(this.configuredDefaultZone);
  }

  @PostConstruct
  void logBootstrap() {
    log.info(
        "batch timezone provider initialized: default-zone={} configured-default-zone='{}'",
        defaultZone,
        configuredDefaultZone);
  }

  /**
   * 平台默认业务时区。
   *
   * <p>来自 batch.timezone.default-zone。配置为空或非法时，兜底 Asia/Shanghai。永远非 null。
   */
  public ZoneId defaultZone() {
    return defaultZone;
  }

  /**
   * 优先使用业务侧显式时区。
   *
   * <p>适用于：
   *
   * <ul>
   *   <li>business_calendar.timezone
   *   <li>schedule.timezone
   *   <li>tenant.timezone
   *   <li>user.timezone
   * </ul>
   *
   * <p>为空或非法时，回退到平台默认业务时区。
   */
  public ZoneId resolveOrDefault(String preferred) {
    if (!Texts.hasText(preferred)) {
      return defaultZone;
    }

    String text = preferred.trim();
    try {
      return ZoneId.of(text);
    } catch (DateTimeException invalid) {
      log.warn(
          "invalid preferred timezone='{}', falling back to default {}: {}",
          text,
          defaultZone,
          invalid.getMessage());
      return defaultZone;
    }
  }

  private ZoneId parseDefaultZone(String configured) {
    try {
      return ZoneId.of(configured);
    } catch (DateTimeException invalid) {
      log.warn(
          "invalid batch.timezone.default-zone='{}', falling back to {}: {}",
          configured,
          FALLBACK_ZONE,
          invalid.getMessage());
      return FALLBACK_ZONE;
    }
  }
}
