package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import com.example.batch.orchestrator.config.OutboxProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxPollScheduler {

    private final DefaultScheduleForwarder scheduleForwarder;
    private final OutboxProperties outboxProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${batch.outbox.poll-interval-millis:5000}")
    @SchedulerLock(name = "outbox_poll", lockAtMostFor = "PT1M", lockAtLeastFor = "PT3S")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduleForwarder.advance(new SchedulePlan());
        } finally {
            running.set(false);
        }
    }
}
