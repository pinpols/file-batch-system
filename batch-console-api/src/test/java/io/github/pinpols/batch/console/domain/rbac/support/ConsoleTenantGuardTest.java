package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("控制台租户守卫:按请求上下文或认证主体解析租户, 缺失与越权一律按禁止拒绝")
class ConsoleTenantGuardTest {

  private final ConsoleRequestMetadataResolver requestMetadataResolver =
      mock(ConsoleRequestMetadataResolver.class);
  private final ConsoleTenantGuard tenantGuard = new ConsoleTenantGuard(requestMetadataResolver);

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("请求参数兜底:请求作用域不可用时按入参解析租户")
  void shouldResolveTenantFromRequestParameterWhenRequestScopeIsUnavailable() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));

    assertThat(tenantGuard.resolveTenant("tenant-a")).isEqualTo("tenant-a");
  }

  @Test
  @DisplayName("认证优先:认证主体存在时以主体租户为准")
  void shouldPreferAuthenticatedTenantWhenRequestScopeIsUnavailable() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("tester", "tenant-b", Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThat(tenantGuard.resolveTenant("tenant-b")).isEqualTo("tenant-b");
  }

  @Test
  @DisplayName("上下文全缺:三处租户来源均缺失时按禁止拒绝, 而非未认证")
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
  @DisplayName("声明缺失:认证通过但租户声明为空时按禁止拒绝")
  void shouldRejectWhenJwtParsedButTenantClaimMissing() {
    // 边缘 case:JWT 解析成功(认证已过)但 tenant claim 为 null(JWT 损坏 / 缺字段),
    // 且 RequestScope 不可用、调用方未带 requestTenantId → 严格按 FORBIDDEN 拒绝。
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("tester", null, Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.resolveTenant(null))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  @DisplayName("越权兜底:已认证但缺租户声明时不得回退请求方自带租户, 按禁止拒绝")
  void shouldRejectRequestTenantFallbackOnWebPathWhenJwtTenantClaimMissing() {
    // M1 (#780 review): web/authenticated 路径的 fail-open 尾巴。
    // 一个 tenant 角色但 JWT 无 tenant claim 的 principal,过去会 fallback 到请求携带的
    // requestTenantId → 可读任意租户(IDOR)。web 路径(SecurityContext 有 ConsolePrincipal)
    // 缺租户上下文时必须 fail-closed(FORBIDDEN),不得回退到请求方自带的 tenantId。
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("tester", null, Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.resolveTenant("victim-tenant"))
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }

  @Test
  @DisplayName("系统路径:无认证主体时保留请求租户回退, 不误伤异步任务")
  void shouldKeepRequestTenantFallbackOnSystemPathWithoutPrincipal() {
    // 系统 / @Async 路径:SecurityContext 无 ConsolePrincipal → 保留 requestTenantId fallback
    // (有意设计,不误伤定时任务 / 异步推送)。
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));

    assertThat(tenantGuard.resolveTenant("system-tenant")).isEqualTo("system-tenant");
  }

  @Test
  @DisplayName("全局角色:允许跨租户解析并返回目标租户")
  void shouldAllowGlobalRoleToCrossTenant() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "ignored"));

    assertThat(tenantGuard.resolveTenant("tenant-a")).isEqualTo("tenant-a");
  }

  @Test
  @DisplayName("全局角色:请求租户为空白时仍按业务异常拒绝")
  void shouldRejectGlobalRoleWhenRequestTenantIsBlank() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.resolveTenant("")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("租户越权:普通租户用户请求他人租户时拒绝")
  void shouldRejectTenantMismatchForTenantUser() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("bob", "tenant-a", Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThatThrownBy(() -> tenantGuard.resolveTenant("tenant-b"))
        .isInstanceOf(BizException.class);
  }

  // ── currentTenantScopeOrNull:列表 / 枚举端点的租户收敛作用域 ────────────────────

  @Test
  @DisplayName("收敛作用域-全局角色:返回空表示不限制租户")
  void currentTenantScope_globalRole_returnsNull() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("admin", "system", Set.of("ROLE_ADMIN")), "ignored"));

    assertThat(tenantGuard.currentTenantScopeOrNull()).isNull();
  }

  @Test
  @DisplayName("收敛作用域-租户角色:返回认证主体所属租户")
  void currentTenantScope_tenantRole_returnsAuthenticatedTenant() {
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("bob", "tenant-a", Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThat(tenantGuard.currentTenantScopeOrNull()).isEqualTo("tenant-a");
  }

  @Test
  @DisplayName("收敛作用域-上下文缺失:抛出业务异常且错误码为禁止")
  void currentTenantScope_tenantContextMissing_throwsForbidden() {
    when(requestMetadataResolver.current())
        .thenThrow(new IllegalStateException("request scope missing"));
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(
            new ConsolePrincipal("bob", null, Set.of("ROLE_TENANT_USER")), "ignored"));

    assertThatThrownBy(tenantGuard::currentTenantScopeOrNull)
        .isInstanceOf(BizException.class)
        .extracting(ex -> ((BizException) ex).getCode())
        .isEqualTo(ResultCode.FORBIDDEN);
  }
}
