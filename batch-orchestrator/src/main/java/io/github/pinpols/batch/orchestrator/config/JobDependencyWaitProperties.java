package io.github.pinpols.batch.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/** 作业依赖等待恢复与超时告警配置。 */
@Data
@ConfigurationProperties(prefix = "batch.job-dependency-wait")
public class JobDependencyWaitProperties {

    private long timeoutAlertSeconds = 86_400L;
}
