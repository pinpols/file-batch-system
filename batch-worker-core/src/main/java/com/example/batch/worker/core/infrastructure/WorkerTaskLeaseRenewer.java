package com.example.batch.worker.core.infrastructure;

import com.example.batch.worker.core.support.ActiveTaskLeaseRegistry;
import com.example.batch.worker.core.support.TaskExecutionClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerTaskLeaseRenewer {

    private final ActiveTaskLeaseRegistry activeTaskLeaseRegistry;
    private final TaskExecutionClient taskExecutionClient;

    @Scheduled(fixedDelayString = "${batch.worker.lease.renew-interval-millis:10000}")
    public void renewActiveTaskLeases() {
        for (ActiveTaskLeaseRegistry.ActiveTaskLease activeTaskLease : activeTaskLeaseRegistry.snapshot()) {
            try {
                boolean renewed = taskExecutionClient.renewLease(
                        activeTaskLease.getTenantId(),
                        Long.valueOf(activeTaskLease.getTaskId()),
                        activeTaskLease.getWorkerId()
                );
                if (!renewed) {
                    log.warn("task lease renew rejected: tenantId={}, taskId={}, workerId={}",
                            activeTaskLease.getTenantId(), activeTaskLease.getTaskId(), activeTaskLease.getWorkerId());
                }
            } catch (Exception exception) {
                log.warn("task lease renew failed: tenantId={}, taskId={}, workerId={}, error={}",
                        activeTaskLease.getTenantId(),
                        activeTaskLease.getTaskId(),
                        activeTaskLease.getWorkerId(),
                        exception.getMessage(),
                        exception);
            }
        }
    }
}
