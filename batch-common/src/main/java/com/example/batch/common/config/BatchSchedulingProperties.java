package com.example.batch.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.scheduling")
public class BatchSchedulingProperties {

    /**
     * Shared scheduler pool size for @Scheduled tasks across batch services.
     */
    private int poolSize = 8;

    /**
     * Prefix used for scheduler threads.
     */
    private String threadNamePrefix = "batch-scheduler-";

    public int getPoolSize() {
        return poolSize;
    }

    public void setPoolSize(int poolSize) {
        this.poolSize = poolSize;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }
}
