package com.example.batch.console.config;

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
}
