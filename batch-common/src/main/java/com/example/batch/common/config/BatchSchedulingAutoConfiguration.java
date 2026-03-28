package com.example.batch.common.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@AutoConfiguration
@EnableConfigurationProperties(BatchSchedulingProperties.class)
public class BatchSchedulingAutoConfiguration {

    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler(BatchSchedulingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(1, properties.getPoolSize()));
        scheduler.setThreadNamePrefix(properties.getThreadNamePrefix());
        scheduler.setWaitForTasksToCompleteOnShutdown(properties.isWaitForTasksToCompleteOnShutdown());
        scheduler.setAwaitTerminationSeconds(Math.max(0, properties.getAwaitTerminationSeconds()));
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(
                properties.isContinueExistingPeriodicTasksAfterShutdown());
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(
                properties.isExecuteExistingDelayedTasksAfterShutdown());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return scheduler;
    }
}
