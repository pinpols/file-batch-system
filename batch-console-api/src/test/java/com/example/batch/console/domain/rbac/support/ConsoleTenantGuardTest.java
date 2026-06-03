package com.example.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ConsoleTenantGuardTest {

  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final ConsoleTenantGuard tenantGuard = new ConsoleTenantGuard(requestMetadataResolver);

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void shouldResolveTenantFromRequestParameterWhenRequestScopeIsUnavailable() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));

    assertThat(tenantGuard.resolveTenant("tenant-a")).isEqualTo("tenant-a");
  }

  @Test
  void shouldPreferAuthenticatedTenantWhenRequestScopeIsUnavailable() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("tester", "tenant-b", Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThat(tenantGuard.resolveTenant("tenant-b")).isEqualTo("tenant-b");
  }

  @Test
  void shouldRejectMissingTenantWhenRequestScopeAndParameterAreBothUnavailable() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));

    // K2 副发现:JWT/RequestScope/参数三处租户上下文均缺失 → FORBIDDEN(授权失败),
    // 非 UNAUTHORIZED(认证失败);请求方不该被引导去"重新登录"。
    assertThatThrownBy(() -> tenantGuard.resolveTenant(" "))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  void shouldRejectWhenJwtParsedButTenantClaimMissing() {
    // 边缘 case:JWT 解析成功(认证已过)但 tenant claim 为 null(JWT 损坏 / 缺字段),
    // 且 RequestScope 不可用、调用方未带 requestTenantId → 严格按 FORBIDDEN 拒绝。
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("tester", null, Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.resolveTenant(null))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  void shouldAllowGlobalRoleToCrossTenant() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "ignored"));

    assertThat(tenantGuard.resolveTenant("tenant-a")).isEqualTo("tenant-a");
  }

  @Test
  void shouldRejectGlobalRoleWhenRequestTenantIsBlank() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.resolveTenant("")).isInstanceOf(BizException.class);
  }

  @Test
  void shouldRejectTenantMismatchForTenantUser() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("bob", "tenant-a", Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.resolveTenant("tenant-b"))
        .isInstanceOf(BizException.class);
  }
}
