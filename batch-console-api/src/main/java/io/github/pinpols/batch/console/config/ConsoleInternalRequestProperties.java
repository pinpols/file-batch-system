package io.github.pinpols.batch.console.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * console-api 内部端点({@code /internal/**},当前即 Alertmanager 出口 {@code
 * /internal/am-notify/**})请求体大小限制配置。
 *
 * <p>这些端点在 {@code ConsoleSecurityConfiguration} 里 permitAll(由 controller
 * 用共享密钥自校验),{@code @RequestBody} 在鉴权前就被 MVC 反序列化。缺硬上限时,未认证方可用超大体撑爆内存(告警风暴 payload / 恶意大 body)。 参考
 * orchestrator 的 {@code InternalRequestProperties} / {@code InternalRequestSizeFilter} 同款前置拦截。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.internal-request")
public class ConsoleInternalRequestProperties {

  /**
   * 内部端点 POST/PUT 请求体允许的最大字节数;{@code <=0} 表示不限。 默认 1MiB —— AM webhook 正常 payload 远小于此,仅拦截异常超大体防
   * OOM。 Content-Length 缺失(chunked)的请求放行。
   */
  private long maxBodyBytes = 1_048_576L;
}
