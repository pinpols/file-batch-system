package com.example.batch.console.support.ratelimit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.console.config.ConsoleRateLimitProperties;
import com.example.batch.console.support.auth.ConsoleSecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
    filter = new ConsoleRateLimitFilter(rateLimiter, props, responseWriter);
  }

  // ── disabled ──────────────────────────────────────────────────────────────

  @Test
  void shouldPassThroughWhenDisabled() throws Exception {
    ConsoleRateLimitProperties disabledProps = new ConsoleRateLimitProperties();
    disabledProps.setEnabled(false);
    ConsoleRateLimitFilter disabledFilter =
        new ConsoleRateLimitFilter(rateLimiter, disabledProps, responseWriter);

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

  @Test
  void shouldResolveXForwardedForAsIpKey() throws Exception {
    when(rateLimiter.tryAcquire(contains("203.0.113.5"), anyInt())).thenReturn(true);

    MockHttpServletRequest request = loginRequest("10.0.0.3");
    request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");

    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimiter).tryAcquire(contains("203.0.113.5"), anyInt());
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

  // ── helpers ───────────────────────────────────────────────────────────────

  private MockHttpServletRequest loginRequest(String remoteAddr) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/console/auth/login");
    request.setRemoteAddr(remoteAddr);
    return request;
  }
}
