package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleOpsApplicationService;
import com.example.batch.console.mapper.AlertEventMapper;
import com.example.batch.console.mapper.ApprovalCommandMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DefaultConsoleOpsApplicationService implements ConsoleOpsApplicationService {

    private final ConsoleTenantGuard tenantGuard;
    private final ApprovalCommandMapper approvalCommandMapper;
    private final AlertEventMapper alertEventMapper;

    @Override
    public Map<String, Object> summary(String tenantId) {
        String resolvedTenantId = tenantGuard.resolveTenant(tenantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", resolvedTenantId);
        result.put("pendingApprovals", approvalCommandMapper.countByStatus(resolvedTenantId, "PENDING"));

        // Minimal: reuse existing alert query by status if present; if not set in DB, caller can filter via /query/alerts
        // Here we count OPEN as default operational view.
        long openAlerts = 0;
        try {
            // AlertEventMapper currently lacks count endpoint; fall back to bounded list size.
            openAlerts = alertEventMapper.selectByQuery(new com.example.batch.console.domain.query.AlertEventQuery(
                    resolvedTenantId, null, "OPEN", null, 1000
            )).size();
        } catch (Exception ignored) {
            // keep 0
        }
        result.put("openAlertsApprox", openAlerts);
        return result;
    }
}

