package com.example.batch.orchestrator.infrastructure.mq;

import com.example.batch.orchestrator.application.engine.DefaultScheduleForwarder;
import com.example.batch.orchestrator.application.engine.ScheduleForwarderResult;
import com.example.batch.orchestrator.application.plan.SchedulePlan;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPollScheduler {

    private final DefaultScheduleForwarder scheduleForwarder;
    private final OutboxPublishCircuitBreaker outboxPublishCircuitBreaker;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${batch.outbox.poll-interval-millis:5000}")
    @SchedulerLock(name = "outbox_poll", lockAtMostFor = "PT1M", lockAtLeastFor = "PT3S")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!outboxPublishCircuitBreaker.allowNow()) {
                log.warn("Outbox 投递熔断已打开：跳过推进（cooldown 中）");
                return;
            }
            ScheduleForwarderResult result = scheduleForwarder.advance(new SchedulePlan());
            outboxPublishCircuitBreaker.onAdvanceResult(result == null ? 0 : result.totalFailures());
        } finally {
            running.set(false);
        }
    }
}
