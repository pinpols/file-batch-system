package io.github.pinpols.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePrincipal;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

class ConsoleRequestContextFilterTest {

  private ConsoleRequestContextFilter filter;

  @BeforeEach
  void setUp() {
    filter = new ConsoleRequestContextFilter(new ConsoleSecurityResponseWriter(new ObjectMapper()));
    ReflectionTestUtils.setField(filter, "applicationName", "batch-console-api");
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldRejectTenantMismatchForTenantUser() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("bob", "tenant-a", Set.of("ROLE_TENANT_USER")),
                "secret",
                Set.of(new SimpleGrantedAuthority("ROLE_TENANT_USER"))));

    MockHttpServletRequest request = baseRequest();
    request.addHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER, "tenant-b");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, noOpChain(chainCalled));
    assertThat(chainCalled).isFalse();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
    assertThat(response.getContentAsString()).contains(ResultCode.FORBIDDEN.name());
    // i18n 迁移后:Filter 通过 ExceptionHandler 翻译,zh_CN 默认 Locale 渲染为"租户不匹配"。
    assertThat(response.getContentAsString()).contains("租户不匹配");
  }

  @Test
  void shouldAllowGlobalRoleToCrossTenant() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")),
                "secret",
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

    MockHttpServletRequest request = baseRequest();
    request.addHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER, "tenant-a");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, noOpChain(chainCalled));
    assertThat(chainCalled).isTrue();
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    ConsoleRequestMetadata metadata =
        (ConsoleRequestMetadata)
            request.getAttribute(ConsoleRequestContextFilter.REQUEST_METADATA_ATTRIBUTE);
    assertThat(metadata.tenantId()).isEqualTo("tenant-a");
  }

  @Test
  void shouldFallbackToOwnTenantWhenGlobalRoleOmitsHeader() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")),
                "secret",
                Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

    MockHttpServletRequest request = baseRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, noOpChain(chainCalled));
    assertThat(chainCalled).isTrue();
    ConsoleRequestMetadata metadata =
        (ConsoleRequestMetadata)
            request.getAttribute(ConsoleRequestContextFilter.REQUEST_METADATA_ATTRIBUTE);
    assertThat(metadata.tenantId()).isEqualTo("system");
  }

  private MockHttpServletRequest baseRequest() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setMethod("GET");
    request.setRequestURI("/api/console/jobs");
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
}
