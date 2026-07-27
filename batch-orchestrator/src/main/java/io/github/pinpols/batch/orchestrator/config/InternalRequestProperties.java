package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 内部端点（{@code /internal/**}）请求体大小限制配置。 */
@Data
@ConfigurationProperties(prefix = "batch.worker.internal-request")
public class InternalRequestProperties {

  /**
   * 内部端点 POST/PUT/PATCH/DELETE 请求体（JSON report / outputs 等）允许的最大字节数；{@code <=0} 表示不限。默认 16MiB，宽松到正常
   * report 不会触发，仅拦截异常超大体防 OOM。Content-Length 缺失（chunked）时也按实际读取字节数拦截。
   */
  private long maxBodyBytes = 16_777_216L;
}
