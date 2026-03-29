package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.orchestrator.application.ratelimit.RateLimitAction;
import com.example.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import com.example.batch.orchestrator.service.LaunchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/orchestrator")
@RequiredArgsConstructor
public class LaunchController {

    private final LaunchService launchService;
    private final TenantActionRateLimiter tenantActionRateLimiter;

    @PostMapping("/launch")
    public LaunchResponse launch(@RequestBody LaunchRequest request) {
        boolean allowed = tenantActionRateLimiter.tryConsume(request.tenantId(), RateLimitAction.LAUNCH);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "launch rate limit exceeded");
        }
        return launchService.launch(request);
    }
}
