package com.example.batch.trigger.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * Trigger 优雅停机：先将 Quartz Scheduler 切换到 standby 模式（停止触发新 job），
 * 再等待已触发的 job 执行完成后 shutdown。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TriggerGracefulShutdown implements ApplicationListener<ContextClosedEvent> {

    private final Scheduler scheduler;

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        try {
            if (scheduler.isShutdown()) {
                return;
            }
            log.info("Trigger graceful shutdown — switching scheduler to standby");
            scheduler.standby();
            log.info("Trigger graceful shutdown — shutting down scheduler (waitForJobsToComplete=true)");
            scheduler.shutdown(true);
            log.info("Trigger scheduler shutdown complete");
        } catch (SchedulerException e) {
            log.warn("Error during trigger graceful shutdown: {}", e.getMessage(), e);
        }
    }
}
