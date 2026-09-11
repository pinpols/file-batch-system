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
 * 任务启动应用服务，区分同步 HTTP 入口与已持久化的 Kafka 队列入口。
 *
 * <p>同步 HTTP 入口通过 {@link TenantActionRateLimiter} 消费 {@code LAUNCH} 令牌，防止调用方绕过 Trigger
 * 准入直接压垮控制面。异步入口已经过 Trigger admission、事务 Outbox 和 Kafka 释放预算，不能再次套用每分钟
 * HTTP 防盗刷阈值，否则队列消费吞吐会被默认 3000/min 永久限制。异步入口仍受租户 quota、资源队列和 worker
 * 容量约束，不是无限制执行旁路。
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

  /**
   * 消费 {@code batch.trigger.launch.v1} 的内部入口。
   *
   * <p>调用方必须是平台管理的 Kafka consumer；该方法只跳过面向同步 HTTP 的防盗刷令牌桶，不跳过底层 launch
   * 的幂等、租户隔离、配额和调度准入。
   */
  public LaunchResponse launchFromTrustedQueue(LaunchRequest request) {
    return launchService.launch(request);
  }
}
