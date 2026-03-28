package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleOpsApplicationService;
import com.example.batch.console.mapper.AlertEventMapper;
import com.example.batch.console.mapper.ApprovalCommandMapper;
import com.example.batch.console.mapper.JobInstanceMapper;
import com.example.batch.console.mapper.OutboxDeliveryLogMapper;
import com.example.batch.console.mapper.OutboxRetryLogMapper;
import com.example.batch.console.mapper.WorkerRegistryMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.response.ConsoleOpsSummaryResponse;
import com.example.batch.common.enums.JobInstanceStatus;
import com.example.batch.common.enums.WorkerRegistryStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultConsoleOpsApplicationService implements ConsoleOpsApplicationService {

    private final ConsoleTenantGuard tenantGuard;
    private final ApprovalCommandMapper approvalCommandMapper;
    private final JobInstanceMapper jobInstanceMapper;
    private final WorkerRegistryMapper workerRegistryMapper;
    private final OutboxRetryLogMapper outboxRetryLogMapper;
    private final OutboxDeliveryLogMapper outboxDeliveryLogMapper;
    private final AlertEventMapper alertEventMapper;

    @Override
    public ConsoleOpsSummaryResponse summary(String tenantId) {
        String resolvedTenantId = tenantGuard.resolveTenant(tenantId);
        long pendingApprovals = approvalCommandMapper.countByStatus(resolvedTenantId, "PENDING");
        long openAlerts = alertEventMapper.countByStatus(resolvedTenantId, "OPEN");
        long criticalAlerts = alertEventMapper.countBySeverityAndStatus(resolvedTenantId, "CRITICAL", "OPEN");
        long runningJobs = jobInstanceMapper.countByStatuses(resolvedTenantId, List.of(JobInstanceStatus.RUNNING.code()));
        long failedJobs = jobInstanceMapper.countByStatuses(
                resolvedTenantId,
                List.of(JobInstanceStatus.FAILED.code(), JobInstanceStatus.PARTIAL_FAILED.code())
        );
        long slaBreaches = jobInstanceMapper.countSlaBreaches(
                resolvedTenantId,
                List.of(
                        JobInstanceStatus.CREATED.code(),
                        JobInstanceStatus.WAITING.code(),
                        JobInstanceStatus.READY.code(),
                        JobInstanceStatus.RUNNING.code(),
                        JobInstanceStatus.PARTIAL_FAILED.code()
                )
        );
        long onlineWorkers = workerRegistryMapper.countByStatus(resolvedTenantId, WorkerRegistryStatus.ONLINE.code());
        long drainingWorkers = workerRegistryMapper.countByStatus(resolvedTenantId, WorkerRegistryStatus.DRAINING.code());
        long offlineWorkers = workerRegistryMapper.countByStatus(resolvedTenantId, WorkerRegistryStatus.OFFLINE.code())
                + workerRegistryMapper.countByStatus(resolvedTenantId, WorkerRegistryStatus.DECOMMISSIONED.code());
        long outboxRetryBacklog = outboxRetryLogMapper.countByStatuses(
                resolvedTenantId,
                List.of("WAITING", "RUNNING", "FAILED")
        );
        long outboxDeliveryFailures = outboxDeliveryLogMapper.countByStatus(resolvedTenantId, "FAILED");

        return new ConsoleOpsSummaryResponse(
                resolvedTenantId,
                pendingApprovals,
                openAlerts,
                criticalAlerts,
                runningJobs,
                failedJobs,
                slaBreaches,
                onlineWorkers,
                drainingWorkers,
                offlineWorkers,
                outboxRetryBacklog,
                outboxDeliveryFailures
        );
    }
}
