package io.github.pinpols.batch.console.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制台 API 限流配置。
 *
 * <p>滑动窗口算法，窗口长度固定为 1 分钟。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.security.rate-limit")
public class ConsoleRateLimitProperties {

  /** 是否启用限流，默认开启 */
  private boolean enabled = true;

  /** 登录接口（POST /api/console/auth/login）：每个客户端 IP 每分钟最多尝试次数。 防止暴力破解。 */
  private int loginIpLimitPerMinute = 10;

  /** 敏感操作接口（触发器操作、手动触发等）：每个已认证用户每分钟最多请求次数。 防止资源耗尽攻击。 */
  private int sensitiveOpUserLimitPerMinute = 30;

  /**
   * 昂贵接口（配置导出/导入、Excel 导出/上传、报表导出等 CPU/IO 重操作）：每个已认证用户每分钟最多请求次数。 比普通敏感操作更严（默认
   * 10/min），防单用户用导出类接口拖垮后端。 任意 HTTP 方法都计入（导出常为 GET）。
   */
  private int expensiveOpUserLimitPerMinute = 10;

  /**
   * 昂贵接口路径前缀白名单（任一前缀命中即按 {@link #expensiveOpUserLimitPerMinute} 限流）。 可经 {@code
   * batch.console.security.rate-limit.expensive-op-path-prefixes[N]} 扩展。
   */
  private List<String> expensiveOpPathPrefixes =
      List.of(
          "/api/console/config/sync/",
          "/api/console/config/tenant-package/excel/",
          "/api/console/reports/excel");
}
