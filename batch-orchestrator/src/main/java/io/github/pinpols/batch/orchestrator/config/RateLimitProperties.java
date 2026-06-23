package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.rate-limit")
public class RateLimitProperties {

  /** 总开关：关闭则不做限流。 */
  private boolean enabled = false;

  /** 每租户每分钟最大新建（launch）请求数；<=0 表示关闭该项。 */
  private long maxNewRequestsPerTenantPerMinute = 0;

  /** 每租户每分钟最大释放（waiting partition dispatch release）请求数；<=0 表示关闭该项。 */
  private long maxReleaseRequestsPerTenantPerMinute = 0;

  /**
   * 每租户每分钟最大 worker 注册请求数；<=0 表示关闭该项（默认不限）。 只对 register 入口生效，防注册风暴；claim / report / heartbeat
   * 等高频热路径不挂硬限流，避免误伤正常 worker。
   */
  private long maxRegisterRequestsPerTenantPerMinute = 0;
}
