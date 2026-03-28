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

    /**
     * Whether the shared scheduler should wait for running tasks during shutdown.
     */
    private boolean waitForTasksToCompleteOnShutdown = false;

    /**
     * Maximum time to wait for running tasks during shutdown.
     */
    private int awaitTerminationSeconds = 30;

    /**
     * Whether periodic tasks already scheduled should continue after shutdown starts.
     */
    private boolean continueExistingPeriodicTasksAfterShutdown = false;

    /**
     * Whether delayed tasks already scheduled should execute after shutdown starts.
     */
    private boolean executeExistingDelayedTasksAfterShutdown = false;

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

    public boolean isWaitForTasksToCompleteOnShutdown() {
        return waitForTasksToCompleteOnShutdown;
    }

    public void setWaitForTasksToCompleteOnShutdown(boolean waitForTasksToCompleteOnShutdown) {
        this.waitForTasksToCompleteOnShutdown = waitForTasksToCompleteOnShutdown;
    }

    public int getAwaitTerminationSeconds() {
        return awaitTerminationSeconds;
    }

    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }

    public boolean isContinueExistingPeriodicTasksAfterShutdown() {
        return continueExistingPeriodicTasksAfterShutdown;
    }

    public void setContinueExistingPeriodicTasksAfterShutdown(boolean continueExistingPeriodicTasksAfterShutdown) {
        this.continueExistingPeriodicTasksAfterShutdown = continueExistingPeriodicTasksAfterShutdown;
    }

    public boolean isExecuteExistingDelayedTasksAfterShutdown() {
        return executeExistingDelayedTasksAfterShutdown;
    }

    public void setExecuteExistingDelayedTasksAfterShutdown(boolean executeExistingDelayedTasksAfterShutdown) {
        this.executeExistingDelayedTasksAfterShutdown = executeExistingDelayedTasksAfterShutdown;
    }
}
