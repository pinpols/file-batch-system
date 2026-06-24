package io.github.pinpols.batch.console.support.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.console.config.ConsoleRateLimitProperties;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class ConsoleRateLimitFilterTest {

  @Mock private SlidingWindowRateLimiter rateLimiter;

  @Mock private FilterChain filterChain;

  @Mock private ConsoleSecurityResponseWriter responseWriter;

  private ConsoleRateLimitFilter filter;

  @BeforeEach
  void setUp() {
    ConsoleRateLimitProperties props = new ConsoleRateLimitProperties();
    props.setLoginIpLimitPerMinute(3);
    props.setSensitiveOpUserLimitPerMinute(5);
    filter =
        new ConsoleRateLimitFilter(
            rateLimiter, props, responseWriter, new ConsoleSecurityProperties());
  }

  // ── disabled ──────────────────────────────────────────────────────────────

  @Test
  void shouldPassThroughWhenDisabled() throws Exception {
    ConsoleRateLimitProperties disabledProps = new ConsoleRateLimitProperties();
    disabledProps.setEnabled(false);
    ConsoleRateLimitFilter disabledFilter =
        new ConsoleRateLimitFilter(
            rateLimiter, disabledProps, responseWriter, new ConsoleSecurityProperties());

    MockHttpServletRequest request = loginRequest("1.2.3.4");
    MockHttpServletResponse response = new MockHttpServletResponse();

    for (int i = 0; i < 5; i++) {
      disabledFilter.doFilter(request, response, filterChain);
    }
    verify(filterChain, times(5)).doFilter(any(), any());
    verifyNoInteractions(rateLimiter);
  }

  // ── login IP rate limit ───────────────────────────────────────────────────

  @Test
  void shouldAllowLoginWhenRateLimiterPermits() throws Exception {
    when(rateLimiter.tryAcquire(contains("login:ip:"), anyInt())).thenReturn(true);

    MockHttpServletRequest request = loginRequest("10.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(any(), any());
    verifyNoInteractions(responseWriter);
  }

  @Test
  void shouldRejectLoginWhenRateLimiterDenies() throws Exception {
    when(rateLimiter.tryAcquire(contains("login:ip:"), anyInt())).thenReturn(false);

    MockHttpServletRequest request = loginRequest("10.0.0.2");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, filterChain);

    verify(filterChain, never()).doFilter(any(), any());
    verify(responseWriter)
        .write(
            any(HttpServletResponse.class),
            eq(HttpStatus.TOO_MANY_REQUESTS),
            eq(ResultCode.RATE_LIMITED),
            contains("频繁"));
  }

  /**
   * trust-forwarded-headers=true(应用挂在受信反代/Ingress 后)时, XFF 第一段作为客户端真实 IP 作限流 key。
   *
   * <p>默认实例(本类大多数 case)是 false,直接走 remoteAddr 防伪造;本 case 单独构造启用 trust 的 filter 实例覆盖 resolveClientIp
   * 的 XFF 分支。
   */
  @Test
  void shouldResolveXForwardedForAsIpKeyWhenTrustEnabled() throws Exception {
    ConsoleSecurityProperties trustProps = new ConsoleSecurityProperties();
    trustProps.setTrustForwardedHeaders(true);
    ConsoleRateLimitProperties limitProps = new ConsoleRateLimitProperties();
    limitProps.setLoginIpLimitPerMinute(3);
    ConsoleRateLimitFilter trustingFilter =
        new ConsoleRateLimitFilter(rateLimiter, limitProps, responseWriter, trustProps);

    when(rateLimiter.tryAcquire(contains("203.0.113.5"), anyInt())).thenReturn(true);

    MockHttpServletRequest request = loginRequest("10.0.0.3");
    request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");

    trustingFilter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimiter).tryAcquire(contains("203.0.113.5"), anyInt());
  }

  /** 默认 trust=false 时, XFF header 必须被忽略,key 走 remoteAddr 防伪造(curl -H 'XFF: 1.2.3.4' 绕过限流)。 */
  @Test
  void shouldIgnoreXForwardedForWhenTrustDisabled() throws Exception {
    when(rateLimiter.tryAcquire(contains("10.0.0.3"), anyInt())).thenReturn(true);

    MockHttpServletRequest request = loginRequest("10.0.0.3");
    request.addHeader("X-Forwarded-For", "203.0.113.5"); // 伪造伪客户端 IP — 默认应忽略

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimiter).tryAcquire(contains("10.0.0.3"), anyInt());
    verify(rateLimiter, never()).tryAcquire(contains("203.0.113.5"), anyInt());
  }

  // ── non-login requests not limited ───────────────────────────────────────

  @Test
  void shouldNotRateLimitGetRequests() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/console/auth/login");
    request.setRemoteAddr("1.2.3.4");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(any(), any());
    verifyNoInteractions(rateLimiter);
  }

  @Test
  void shouldNotRateLimitOtherPostEndpoints() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/console/jobs/launch");
    request.setRemoteAddr("1.2.3.4");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(filterChain).doFilter(any(), any());
    verifyNoInteractions(rateLimiter);
  }

  // ── expensive endpoint rate limit (导出/导入/Excel/报表,按用户,任意方法) ──────────

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldRejectExpensiveEndpointWhenUserOverLimit() throws Exception {
    authenticateAs("alice");
    when(rateLimiter.tryAcquire(eq("expensive:user:alice"), anyInt())).thenReturn(false);

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/console/reports/excel");
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(filterChain, never()).doFilter(any(), any());
    verify(responseWriter)
        .write(
            any(HttpServletResponse.class),
            eq(HttpStatus.TOO_MANY_REQUESTS),
            eq(ResultCode.RATE_LIMITED),
            contains("频繁"));
  }

  @Test
  void shouldAllowExpensiveEndpointWhenUnderLimit() throws Exception {
    authenticateAs("alice");
    when(rateLimiter.tryAcquire(eq("expensive:user:alice"), anyInt())).thenReturn(true);

    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/console/config/sync/export");
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(filterChain).doFilter(any(), any());
  }

  @Test
  void shouldNotApplyExpensiveLimitToUnauthenticatedRequest() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/console/reports/excel");
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(filterChain).doFilter(any(), any());
    verifyNoInteractions(rateLimiter);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private void authenticateAs(String username) {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a", List.of()));
  }

  private MockHttpServletRequest loginRequest(String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/console/auth/login");
    request.setRemoteAddr(remoteAddr);
    return request;
  }
}
