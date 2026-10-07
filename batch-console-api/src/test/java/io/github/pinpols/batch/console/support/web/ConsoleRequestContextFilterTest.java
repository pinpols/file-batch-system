package io.github.pinpols.batch.console.support.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.ApplicationNameProvider;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityResponseWriter;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("控制台请求上下文过滤器: 租户一致性校验,操作人取值与日志上下文还原")
class ConsoleRequestContextFilterTest {

  private ConsoleRequestContextFilter filter;

  @BeforeEach
  void setUp() {
    BatchMdc.clear();
    filter = new ConsoleRequestContextFilter(
        new ConsoleSecurityResponseWriter(new ObjectMapper().findAndRegisterModules()),
        new ApplicationNameProvider(new MockEnvironment()
            .withProperty(ApplicationNameProvider.APPLICATION_NAME_KEY, "batch-console-api")));
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("租户用户提交的租户与令牌不一致时,返回禁止并回填请求标识")
  void shouldRejectTenantMismatchForTenantUser() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
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
    assertThat(response.getHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER)).isEqualTo("req-001");
    assertThat(response.getHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER)).isEqualTo("trace-001");
    assertThat(response.getContentAsString())
        .contains("\"requestId\":\"req-001\"")
        .contains("\"traceId\":\"trace-001\"");
    // i18n 迁移后:Filter 通过 ExceptionHandler 翻译,zh_CN 默认 Locale 渲染为"租户不匹配"。
    assertThat(response.getContentAsString()).contains("租户不匹配");
  }

  @Test
  @DisplayName("全局角色可以跨租户访问,并写入解析后的租户与操作人")
  void shouldAllowGlobalRoleToCrossTenant() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
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
    ConsoleRequestMetadata metadata = (ConsoleRequestMetadata)
        request.getAttribute(ConsoleRequestContextFilter.REQUEST_METADATA_ATTRIBUTE);
    assertThat(metadata.tenantId()).isEqualTo("tenant-a");
    assertThat(metadata.operatorId()).isEqualTo("admin");
  }

  @Test
  @DisplayName("客户端自行提交的操作人头被忽略,以认证身份为准")
  void shouldIgnoreClientSuppliedOperatorHeader() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")),
            "secret",
            Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    MockHttpServletRequest request = baseRequest();
    request.addHeader(CommonConstants.DEFAULT_OPERATOR_ID_HEADER, "forged-operator");
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, noOpChain(new AtomicBoolean()));

    ConsoleRequestMetadata metadata = (ConsoleRequestMetadata)
        request.getAttribute(ConsoleRequestContextFilter.REQUEST_METADATA_ATTRIBUTE);
    assertThat(metadata.operatorId()).isEqualTo("admin");
  }

  @Test
  @DisplayName("过滤器链内可见解析后的租户,链结束后恢复外层日志上下文")
  void shouldExposeResolvedTenantInChainAndRestoreOuterMdc() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")),
            "secret",
            Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    MockHttpServletRequest request = baseRequest();
    request.addHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER, "tenant-a");
    MockHttpServletResponse response = new MockHttpServletResponse();
    BatchMdc.put(StructuredLogField.TENANT_ID, "outer-tenant");
    AtomicReference<String> tenantInChain = new AtomicReference<>();

    filter.doFilter(
        request,
        response,
        (servletRequest, servletResponse) ->
            tenantInChain.set(BatchMdc.snapshot().get(StructuredLogField.TENANT_ID)));

    assertThat(tenantInChain).hasValue("tenant-a");
    assertThat(BatchMdc.snapshot()).containsEntry(StructuredLogField.TENANT_ID, "outer-tenant");
  }

  @Test
  @DisplayName("全局角色未提交租户头时,回退到自身所属租户")
  void shouldFallbackToOwnTenantWhenGlobalRoleOmitsHeader() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")),
            "secret",
            Set.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

    MockHttpServletRequest request = baseRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.doFilter(request, response, noOpChain(chainCalled));
    assertThat(chainCalled).isTrue();
    ConsoleRequestMetadata metadata = (ConsoleRequestMetadata)
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
