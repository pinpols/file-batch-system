package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleDashboardQueryService;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/dashboard")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleDashboardController {

    private final ConsoleDashboardQueryService queryService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/job-stats")
    public CommonResponse<Map<String, Object>> jobStats(@RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.jobStats(tenantId, days));
    }

    @GetMapping("/trigger-stats")
    public CommonResponse<Map<String, Object>> triggerStats(@RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.triggerStats(tenantId, days));
    }

    @GetMapping("/worker-load")
    public CommonResponse<Map<String, Object>> workerLoad(@RequestParam("tenantId") String tenantId) {
        return responseFactory.success(queryService.workerLoad(tenantId));
    }

    @GetMapping("/alert-trend")
    public CommonResponse<Map<String, Object>> alertTrend(@RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.alertTrend(tenantId, days));
    }

    @GetMapping("/sla-compliance")
    public CommonResponse<Map<String, Object>> slaCompliance(@RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.slaCompliance(tenantId, days));
    }
}
