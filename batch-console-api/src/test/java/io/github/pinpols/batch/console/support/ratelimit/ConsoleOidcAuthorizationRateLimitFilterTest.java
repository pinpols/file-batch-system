package io.github.pinpols.batch.console.support.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("OIDC 授权发起限流过滤器")
class ConsoleOidcAuthorizationRateLimitFilterTest {

  @Mock
  private SlidingWindowRateLimiter rateLimiter;

  @Mock
  private ConsoleSecurityResponseWriter responseWriter;

  @Mock
  private FilterChain filterChain;

  private ConsoleRateLimitProperties properties;
  private ConsoleOidcAuthorizationRateLimitFilter filter;

  @BeforeEach
  void setUp() {
    properties = new ConsoleRateLimitProperties();
    properties.setLoginIpLimitPerMinute(3);
    ConsoleLoginIpRateLimiter loginIpRateLimiter = new ConsoleLoginIpRateLimiter(
        rateLimiter,
        properties,
        new ConsoleSecurityProperties(),
        RedisRateLimitCircuitBreaker.forTesting(properties));
    filter =
        new ConsoleOidcAuthorizationRateLimitFilter(properties, loginIpRateLimiter, responseWriter);
  }

  @Test
  @DisplayName("超限时在授权重定向前返回 429")
  void shouldRejectAuthorizationStart_whenClientIpLimitIsReached() throws Exception {
    when(rateLimiter.tryAcquire(contains("login:ip:10.0.0.9"), eq(3))).thenReturn(false);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/pilot-tenant");
    request.setRemoteAddr("10.0.0.9");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimiter).tryAcquire(contains("login:ip:10.0.0.9"), eq(3));
    verify(filterChain, never()).doFilter(any(), any());
    verify(responseWriter)
        .write(
            any(HttpServletResponse.class),
            eq(HttpStatus.TOO_MANY_REQUESTS),
            eq(ResultCode.RATE_LIMITED),
            contains("频繁"));
  }

  @Test
  @DisplayName("限流关闭时不触达 Redis 并继续过滤器链")
  void shouldPassAuthorizationStart_whenRateLimitingIsDisabled() throws Exception {
    properties.setEnabled(false);
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/oauth2/authorization/pilot-tenant");
    request.setRemoteAddr("10.0.0.9");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimiter, never()).tryAcquire(any(), anyInt());
    verify(filterChain).doFilter(any(), any());
  }
}
