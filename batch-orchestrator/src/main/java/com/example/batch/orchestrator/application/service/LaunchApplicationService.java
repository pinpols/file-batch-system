package com.example.batch.orchestrator.application.service;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.orchestrator.application.ratelimit.RateLimitAction;
import com.example.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import com.example.batch.orchestrator.service.LaunchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class LaunchApplicationService {

  private final LaunchService launchService;
  private final TenantActionRateLimiter tenantActionRateLimiter;

  public LaunchResponse launch(LaunchRequest request) {
    boolean allowed =
        tenantActionRateLimiter.tryConsume(request.tenantId(), RateLimitAction.LAUNCH);
    if (!allowed) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "launch rate limit exceeded");
    }
    return launchService.launch(request);
  }
}
