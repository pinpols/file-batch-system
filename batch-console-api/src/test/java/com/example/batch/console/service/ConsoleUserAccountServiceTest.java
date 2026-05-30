package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import com.example.batch.console.domain.rbac.service.ConsoleUserAccountService;
import com.example.batch.console.domain.rbac.support.ConsolePasswordHasher;
import com.example.batch.console.domain.rbac.support.ConsolePrincipal;
import com.example.batch.console.domain.rbac.support.ConsoleRoles;
import com.example.batch.console.domain.rbac.support.ConsoleSessionRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 2026-05 角色重设计:验证 {@link ConsoleUserAccountService} 的租户隔离 + 角色授予守卫。
 *
 * <p>覆盖矩阵:
 *
 * <ul>
 *   <li>TENANT_ADMIN 创建账号 tenantId 自动覆盖为 principal.tenantId
 *   <li>TENANT_ADMIN 授 ROLE_ADMIN / ROLE_AUDITOR → 403
 *   <li>TENANT_ADMIN 操作跨租户账号 → 403
 *   <li>ADMIN 不受守卫限制
 *   <li>无 principal 上下文(@Async / 内部脚本)豁免
 * </ul>
 */
class ConsoleUserAccountServiceTest {

  private ConsoleUserAccountMapper userAccountMapper;
  private ConsolePasswordHasher passwordHasher;
  private ConsoleSessionRegistry sessionRegistry;
  private ConsoleUserAccountService service;

  @BeforeEach
  void setUp() {
    userAccountMapper = mock(ConsoleUserAccountMapper.class);
    passwordHasher = mock(ConsolePasswordHasher.class);
    sessionRegistry = mock(ConsoleSessionRegistry.class);
    when(passwordHasher.encode(any())).thenReturn("hashed");
    service = new ConsoleUserAccountService(userAccountMapper, passwordHasher, sessionRegistry);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void asPrincipal(String tenantId, String... authorities) {
    Set<String> authSet = Set.of(authorities);
    ConsolePrincipal principal = new ConsolePrincipal("u-test", tenantId, authSet);
    var token =
        new UsernamePasswordAuthenticationToken(
            principal,
            null,
            authSet.stream()
                .map(SimpleGrantedAuthority::new)
                .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                .toList());
    SecurityContextHolder.getContext().setAuthentication(token);
  }

  private Map<String, Object> accountRow(long id, String tenantId, String username) {
    Map<String, Object> row = new HashMap<>();
    row.put("id", id);
    row.put("tenant_id", tenantId);
    row.put("username", username);
    row.put("display_name", username);
    row.put("authorities_csv", ConsoleRoles.TENANT_USER);
    row.put("enabled", Boolean.TRUE);
    return row;
  }

  @Nested
  class TenantAdminCreate {

    @Test
    void shouldOverrideTenantIdWithPrincipalTenant() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      when(userAccountMapper.selectByUsername("alice")).thenReturn(null);
      when(userAccountMapper.selectByUsername("alice"))
          .thenReturn(null) // first call: existence check
          .thenReturn(accountRow(1L, "tenant-a", "alice")); // second: post-insert read

      service.create("tenant-b", "alice", "pw", "Alice", ConsoleRoles.TENANT_USER);

      verify(userAccountMapper)
          .insert(
              eq("tenant-a"),
              eq("alice"),
              eq("Alice"),
              eq("hashed"),
              eq(ConsoleRoles.TENANT_USER),
              nullable(String.class));
    }

    @Test
    void shouldRejectGrantingAdminAuthority() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);

