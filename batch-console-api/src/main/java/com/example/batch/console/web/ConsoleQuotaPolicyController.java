package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.model.PageResponse;
import com.example.batch.console.application.ConsoleQuotaPolicyApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.QuotaPolicySaveRequest;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/console/quota-policies")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleQuotaPolicyController {

    private final ConsoleQuotaPolicyApplicationService quotaPolicyApplicationService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping
    public CommonResponse<PageResponse<Map<String, Object>>> list(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "policyCode", required = false) String policyCode,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return responseFactory.success(
                quotaPolicyApplicationService.list(
                        tenantId, policyCode, enabled, pageNo, pageSize));
    }

    @PostMapping
    public CommonResponse<Map<String, Object>> create(
            @Valid @RequestBody QuotaPolicySaveRequest request) {
        return responseFactory.success(quotaPolicyApplicationService.create(request));
    }

    @PutMapping("/{id}")
    public CommonResponse<Map<String, Object>> update(
            @PathVariable Long id, @Valid @RequestBody QuotaPolicySaveRequest request) {
        return responseFactory.success(quotaPolicyApplicationService.update(id, request));
    }

    @PostMapping("/{id}/toggle")
    public CommonResponse<Void> toggle(
            @PathVariable Long id,
            @RequestParam("tenantId") String tenantId,
            @RequestParam("enabled") Boolean enabled) {
        quotaPolicyApplicationService.toggle(id, tenantId, enabled);
        return responseFactory.success(null);
    }
}
