package com.example.batch.orchestrator.infrastructure.retry;

import com.example.batch.orchestrator.application.service.RetryGovernanceService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RetryScheduleScheduler {

    private final RetryGovernanceService retryGovernanceService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${batch.retry.poll-interval-millis:10000}")
    public void poll() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            retryGovernanceService.dispatchDueRetries();
        } finally {
            running.set(false);
        }
    }
}
