package com.example.batch.orchestrator.application.ratelimit;

import com.example.batch.orchestrator.config.governance.BatchOrchestratorGovernanceProperties;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.time.Clock;

@Component
@RequiredArgsConstructor
public class TenantActionRateLimiter {

    private final BatchOrchestratorGovernanceProperties governance;
    private final TokenBucketRateLimiter limiter;

    // 更方便测试；当前未注入自定义 clock，直接使用系统时间
    private final Clock clock = Clock.systemDefaultZone();

    public boolean tryConsume(String tenantId, RateLimitAction action) {
        if (!governance.rateLimit().isEnabled()) {
            return true;
        }
        if (tenantId == null || tenantId.isBlank()) {
            return true;
        }
        long max;
        if (action == RateLimitAction.LAUNCH) {
            max = governance.rateLimit().getMaxNewRequestsPerTenantPerMinute();
        } else if (action == RateLimitAction.DISPATCH_RELEASE) {
            max = governance.rateLimit().getMaxReleaseRequestsPerTenantPerMinute();
        } else {
            max = 0;
        }
        return limiter.tryConsume(tenantId, action.name(), max, clock.millis());
    }
}
