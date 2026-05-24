package com.example.batch.worker.dispatchs.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.worker.dispatch.http-channel")
public class HttpDispatchChannelProperties {

  /** HTTP 连接超时（毫秒），默认 10 秒 */
  private long connectTimeoutMillis = 10_000L;

  /** HTTP 读取超时（毫秒），默认 30 秒 */
  private long readTimeoutMillis = 30_000L;

  /** HTTP 写入超时（毫秒），默认 30 秒 */
  private long writeTimeoutMillis = 30_000L;

  /** HTTP 整体调用超时（毫秒，含连接 + 读 + 写 + 重定向），默认 60 秒；防止单次请求总时长不可控 */
  private long callTimeoutMillis = 60_000L;
}