      assertThatThrownBy(
              () -> service.create("tenant-a", "alice", "pw", "Alice", ConsoleRoles.ADMIN))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.FORBIDDEN);
      verify(userAccountMapper, never())
          .insert(any(), any(), any(), any(), any(), nullable(String.class));
    }

    @Test
    void shouldRejectGrantingAuditorAuthority() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);

      assertThatThrownBy(() -> service.create("tenant-a", "bob", "pw", "Bob", ConsoleRoles.AUDITOR))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.FORBIDDEN);
    }

    @Test
    void shouldAllowGrantingTenantUserAndTenantAdmin() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      when(userAccountMapper.selectByUsername("carol"))
          .thenReturn(null)
          .thenReturn(accountRow(2L, "tenant-a", "carol"));

      service.create(
          "tenant-a",
          "carol",
          "pw",
          "Carol",
          ConsoleRoles.TENANT_ADMIN + "," + ConsoleRoles.TENANT_USER);

      verify(userAccountMapper)
          .insert(
              eq("tenant-a"),
              eq("carol"),
              eq("Carol"),
              eq("hashed"),
              eq(ConsoleRoles.TENANT_ADMIN + "," + ConsoleRoles.TENANT_USER),
              nullable(String.class));
    }
  }

  @Nested
  class AdminCreate {

    @Test
    void shouldRespectExplicitTenantId() {
      asPrincipal("system", ConsoleRoles.ADMIN);
      when(userAccountMapper.selectByUsername("dave"))
          .thenReturn(null)
          .thenReturn(accountRow(3L, "tenant-z", "dave"));

      service.create("tenant-z", "dave", "pw", "Dave", ConsoleRoles.ADMIN);

      verify(userAccountMapper)
          .insert(
              eq("tenant-z"),
              eq("dave"),
              eq("Dave"),
              eq("hashed"),
              eq(ConsoleRoles.ADMIN),
              nullable(String.class));
    }
  }

  @Nested
  class TenantScopeOnMutate {

    @Test
    void tenantAdminCannotResetCrossTenantPassword() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      when(userAccountMapper.selectById(99L)).thenReturn(accountRow(99L, "tenant-b", "victim"));

      assertThatThrownBy(() -> service.resetPassword(99L, "new-pw"))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.FORBIDDEN);
      verify(userAccountMapper, never()).updatePasswordHash(eq(99L), any());
    }

    @Test
    void tenantAdminCannotDisableCrossTenantAccount() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      when(userAccountMapper.selectById(99L)).thenReturn(accountRow(99L, "tenant-b", "victim"));

      assertThatThrownBy(() -> service.disable(99L))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.FORBIDDEN);
    }

    @Test
    void adminCanResetAcrossTenants() {
      asPrincipal("system", ConsoleRoles.ADMIN);
      when(userAccountMapper.selectById(99L)).thenReturn(accountRow(99L, "tenant-b", "victim"));

      service.resetPassword(99L, "new-pw");
      verify(userAccountMapper).updatePasswordHash(eq(99L), eq("hashed"));
    }
  }

  @Nested
  class TenantScopeOnList {

    @Test
    void tenantAdminListIsAutoFilteredToOwnTenant() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      when(userAccountMapper.selectByQuery(eq("tenant-a"), any(), any())).thenReturn(List.of());
      when(userAccountMapper.countByQuery(eq("tenant-a"), any())).thenReturn(0L);

      service.list("tenant-b", null, new PageRequest(1, 10));

      verify(userAccountMapper).selectByQuery(eq("tenant-a"), nullable(String.class), any());
      verify(userAccountMapper).countByQuery(eq("tenant-a"), nullable(String.class));
    }

    @Test
    void adminListRespectsExplicitTenantFilter() {
      asPrincipal("system", ConsoleRoles.ADMIN);
      when(userAccountMapper.selectByQuery(eq("tenant-b"), any(), any())).thenReturn(List.of());
      when(userAccountMapper.countByQuery(eq("tenant-b"), any())).thenReturn(0L);

      service.list("tenant-b", null, new PageRequest(1, 10));

      verify(userAccountMapper).selectByQuery(eq("tenant-b"), nullable(String.class), any());
    }
  }

  @Nested
  class NoPrincipalContext {

    @Test
    void shouldPassThroughWhenNoSecurityContext() {
      // SecurityContextHolder 已 clear,无 principal
      when(userAccountMapper.selectByUsername("eve"))
          .thenReturn(null)
          .thenReturn(accountRow(4L, "tenant-x", "eve"));

      service.create("tenant-x", "eve", "pw", "Eve", ConsoleRoles.ADMIN);

      verify(userAccountMapper)
          .insert(eq("tenant-x"), eq("eve"), any(), any(), any(), nullable(String.class));
    }
  }
}
