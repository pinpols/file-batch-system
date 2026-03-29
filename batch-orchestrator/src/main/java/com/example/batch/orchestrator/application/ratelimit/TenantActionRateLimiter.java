package com.example.batch.orchestrator.application.ratelimit;

import com.example.batch.orchestrator.config.RateLimitProperties;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TenantActionRateLimiter {

    private final RateLimitProperties rateLimitProperties;

    // 更方便测试；当前未注入自定义 clock，直接使用系统时间
    private final Clock clock = Clock.systemDefaultZone();

    private final TokenBucketRateLimiter limiter = new TokenBucketRateLimiter();

    public boolean tryConsume(String tenantId, RateLimitAction action) {
        if (!rateLimitProperties.isEnabled()) {
            return true;
        }
        if (tenantId == null || tenantId.isBlank()) {
            return true;
        }
        long max;
        if (action == RateLimitAction.LAUNCH) {
            max = rateLimitProperties.getMaxNewRequestsPerTenantPerMinute();
        } else if (action == RateLimitAction.DISPATCH_RELEASE) {
            max = rateLimitProperties.getMaxReleaseRequestsPerTenantPerMinute();
        } else {
            max = 0;
        }
        String key = tenantId + ":" + action.name();
        return limiter.tryConsume(key, max, clock.millis());
    }
}

