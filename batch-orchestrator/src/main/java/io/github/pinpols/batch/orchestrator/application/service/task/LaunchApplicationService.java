package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.orchestrator.application.ratelimit.RateLimitAction;
import io.github.pinpols.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import io.github.pinpols.batch.orchestrator.service.LaunchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 任务启动应用服务，在调用底层 {@link io.github.pinpols.batch.orchestrator.service.LaunchService} 前
 * 对租户进行启动动作的限流校验。
 *
 * <p>通过 {@link TenantActionRateLimiter} 以 {@code LAUNCH} 动作类型消费令牌； 若令牌耗尽则直接抛出 HTTP 429
 * 异常，防止单租户高频启动压垮调度链路。 限流通过后才执行实际的任务启动逻辑，保证正常流量不受影响。
 */
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
