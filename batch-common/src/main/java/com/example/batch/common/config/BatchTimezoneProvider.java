package com.example.batch.common.config;

import com.example.batch.common.utils.Texts;
import jakarta.annotation.PostConstruct;
import java.time.DateTimeException;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 全局时区 provider。所有业务代码通过此 bean 读取默认 {@link ZoneId}；禁止继续使用 {@code ZoneId.systemDefault()}（容器 / JVM
 * 默认可能因部署环境漂移）。
 *
 * <p>{@link #resolveOrDefault} 供 "优先用业务侧显式配置（比如 business_calendar.timezone），其次退到平台默认" 的模式使用，替换以前
 * {@code Texts.hasText(tz) ? ZoneId.of(tz) : ZoneId.systemDefault()} 的写法。
 */
@Slf4j
@Component
@EnableConfigurationProperties(BatchTimezoneProperties.class)
public class BatchTimezoneProvider {

  private final ZoneId defaultZone;
  private final String defaultZoneLabel;

  public BatchTimezoneProvider(BatchTimezoneProperties properties) {
    String raw = properties == null ? null : properties.getDefaultZone();
    this.defaultZoneLabel = Texts.hasText(raw) ? raw.trim() : "Asia/Shanghai";
    ZoneId parsed;
    try {
      parsed = ZoneId.of(this.defaultZoneLabel);
    } catch (DateTimeException invalid) {
      log.warn(
          "invalid batch.timezone.default-zone='{}', falling back to Asia/Shanghai: {}",
          this.defaultZoneLabel,
          invalid.getMessage());
      parsed = ZoneId.of("Asia/Shanghai");
    }
    this.defaultZone = parsed;
  }

  @PostConstruct
  void logBootstrap() {
    log.info(
        "batch timezone provider initialized: default-zone={} (raw='{}')",
        defaultZone,
        defaultZoneLabel);
  }

  /** 平台默认时区，来自 {@code batch.timezone.default-zone}；永远非 null。 */
  public ZoneId defaultZone() {
    return defaultZone;
  }

  /** 优先用传入的 IANA 名（如 calendar.timezone），为空则返回 {@link #defaultZone}。 */
  public ZoneId resolveOrDefault(String preferred) {
    if (!Texts.hasText(preferred)) {
      return defaultZone;
    }
    try {
      return ZoneId.of(preferred.trim());
    } catch (DateTimeException invalid) {
      log.warn(
          "invalid preferred timezone='{}', falling back to default {}: {}",
          preferred,
          defaultZone,
          invalid.getMessage());
      return defaultZone;
    }
  }
}
