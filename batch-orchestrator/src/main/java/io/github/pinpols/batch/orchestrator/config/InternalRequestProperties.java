package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 内部端点（{@code /internal/**}）请求体大小限制配置。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.internal-request")
public class InternalRequestProperties {

  /**
   * 内部端点 POST/PUT 请求体（JSON report / outputs 等）允许的最大字节数；{@code <=0} 表示不限。 默认 16MiB，宽松到正常 report
   * 不会触发，仅拦截异常超大体防 OOM。Content-Length 缺失（chunked）的请求放行。
   */
  private long maxBodyBytes = 16_777_216L;
}
