package com.example.batch.console.support.auth;

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

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.support.SseTicketService;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

// SseTicketService used in mock() call below

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ConsoleAuthenticationFilterTest {

  @Mock private ConsoleJwtService jwtService;
  @Mock private ConsoleSecurityResponseWriter responseWriter;
  @Mock private SseTicketService sseTicketService;

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

    filter =
        new ConsoleAuthenticationFilter(
            properties, batchProperties, jwtService, responseWriter, sseTicketService);
    SecurityContextHolder.clearContext();
  }

  @Test
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
  void filter_authenticatesViaBearerToken() throws Exception {
    ConsolePrincipal principal = new ConsolePrincipal("alice", "t1", Set.of("ROLE_ADMIN"));
    when(jwtService.authenticate("valid-jwt")).thenReturn(principal);

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer valid-jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(chain).doFilter(request, response);
    verify(jwtService).authenticate("valid-jwt");
  }

  @Test
  void filter_returns401WhenBearerTokenInvalid() throws Exception {
    when(jwtService.authenticate(anyString())).thenThrow(new RuntimeException("expired"));
    doNothing().when(responseWriter).write(any(), any(), any(), anyString());

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer bad-jwt");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilterInternal(request, response, chain);

    verify(responseWriter).write(eq(response), eq(HttpStatus.UNAUTHORIZED), any(), anyString());
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
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
  void filter_validatesSseTicketAndCachesResultForAsyncDispatch() throws Exception {
    when(sseTicketService.validate("ticket-1")).thenReturn("alice:t1");

    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setParameter("ticket", "ticket-1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    // 首轮 REQUEST 分派：consume ticket，缓存到 attribute
    filter.doFilterInternal(request, response, chain);
    verify(sseTicketService, times(1)).validate("ticket-1");
    verify(chain, times(1)).doFilter(request, response);
    assertThat(request.getAttribute(ConsoleAuthenticationFilter.TICKET_PRINCIPAL_ATTR))
        .isEqualTo("alice:t1");

    // 模拟 ASYNC/ERROR 分派：ticket 已被消费，validate 必须不被再次调用，认证仍要建立
    when(sseTicketService.validate("ticket-1")).thenReturn(null);
    filter.doFilterInternal(request, response, chain);

    // validate 仍只调用过一次（首轮）
    verify(sseTicketService, times(1)).validate("ticket-1");
    // 第二轮 chain 也被调用，证明走到了认证成功路径
    verify(chain, times(2)).doFilter(request, response);
  }

  @Test
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
