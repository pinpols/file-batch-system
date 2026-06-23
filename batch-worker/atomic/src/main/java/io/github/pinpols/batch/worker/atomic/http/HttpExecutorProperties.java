package io.github.pinpols.batch.worker.atomic.http;

import java.time.Duration;
import java.util.Set;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@link HttpTaskExecutor} 配置 — 默认全关。
 *
 * <p>设计依据:{@code docs/design/task-spi-design.md}。
 *
 * <p>安全防护链:
 *
 * <ol>
 *   <li>{@link #enabled}:总开关,默认 false
 *   <li>{@link #allowedHostPatterns}:出口域名白名单(简单 glob,如 {@code "api.internal.*"}),空 = 允许全部(仅 dev)
 *   <li>{@link #blockedHostPatterns}:出口域名黑名单(永远拒,优先于白名单)。默认拒绝 metadata 服务 + localhost(SSRF 防御)
 *   <li>{@link #defaultTimeout}:连接 + 读超时
 *   <li>{@link #maxResponseBytes}:响应体截断字节,超出截断 + WARN
 *   <li>{@link #allowedMethods}:HTTP 方法白名单
 *   <li>{@link #maxRetries} / {@link #retryBackoff}:简单重试(只对幂等方法 / 5xx)
 * </ol>
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.executors.http")
public class HttpExecutorProperties {

  /** 总开关。默认 <b>true</b>(随 atomic worker 默认提供;出口受 SSRF 私网拦截 + 域名白名单约束)。 */
  private boolean enabled = true;

  /** 给 http 任务挂的 task type。固定 "http"。 */
  private String taskType = "http";

  /**
   * 出口域名白名单。glob 模式(* = 一段任意字符,不跨 .)。空 = 允许全部(仅 dev / 信任环境)。 例: {@code ["api.internal.*",
   * "*.example.com"]}
   */
  private Set<String> allowedHostPatterns = Set.of();

  /**
   * 出口域名黑名单。永远拒绝,优先于白名单。默认拒绝 cloud metadata 服务 + localhost(SSRF 防御)。 安全 critical:不要轻易清空,看
   * docs/design/task-spi-design.md §安全。
   */
  private Set<String> blockedHostPatterns =
      Set.of(
          "169.254.169.254", // AWS / GCP metadata
          "metadata.google.internal",
          "metadata.azure.com",
          "localhost",
          "127.*",
          "0.0.0.0",
          "::1");

  /** 连接 + 读超时(同一个 Duration,简化)。 */
  private Duration defaultTimeout = Duration.ofSeconds(30);

  /** 响应体最大字节,超出截断 + WARN。默认 1MB。 */
  private int maxResponseBytes = 1024 * 1024;

  /** 允许的 HTTP 方法。 */
  private Set<String> allowedMethods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD");

  /** 最大重试次数(只对幂等方法 + 5xx + 网络异常)。0 = 不重试。 */
  private int maxRetries = 0;

  /** 重试 backoff(指数,实际等待 = backoff * 2^attempt)。 */
  private Duration retryBackoff = Duration.ofMillis(500);

  /** 允许的鉴权类型集合。默认 none / basic / bearer。 */
  private Set<String> allowedAuthTypes = Set.of("none", "basic", "bearer");

  /**
   * 是否在 glob 校验之后,把 host 解析为 IP 并拒绝内网 / 回环 / link-local / metadata 网段(SSRF 加固)。 默认 true:glob
   * 黑名单只能字面匹配,无法挡住 octal / decimal IP literal、IPv4-mapped IPv6 ({@code ::ffff:169.254.169.254})、DNS
   * rebinding 等绕过。
   */
  private boolean blockPrivateIps = true;

  /**
   * 为 true 时,空 {@link #allowedHostPatterns} 表示「拒绝全部」而非「允许全部」。 默认 false:保持空白名单 = 允许全部的现有行为(仅
   * dev)。生产建议置 true 做 fail-closed。
   */
  private boolean enforceAllowlist = false;
}
