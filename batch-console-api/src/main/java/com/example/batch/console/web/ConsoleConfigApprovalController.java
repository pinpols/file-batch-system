package com.example.batch.console.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.application.ConsoleConfigApprovalApplicationService;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.ConfigApprovalActionRequest;
import com.example.batch.console.web.request.ConfigReleaseApprovalSubmitRequest;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/console/config")
@RequiredArgsConstructor
public class ConsoleConfigApprovalController {

    private final ConsoleConfigApprovalApplicationService applicationService;
    private final ConsoleResponseFactory responseFactory;

    @PostMapping("/releases/{releaseId}/submit-approval")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<Map<String, Object>> submitApproval(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable Long releaseId,
            @Valid @RequestBody ConfigReleaseApprovalSubmitRequest request) {
        return responseFactory.success(applicationService.submit(releaseId, request));
    }

    @GetMapping("/releases/{releaseId}/approval")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
    public CommonResponse<Map<String, Object>> approvalDetail(
            @PathVariable Long releaseId, @RequestParam("tenantId") String tenantId) {
        return responseFactory.success(applicationService.detail(tenantId, releaseId));
    }

    @PostMapping("/approvals/{approvalId}/approve")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<Map<String, Object>> approve(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable Long approvalId,
            @Valid @RequestBody ConfigApprovalActionRequest request) {
        return responseFactory.success(applicationService.approve(approvalId, request));
    }

    @PostMapping("/approvals/{approvalId}/reject")
    @PreAuthorize("hasAuthority('ROLE_ADMIN')")
    public CommonResponse<Map<String, Object>> reject(
            @RequestHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @PathVariable Long approvalId,
            @Valid @RequestBody ConfigApprovalActionRequest request) {
        return responseFactory.success(applicationService.reject(approvalId, request));
    }
}
