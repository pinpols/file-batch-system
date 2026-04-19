package com.example.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局时区策略。
 *
 * <p>用于替代代码中散落的 {@code ZoneId.systemDefault()} 静默降级，统一以 {@code
 * batch.timezone.default-zone} 注入。无显式覆盖时取 {@code Asia/Shanghai}（UTC+8，匹配
 * docker-compose 与 .env.example 里 {@code TZ}）。
 *
 * <p>业务代码必须通过 {@link BatchTimezoneProvider} 读取，禁止继续使用 {@code
 * ZoneId.systemDefault()}。非业务的 OS 级时间处理（日志打印、文件系统时间戳）不在此约束内。
 */
@Data
@ConfigurationProperties(prefix = "batch.timezone")
public class BatchTimezoneProperties {

  /** IANA 时区字符串（如 {@code Asia/Shanghai} / {@code UTC} / {@code America/New_York}）。 */
  private String defaultZone = "Asia/Shanghai";
}
