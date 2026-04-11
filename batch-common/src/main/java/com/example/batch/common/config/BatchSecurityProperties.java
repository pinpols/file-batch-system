package com.example.batch.common.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.security")
public class BatchSecurityProperties {

    /** 早期测试模式：放宽认证、脱敏和解密等限制。 */
    private boolean testingOpen = false;
}
