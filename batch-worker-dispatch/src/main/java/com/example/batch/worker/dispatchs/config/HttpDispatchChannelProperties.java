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
}
