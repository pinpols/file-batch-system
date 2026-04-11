package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleTriggerProxyService;
import com.example.batch.console.service.ConsoleResponseFactory;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/console/triggers")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsoleTriggerController {

    private final ConsoleTriggerProxyService triggerProxyService;
    private final ConsoleResponseFactory responseFactory;

    @GetMapping
    @PreAuthorize(
            "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN',"
                + " 'ROLE_TENANT_USER')")
    public CommonResponse<List<Object>> list() {
        return responseFactory.success(triggerProxyService.triggerList());
    }

    @PostMapping("/{jobCode}/register")
    public CommonResponse<Map<String, String>> register(
            @PathVariable String jobCode, @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(
                triggerProxyService.triggerAction(tenantId, jobCode, "register"));
    }

    @PostMapping("/{jobCode}/unregister")
    public CommonResponse<Map<String, String>> unregister(
            @PathVariable String jobCode, @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(
                triggerProxyService.triggerAction(tenantId, jobCode, "unregister"));
    }

    @PostMapping("/{jobCode}/pause")
    public CommonResponse<Map<String, String>> pause(
            @PathVariable String jobCode, @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(
                triggerProxyService.triggerAction(tenantId, jobCode, "pause"));
    }

    @PostMapping("/{jobCode}/resume")
    public CommonResponse<Map<String, String>> resume(
            @PathVariable String jobCode, @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(
                triggerProxyService.triggerAction(tenantId, jobCode, "resume"));
    }
}
