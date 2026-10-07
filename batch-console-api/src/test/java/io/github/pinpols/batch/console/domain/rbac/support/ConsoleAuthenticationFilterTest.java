package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.console.application.observability.SseTicketService;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

// SseTicketService used in mock() call below

@ExtendWith(MockitoExtension.class)
@DisplayName("控制台认证过滤器:开关与旁路模式放行, cookie 与工单票据鉴权, 非法凭据返回未授权或禁止")
class ConsoleAuthenticationFilterTest {

  @Mock
  private ConsoleJwtService jwtService;

  @Mock
  private ConsoleSecurityResponseWriter responseWriter;

  @Mock
  private SseTicketService sseTicketService;

  private ConsoleSecurityProperties properties;
  private BatchSecurityProperties batchProperties;

  private ConsoleAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    properties = new ConsoleSecurityProperties();
    properties.setEnabled(true);
    properties.setDefaultTenantId("default-tenant");
    properties.setAllowedTenants(List.of("default-tenant", "t1"));
    properties.setDefaultAuthorities(List.of("ROLE_ADMIN"));

    batchProperties = new BatchSecurityProperties();

    filter = new ConsoleAuthenticationFilter(
        properties, batchProperties, jwtService, responseWriter, sseTicketService);
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("鉴权关闭:未开启旁路时直接放行, 且不写入安全上下文")
  void filter_passesThroughWhenAuthDisabledAndNotTestingOpen() throws Exception {
    properties.setEnabled(false);
    batchProperties.setBypassMode(false);

    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  @DisplayName("旁路模式:放行请求并在退出时清理安全上下文")
  void filter_setsTestingAuthAndContinuesWhenTestingOpen() throws Exception {
    batchProperties.setBypassMode(true);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(properties.getTenantHeader(), "t1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    // SecurityContext should be cleared in finally block
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  @DisplayName("凭据鉴权:仅从只读 cookie 取令牌并通过认证")
  void filter_authenticatesViaHttpOnlyCookie() throws Exception {
    // ADR-030 §D7 Stage B 收尾：JWT 只能通过 HttpOnly cookie 入站，Authorization header 已不识别。
    ConsolePrincipal principal = new ConsolePrincipal("alice", "t1", Set.of("ROLE_ADMIN"));
    when(jwtService.authenticate("valid-jwt")).thenReturn(principal);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new jakarta.servlet.http.Cookie("batch_console_token", "valid-jwt"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(jwtService).authenticate("valid-jwt");
  }

  @Test
  @DisplayName("无效 cookie:令牌解析失败时返回未授权, 且不放行请求")
  void filter_returns401WhenCookieJwtInvalid() throws Exception {
    when(jwtService.authenticate(anyString())).thenThrow(new RuntimeException("expired"));
    doNothing().when(responseWriter).write(any(), any(), any(), anyString());

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new jakarta.servlet.http.Cookie("batch_console_token", "bad-jwt"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(responseWriter).write(eq(response), eq(HttpStatus.UNAUTHORIZED), any(), anyString());
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("失效 cookie:登录与登出路径直接放行, 不返回未授权")
  void filter_passThroughOnInvalidCookie_forPublicAuthPaths() throws Exception {
    // D7 Stage B 切 cookie 后浏览器对 /auth/login 也会自动带过期 cookie。
    // 失效 cookie 不应在 permitAll 端点上 401,否则用户陷死无法重新登录。
    when(jwtService.authenticate(anyString())).thenThrow(new RuntimeException("expired"));

    for (String path : new String[] {"/api/console/auth/login", "/api/console/auth/logout"}) {
      MockHttpServletRequest request = new MockHttpServletRequest();
      request.setRequestURI(path);
      request.setCookies(new jakarta.servlet.http.Cookie("batch_console_token", "bad-jwt"));
      MockHttpServletResponse response = new MockHttpServletResponse();
      FilterChain chain = mock(FilterChain.class);

      filter.doFilterInternal(request, response, chain);

      verify(chain).doFilter(request, response);
      verify(responseWriter, never())
          .write(eq(response), eq(HttpStatus.UNAUTHORIZED), any(), anyString());
    }
  }

  @Test
  @DisplayName("请求头忽略:仅带授权头不再触发令牌解析, 请求按未认证放行")
  void filter_ignoresAuthorizationHeader_afterStageBCleanup() throws Exception {
    // 验证 Authorization header 已不再被识别（D7 Stage B 收尾后只接 cookie）
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer legacy-jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    // Filter 把请求当未认证放行，下游 @PreAuthorize 会拒绝。jwtService 不应被调用
    verify(chain).doFilter(request, response);
    verify(jwtService, never()).authenticate(anyString());
  }

  @Test
  @DisplayName("租户不允许:旁路模式下非白名单租户返回禁止, 且不放行")
  void filter_returns403WhenTenantNotAllowedInBypassMode() throws Exception {
    batchProperties.setBypassMode(true);
    doNothing().when(responseWriter).write(any(), any(), any(), anyString());

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(properties.getTenantHeader(), "not-allowed-tenant");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(responseWriter).write(eq(response), eq(HttpStatus.FORBIDDEN), any(), anyString());
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("历史角色:旁路模式携带旧角色头时返回禁止, 且不放行")
  void filter_returns403WhenBypassHeaderContainsLegacyRole() throws Exception {
    batchProperties.setBypassMode(true);
    doNothing().when(responseWriter).write(any(), any(), any(), anyString());

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(properties.getTenantHeader(), "t1");
    request.addHeader(properties.getRoleHeader(), "ROLE_ADMIN,ROLE_USER");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(responseWriter).write(eq(response), eq(HttpStatus.FORBIDDEN), any(), anyString());
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("无令牌旁路:旁路模式未带令牌时直接放行")
  void filter_passesThroughWhenTestingOpenAndNoToken() throws Exception {
    batchProperties.setBypassMode(true);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(properties.getTenantHeader(), "t1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
  }

  @Test
  @DisplayName("查询参数令牌:不再作为凭据来源, 请求直接放行")
  void filter_ignoresQueryParamToken() throws Exception {
    // 5.4: URL query token 已移除，?token= 不再作为 JWT 来源
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("token", "query-jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verifyNoInteractions(jwtService);
    verify(chain).doFilter(request, response);
  }

  @Test
  @DisplayName("工单票据:首次校验并缓存到请求属性, 异步派发复用不再校验")
  void filter_validatesSseTicketAndCachesResultForAsyncDispatch() throws Exception {
    // R4-P1-1：validate 返回 TicketPayload，含签发时角色集
    io.github.pinpols.batch.console.application.observability.SseTicketService.TicketPayload
        payload =
            new io.github.pinpols.batch.console.application.observability.SseTicketService
                .TicketPayload("alice", "t1", Set.of("ROLE_TENANT_USER"));
    when(sseTicketService.validate("ticket-1")).thenReturn(payload);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("ticket", "ticket-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);
    verify(sseTicketService, times(1)).validate("ticket-1");
    verify(chain, times(1)).doFilter(request, response);
    assertThat(request.getAttribute(ConsoleAuthenticationFilter.TICKET_PRINCIPAL_ATTR))
        .isEqualTo(payload);

    // 第二次 dispatch:filter 应从 request attribute 缓存里取,不再调用 sseTicketService.validate
    filter.doFilterInternal(request, response, chain);

    verify(sseTicketService, times(1)).validate("ticket-1");
    verify(chain, times(2)).doFilter(request, response);
  }

  @Test
  @DisplayName("历史角色票据:工单票据含旧角色时返回未授权, 且不放行")
  void filter_returns401WhenSseTicketContainsLegacyRole() throws Exception {
    SseTicketService.TicketPayload payload =
        new SseTicketService.TicketPayload("alice", "t1", Set.of("ROLE_USER"));
    when(sseTicketService.validate("legacy-ticket")).thenReturn(payload);
    doNothing().when(responseWriter).write(any(), any(), any(), anyString());

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("ticket", "legacy-ticket");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(responseWriter).write(eq(response), eq(HttpStatus.UNAUTHORIZED), any(), anyString());
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("旁路模式无效凭据:主动携带的令牌解析失败仍返回未授权")
  void filter_returns401WhenJwtInvalid_evenInBypassMode() throws Exception {
    // P1-2(2026-06-03): bypass-mode 不再因 JWT 解析失败而自动 admin。
    // 客户端"主动带了凭据"必须按凭据严格校验,避免 prod profile 漂移 + bypass=true 双失守。
    batchProperties.setBypassMode(true);
    when(jwtService.authenticate(anyString())).thenThrow(new RuntimeException("expired"));
    doNothing().when(responseWriter).write(any(), any(), any(), anyString());

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new jakarta.servlet.http.Cookie("batch_console_token", "bad-jwt"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(responseWriter).write(eq(response), eq(HttpStatus.UNAUTHORIZED), any(), anyString());
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("上下文清理:请求处理结束后安全上下文被清空")
  void filter_clearSecurityContextInFinally() throws Exception {
    batchProperties.setBypassMode(true);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(properties.getTenantHeader(), "t1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
