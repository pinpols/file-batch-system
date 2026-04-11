package com.example.batch.orchestrator.config;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.resource-scheduler")
public class ResourceSchedulerProperties {

    private int waitingDispatchBatchSize = 100;
    private long waitingDispatchIntervalMillis = 10000L;
    private int quotaResetSlidingWindowHours = 24;
    private long quotaResetScanIntervalMillis = 60000L;
    private boolean quotaResetEnabled = true;

    /** 全局并发上限（所有租户合计的运行中任务数）。 值 <= 0 表示关闭。 */
    private long globalMaxRunningJobs = 0;
}
