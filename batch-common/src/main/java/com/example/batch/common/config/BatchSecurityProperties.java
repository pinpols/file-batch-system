package com.example.batch.common.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.security")
public class BatchSecurityProperties {

    /** 早期测试模式：放宽认证、脱敏和解密等限制。 */
    private boolean testingOpen = false;

    /** 演示模式：允许控制台管理员访问，并返回异常堆栈， 便于前端联调排查问题。 */
    private boolean demoOpen = false;
}
