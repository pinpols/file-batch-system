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
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("控制台限流过滤器: 登录、高开销与文件操作三个维度的拦截")
class ConsoleRateLimitFilterTest {

  @Mock
  private SlidingWindowRateLimiter rateLimiter;

  @Mock
  private FilterChain filterChain;

  @Mock
  private ConsoleSecurityResponseWriter responseWriter;

  private ConsoleRateLimitFilter filter;

  @BeforeEach
  void setUp() {
    ConsoleRateLimitProperties props = new ConsoleRateLimitProperties();
    props.setLoginIpLimitPerMinute(3);
    props.setSensitiveOpUserLimitPerMinute(5);
    filter = new ConsoleRateLimitFilter(
        rateLimiter,
        props,
        responseWriter,
        new ConsoleSecurityProperties(),
        RedisRateLimitCircuitBreaker.forTesting(props));
  }

  // ── disabled ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("限流关闭时连续五次登录请求全部放行, 且不触达限流器")
  void shouldPassThroughWhenDisabled() throws Exception {
    ConsoleRateLimitProperties disabledProps = new ConsoleRateLimitProperties();
    disabledProps.setEnabled(false);
    ConsoleRateLimitFilter disabledFilter = new ConsoleRateLimitFilter(
        rateLimiter,
        disabledProps,
        responseWriter,
        new ConsoleSecurityProperties(),
        RedisRateLimitCircuitBreaker.forTesting(disabledProps));

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
  @DisplayName("登录限流放行时请求继续执行, 且不写任何错误响应")
  void shouldAllowLoginWhenRateLimiterPermits() throws Exception {
    when(rateLimiter.tryAcquire(contains("login:ip:"), anyInt())).thenReturn(true);

    MockHttpServletRequest request = loginRequest("10.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(any(), any());
    verifyNoInteractions(responseWriter);
  }

  @Test
  @DisplayName("登录限流拒绝时请求被中断, 返回请求过多状态且提示访问频繁")
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
  @DisplayName("启用转发头信任时, 取转发链首个地址作为限流键")
  void shouldResolveXForwardedForAsIpKeyWhenTrustEnabled() throws Exception {
    ConsoleSecurityProperties trustProps = new ConsoleSecurityProperties();
    trustProps.setTrustForwardedHeaders(true);
    ConsoleRateLimitProperties limitProps = new ConsoleRateLimitProperties();
    limitProps.setLoginIpLimitPerMinute(3);
    ConsoleRateLimitFilter trustingFilter = new ConsoleRateLimitFilter(
        rateLimiter,
        limitProps,
        responseWriter,
        trustProps,
        RedisRateLimitCircuitBreaker.forTesting(limitProps));

    when(rateLimiter.tryAcquire(contains("203.0.113.5"), anyInt())).thenReturn(true);

    MockHttpServletRequest request = loginRequest("10.0.0.3");
    request.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1");

    trustingFilter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimiter).tryAcquire(contains("203.0.113.5"), anyInt());
  }

  /** 默认 trust=false 时, XFF header 必须被忽略,key 走 remoteAddr 防伪造(curl -H 'XFF: 1.2.3.4' 绕过限流)。 */
  @Test
  @DisplayName("未启用转发头信任时忽略转发头, 以远端地址作为限流键")
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
  @DisplayName("查询方法的登录路径不参与限流, 请求直接放行且不触达限流器")
  void shouldNotRateLimitGetRequests() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/console/auth/login");
    request.setRemoteAddr("1.2.3.4");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(filterChain).doFilter(any(), any());
    verifyNoInteractions(rateLimiter);
  }

  @Test
  @DisplayName("非登录的提交路径不参与登录限流, 请求直接放行且不触达限流器")
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
  @DisplayName("已认证用户超出高开销接口配额时被拦截, 返回请求过多状态并提示访问频繁")
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
  @DisplayName("已认证用户在高开销接口配额内时请求正常放行")
  void shouldAllowExpensiveEndpointWhenUnderLimit() throws Exception {
    authenticateAs("alice");
    when(rateLimiter.tryAcquire(eq("expensive:user:alice"), anyInt())).thenReturn(true);

    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/console/config/sync/export");
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(filterChain).doFilter(any(), any());
  }

  @Test
  @DisplayName("前端遥测复用高开销接口配额, 防止高频上报压垮 Console")
  void shouldRateLimitTelemetryEndpointAsExpensiveOperation() throws Exception {
    authenticateAs("alice");
    when(rateLimiter.tryAcquire(eq("expensive:user:alice"), anyInt())).thenReturn(false);

    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/console/telemetry/events");
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
  @DisplayName("控制台认证主体按用户名生成限流键, 不把租户和角色文本混入身份")
  void shouldUseConsolePrincipalUsernameAsRateLimitIdentity() throws Exception {
    ConsolePrincipal principal = new ConsolePrincipal("alice", "tenant-a", Set.of("ROLE_AUDITOR"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
    when(rateLimiter.tryAcquire(eq("expensive:user:alice"), anyInt())).thenReturn(true);

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/console/reports/excel");
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(rateLimiter).tryAcquire(eq("expensive:user:alice"), anyInt());
    verify(filterChain).doFilter(any(), any());
  }

  @Test
  @DisplayName("未认证请求不适用高开销接口限流, 放行且不触达限流器")
  void shouldNotApplyExpensiveLimitToUnauthenticatedRequest() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/console/reports/excel");
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(filterChain).doFilter(any(), any());
    verifyNoInteractions(rateLimiter);
  }

  // ── file-op endpoint rate limit (下载/错误导出/归档/重派/到达组,按用户,任意方法) ──────────

  @Test
  @DisplayName("已认证用户超出文件操作配额时下载被拦截, 返回请求过多状态并提示访问频繁")
  void shouldRejectFileDownloadWhenUserOverLimit() throws Exception {
    authenticateAs("alice");
    when(rateLimiter.tryAcquire(eq("fileop:user:alice"), anyInt())).thenReturn(false);

    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/console/files/F-1/download");
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
  @DisplayName("已认证用户在文件操作配额内时归档请求放行")
  void shouldAllowFileMutationWhenUnderLimit() throws Exception {
    authenticateAs("alice");
    when(rateLimiter.tryAcquire(eq("fileop:user:alice"), anyInt())).thenReturn(true);

    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/console/files/archive");
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    verify(filterChain).doFilter(any(), any());
  }

  /** presign 下载（fs-download）无登录态，取不到用户名 → 文件操作限流自然跳过，不应触达 rateLimiter。 */
  @Test
  @DisplayName("无登录态的预签名下载不适用文件操作限流, 放行且不触达限流器")
  void shouldNotApplyFileOpLimitToUnauthenticatedPresignDownload() throws Exception {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/console/files/fs-download");
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
