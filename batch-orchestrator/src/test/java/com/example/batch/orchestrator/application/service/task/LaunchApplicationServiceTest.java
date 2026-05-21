package com.example.batch.orchestrator.application.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.dto.LaunchRequest;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.orchestrator.application.ratelimit.RateLimitAction;
import com.example.batch.orchestrator.application.ratelimit.TenantActionRateLimiter;
import com.example.batch.orchestrator.service.LaunchService;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 守护 launch 入口的租户级限流:
 *
 * <ul>
 *   <li>tryConsume 失败 → 抛 429,不调底层 LaunchService
 *   <li>tryConsume 通过 → 透传到 LaunchService
 * </ul>
 */
class LaunchApplicationServiceTest {

  @Mock private LaunchService launchService;
  @Mock private TenantActionRateLimiter rateLimiter;

  private LaunchApplicationService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new LaunchApplicationService(launchService, rateLimiter);
  }

  private LaunchRequest request(String tenantId) {
    return new LaunchRequest(
        tenantId, "j", LocalDate.now(), TriggerType.SCHEDULED, "req", "trace", Map.of());
  }

  @Test
  @DisplayName("限流失败 → 抛 429,LaunchService 不被调用")
  void throws429WhenRateLimited() {
    LaunchRequest req = request("ta");
    when(rateLimiter.tryConsume(eq("ta"), eq(RateLimitAction.LAUNCH))).thenReturn(false);

    assertThatThrownBy(() -> service.launch(req))
        .isInstanceOf(ResponseStatusException.class)
        .extracting("statusCode")
        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

    verify(launchService, never()).launch(any());
  }

  @Test
  @DisplayName("限流通过 → 透传到 LaunchService,返回其结果")
  void delegatesToLaunchServiceWhenAllowed() {
    LaunchRequest req = request("ta");
    LaunchResponse expected = new LaunchResponse("inst-001", "trace");
    when(rateLimiter.tryConsume(eq("ta"), eq(RateLimitAction.LAUNCH))).thenReturn(true);
    when(launchService.launch(eq(req))).thenReturn(expected);

    LaunchResponse result = service.launch(req);
    assertThat(result).isSameAs(expected);
  }

  @Test
  @DisplayName("限流 key 使用 request.tenantId(不漂移到其他租户)")
  void rateLimitsByRequestTenantId() {
    LaunchRequest req = request("tenant-X");
    when(rateLimiter.tryConsume(eq("tenant-X"), eq(RateLimitAction.LAUNCH))).thenReturn(false);

    assertThatThrownBy(() -> service.launch(req)).isInstanceOf(ResponseStatusException.class);
    verify(rateLimiter).tryConsume(eq("tenant-X"), eq(RateLimitAction.LAUNCH));
  }
}
