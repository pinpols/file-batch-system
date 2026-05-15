package com.example.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.support.SseTicketService;
import com.example.batch.console.support.auth.ConsoleAuthenticationFilter;
import com.example.batch.console.support.auth.ConsoleJwtService;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.example.batch.console.support.auth.ConsoleSecurityResponseWriter;
import com.example.batch.console.support.auth.ConsoleSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class ConsoleSecurityConfigurationTest {

  private ConsoleSecurityProperties properties;
  private BatchSecurityProperties batchSecurityProperties;
  private ConsoleAuthenticationFilter filter;
  private ConsoleSessionRegistry sessionRegistry;
  private ConsoleJwtService jwtService;

  @BeforeEach
  void setUp() {
    properties = new ConsoleSecurityProperties();
    properties.setEnabled(true);
    properties.setJwtIssuer("batch-console-api");
    properties.setJwtSecret("console-jwt-secret");
    properties.setDefaultTenantId("tenant-a");
    properties.setAllowedTenants(new ArrayList<>(List.of("tenant-a")));
    properties.setDefaultAuthorities(new ArrayList<>(List.of("ROLE_ADMIN", "ROLE_AUDITOR")));

    batchSecurityProperties = new BatchSecurityProperties();
    batchSecurityProperties.setBypassMode(false);
    sessionRegistry = Mockito.mock(ConsoleSessionRegistry.class);
    Environment environment = Mockito.mock(Environment.class);
    Mockito.when(environment.getActiveProfiles()).thenReturn(new String[] {"test"});
    jwtService = new ConsoleJwtService(properties, sessionRegistry, environment);
    filter =
        new ConsoleAuthenticationFilter(
            properties,
            batchSecurityProperties,
            jwtService,
            new ConsoleSecurityResponseWriter(new ObjectMapper()),
            Mockito.mock(SseTicketService.class));
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldRejectTenantOutsideAllowedListInBypassMode() throws Exception {
    batchSecurityProperties.setBypassMode(true);
    MockHttpServletRequest request = baseRequest();
    request.addHeader(properties.getTenantHeader(), "tenant-b");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, noOpChain(chainCalled));

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    assertThat(readBody(response)).contains("\"code\":\"FORBIDDEN\"");
    assertThat(chainCalled).isFalse();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void shouldAuthenticateAsAdminInTestingOpenModeWithoutToken() throws Exception {
    batchSecurityProperties.setBypassMode(true);
    MockHttpServletRequest request = baseRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(
        request,
        response,
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            chainCalled.set(true);
            ConsolePrincipal principal =
                (ConsolePrincipal)
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertThat(principal.authorities()).contains("ROLE_ADMIN");
          }
        });

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(chainCalled).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  void shouldAuthenticateWithHttpOnlyCookie() throws Exception {
    // ADR-030 §D7 Stage B 收尾：JWT 通过 HttpOnly cookie batch_console_token 入站
    String token = jwtService.issueToken("bob", "tenant-a", Set.of("ROLE_ADMIN"), 9L).accessToken();
    Mockito.when(sessionRegistry.isCurrentSession("bob", "tenant-a", 9L)).thenReturn(true);

    MockHttpServletRequest request = baseRequest();
    request.setCookies(new jakarta.servlet.http.Cookie("batch_console_token", token));
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(
        request,
        response,
        new FilterChain() {
          @Override
          public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse) {
            chainCalled.set(true);
            ConsolePrincipal principal =
                (ConsolePrincipal)
                    SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            assertThat(principal.username()).isEqualTo("bob");
            assertThat(principal.tenantId()).isEqualTo("tenant-a");
          }
        });

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    assertThat(chainCalled).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  private MockHttpServletRequest baseRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURI("/api/console/ping");
    request.addHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER, "req-001");
    request.addHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER, "trace-001");
    request.setRemoteAddr("127.0.0.1");
    return request;
  }

  private FilterChain noOpChain(AtomicBoolean chainCalled) {
    return new FilterChain() {
      @Override
      public void doFilter(ServletRequest request, ServletResponse response) {
        chainCalled.set(true);
      }
    };
  }

  private String readBody(MockHttpServletResponse response) throws Exception {
    return response.getContentAsString(StandardCharsets.UTF_8);
  }
}
