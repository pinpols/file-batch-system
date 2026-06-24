package io.github.pinpols.batch.orchestrator.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 请求签名校验配置（方案 A，opt-in，默认关）。
 *
 * <p>开启后，对 api_key 鉴权的 {@code /internal/tasks|workers} 写请求强制校验 HMAC 签名 + 时间戳窗口 + nonce 一次性，
 * 防重放与防篡改。默认关闭以保持对存量 worker 的向后兼容；灰度时先升级 SDK 再开开关。
 */
@Data
@ConfigurationProperties(prefix = "batch.request-signing")
public class RequestSigningProperties {

  /** 总开关，默认关闭。{@code BATCH_REQUEST_SIGNING_ENABLED=true} 开启。 */
  private boolean enabled = false;

  /** 时钟偏移容忍窗口（秒）；|now - timestamp| 超出即拒。默认 300s。nonce TTL 取其 2 倍。 */
  private int clockSkewSeconds = 300;
}
