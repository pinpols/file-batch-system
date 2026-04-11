package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleDashboardQueryService;
import com.example.batch.console.service.ConsoleResponseFactory;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/console/dashboard")
@PreAuthorize(
        "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleDashboardController {

    private final ConsoleDashboardQueryService queryService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping("/job-stats")
    public CommonResponse<Map<String, Object>> jobStats(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.jobStats(tenantId, days));
    }

    @GetMapping("/trigger-stats")
    public CommonResponse<Map<String, Object>> triggerStats(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.triggerStats(tenantId, days));
    }

    @GetMapping("/worker-load")
    public CommonResponse<Map<String, Object>> workerLoad(
            @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(queryService.workerLoad(tenantId));
    }

    @GetMapping("/alert-trend")
    public CommonResponse<Map<String, Object>> alertTrend(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.alertTrend(tenantId, days));
    }

    @GetMapping("/sla-compliance")
    public CommonResponse<Map<String, Object>> slaCompliance(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.slaCompliance(tenantId, days));
    }

    /** SLA 报表：按 job 维度统计成功率、SLA 达标率、平均/最大耗时。 */
    @GetMapping("/sla-report")
    public CommonResponse<Map<String, Object>> slaReport(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "7") int days) {
        return responseFactory.success(queryService.slaReport(tenantId, days));
    }

    /** 执行进度查询（轻量）：按 jobCode + bizDate 返回实例进度。面向业务方。 */
    @GetMapping("/execution-progress")
    public CommonResponse<List<Map<String, Object>>> executionProgress(
            @RequestParam("tenantId") String tenantId,
            @RequestParam("jobCode") String jobCode,
            @RequestParam("bizDate") String bizDate) {
        return responseFactory.success(queryService.executionProgress(tenantId, jobCode, bizDate));
    }

    /** 租户用量统计：配置数量 + 近期实例/文件处理量。 */
    @GetMapping("/tenant-usage")
    public CommonResponse<Map<String, Object>> tenantUsage(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return responseFactory.success(queryService.tenantUsage(tenantId, days));
    }
}
