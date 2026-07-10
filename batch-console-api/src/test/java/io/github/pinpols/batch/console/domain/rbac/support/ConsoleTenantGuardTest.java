package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
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
  void shouldRejectRequestTenantFallbackOnWebPathWhenJwtTenantClaimMissing() {
    // M1 (#780 review): web/authenticated 路径的 fail-open 尾巴。
    // 一个 tenant 角色但 JWT 无 tenant claim 的 principal,过去会 fallback 到请求携带的
    // requestTenantId → 可读任意租户(IDOR)。web 路径(SecurityContext 有 ConsolePrincipal)
    // 缺租户上下文时必须 fail-closed(FORBIDDEN),不得回退到请求方自带的 tenantId。
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("tester", null, Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.resolveTenant("victim-tenant"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  void shouldKeepRequestTenantFallbackOnSystemPathWithoutPrincipal() {
    // 系统 / @Async 路径:SecurityContext 无 ConsolePrincipal → 保留 requestTenantId fallback
    // (有意设计,不误伤定时任务 / 异步推送)。
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));

    assertThat(tenantGuard.resolveTenant("system-tenant")).isEqualTo("system-tenant");
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

  // ── currentTenantScopeOrNull:列表 / 枚举端点的租户收敛作用域 ────────────────────

  @Test
  void currentTenantScope_globalRole_returnsNull() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "ignored"));

    assertThat(tenantGuard.currentTenantScopeOrNull()).isNull();
  }

  @Test
  void currentTenantScope_tenantRole_returnsAuthenticatedTenant() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("bob", "tenant-a", Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThat(tenantGuard.currentTenantScopeOrNull()).isEqualTo("tenant-a");
  }

  @Test
  void currentTenantScope_tenantContextMissing_throwsForbidden() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                new ConsolePrincipal("bob", null, Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.currentTenantScopeOrNull())
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }
}
