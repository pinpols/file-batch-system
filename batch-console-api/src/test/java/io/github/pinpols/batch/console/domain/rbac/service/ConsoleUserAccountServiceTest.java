package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePasswordHasher;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleRoles;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSessionRegistry;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 *   <li>ADMIN 可授予四类正式角色，但不能写入旧角色或未知角色
 *   <li>无 principal 上下文(@Async / 内部脚本)豁免
 * </ul>
 */
@DisplayName("用户账号服务: 租户隔离、角色授予守卫与账号本人改密流程")
class ConsoleUserAccountServiceTest {

  private ConsoleUserAccountMapper userAccountMapper;
  private TenantMapper tenantMapper;
  private ConsolePasswordHasher passwordHasher;
  private ConsoleSessionRegistry sessionRegistry;
  private ConsoleUserAccountService service;

  @BeforeEach
  void setUp() {
    userAccountMapper = mock(ConsoleUserAccountMapper.class);
    tenantMapper = mock(TenantMapper.class);
    passwordHasher = mock(ConsolePasswordHasher.class);
    sessionRegistry = mock(ConsoleSessionRegistry.class);
    when(passwordHasher.encode(any())).thenReturn("hashed");
    service = new ConsoleUserAccountService(
        userAccountMapper, tenantMapper, passwordHasher, sessionRegistry);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  private void asPrincipal(String tenantId, String... authorities) {
    Set<String> authSet = Set.of(authorities);
    ConsolePrincipal principal = new ConsolePrincipal("u-test", tenantId, authSet);
    var token = new UsernamePasswordAuthenticationToken(
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

  private void activeTenant(String tenantId) {
    when(tenantMapper.selectByTenantId(tenantId)).thenReturn(Map.of("status", "ACTIVE"));
  }

  @Nested
  @DisplayName("租户管理员创建账号: 租户覆盖与角色授予边界")
  class TenantAdminCreate {

    @Test
    @DisplayName("租户管理员创建账号时租户被覆盖为自身租户, 落库参数一致")
    void shouldOverrideTenantIdWithPrincipalTenant() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      activeTenant("tenant-a");
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
    @DisplayName("租户管理员授予平台管理员角色被拒, 且不写入账号")
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
    @DisplayName("租户管理员授予审计角色被拒")
    void shouldRejectGrantingAuditorAuthority() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);

      assertThatThrownBy(() -> service.create("tenant-a", "bob", "pw", "Bob", ConsoleRoles.AUDITOR))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.FORBIDDEN);
    }

    @Test
    @DisplayName("租户管理员可同时授予租户用户与租户管理员角色")
    void shouldAllowGrantingTenantUserAndTenantAdmin() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      activeTenant("tenant-a");
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
  @DisplayName("平台管理员创建账号: 显式租户、角色组合与租户状态校验")
  class AdminCreate {

    @Test
    @DisplayName("平台管理员创建账号时按显式租户落库")
    void shouldRespectExplicitTenantId() {
      asPrincipal("system", ConsoleRoles.ADMIN);
      activeTenant("tenant-z");
      when(userAccountMapper.selectByUsername("dave"))
          .thenReturn(null)
          .thenReturn(accountRow(3L, "tenant-z", "dave"));

      service.create("tenant-z", "dave", "pw", "Dave", ConsoleRoles.TENANT_ADMIN);

      verify(userAccountMapper)
          .insert(
              eq("tenant-z"),
              eq("dave"),
              eq("Dave"),
              eq("hashed"),
              eq(ConsoleRoles.TENANT_ADMIN),
              nullable(String.class));
    }

    @Test
    @DisplayName("授予历史遗留角色时被拒, 且不写入账号")
    void shouldRejectLegacyRole_whenGranted() {
      asPrincipal("system", ConsoleRoles.ADMIN);

      assertThatThrownBy(() -> service.create("tenant-z", "legacy", "pw", "Legacy", "ROLE_USER"))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.INVALID_ARGUMENT);
      verify(userAccountMapper, never())
          .insert(any(), any(), any(), any(), any(), nullable(String.class));
    }

    @Test
    @DisplayName("平台角色与租户角色混合授予被拒, 且不写入账号")
    void shouldRejectMixedPlatformAndTenantRoles() {
      asPrincipal("system", ConsoleRoles.ADMIN);
      String roles = ConsoleRoles.ADMIN + "," + ConsoleRoles.TENANT_USER;

      assertThatThrownBy(
              () -> service.create("system", "security-owner", "pw", "Security Owner", roles))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.INVALID_ARGUMENT);

      verify(userAccountMapper, never())
          .insert(any(), any(), any(), any(), any(), nullable(String.class));
    }

    @Test
    @DisplayName("非系统租户下授予平台角色被拒, 且不写入账号")
    void shouldRejectPlatformRoleOutsideSystemTenant() {
      asPrincipal("system", ConsoleRoles.ADMIN);

      assertThatThrownBy(() -> service.create("tenant-z", "root", "pw", "Root", ConsoleRoles.ADMIN))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.INVALID_ARGUMENT);

      verify(userAccountMapper, never())
          .insert(any(), any(), any(), any(), any(), nullable(String.class));
    }

    @Test
    @DisplayName("目标租户不存在或已停用时授予租户角色被拒")
    void shouldRejectTenantRoleWhenTenantMissingOrInactive() {
      asPrincipal("system", ConsoleRoles.ADMIN);
      when(tenantMapper.selectByTenantId("missing")).thenReturn(null);
      when(tenantMapper.selectByTenantId("paused")).thenReturn(Map.of("status", "SUSPENDED"));

      assertThatThrownBy(
              () -> service.create("missing", "miss", "pw", "Miss", ConsoleRoles.TENANT_USER))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.INVALID_ARGUMENT);
      assertThatThrownBy(
              () -> service.create("paused", "pause", "pw", "Pause", ConsoleRoles.TENANT_USER))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.INVALID_ARGUMENT);

      verify(userAccountMapper, never())
          .insert(any(), any(), any(), any(), any(), nullable(String.class));
    }
  }

  @Nested
  @DisplayName("租户管理员写操作范围: 跨租户改密与禁用拦截")
  class TenantScopeOnMutate {

    @Test
    @DisplayName("租户管理员重置他租户账号密码被拒, 且不更新密码")
    void shouldRejectCrossTenantPasswordReset_whenTenantAdmin() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      when(userAccountMapper.selectById(99L)).thenReturn(accountRow(99L, "tenant-b", "victim"));

      assertThatThrownBy(() -> service.resetPassword(99L, "new-pw"))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.FORBIDDEN);
      verify(userAccountMapper, never())
          .updatePasswordHashAndMustChange(eq(99L), any(), anyBoolean());
    }

    @Test
    @DisplayName("租户管理员禁用他租户账号被拒")
    void shouldRejectCrossTenantDisable_whenTenantAdmin() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      when(userAccountMapper.selectById(99L)).thenReturn(accountRow(99L, "tenant-b", "victim"));

      assertThatThrownBy(() -> service.disable(99L))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.FORBIDDEN);
    }

    @Test
    @DisplayName("平台管理员可跨租户重置密码, 并强制下次改密")
    void shouldAllowCrossTenantReset_whenPlatformAdmin() {
      asPrincipal("system", ConsoleRoles.ADMIN);
      when(userAccountMapper.selectById(99L)).thenReturn(accountRow(99L, "tenant-b", "victim"));

      service.resetPassword(99L, "new-pw");
      verify(userAccountMapper).updatePasswordHashAndMustChange(99L, "hashed", true);
    }
  }

  @Nested
  @DisplayName("租户管理员列表范围: 按自身租户自动收敛")
  class TenantScopeOnList {

    @Test
    @DisplayName("租户管理员传入他租户时查询仍收敛到自身租户")
    void shouldFilterListToOwnTenant_whenTenantAdmin() {
      asPrincipal("tenant-a", ConsoleRoles.TENANT_ADMIN);
      when(userAccountMapper.selectByQuery(eq("tenant-a"), any(), any(), any()))
          .thenReturn(List.of());
      when(userAccountMapper.countByQuery(eq("tenant-a"), any(), any())).thenReturn(0L);

      service.list("tenant-b", null, true, new PageRequest(1, 10));

      verify(userAccountMapper)
          .selectByQuery(eq("tenant-a"), nullable(String.class), eq(true), any());
      verify(userAccountMapper).countByQuery(eq("tenant-a"), nullable(String.class), eq(true));
    }

    @Test
    @DisplayName("平台管理员按显式租户筛选查询")
    void shouldRespectExplicitTenantFilter_whenPlatformAdmin() {
      asPrincipal("system", ConsoleRoles.ADMIN);
      when(userAccountMapper.selectByQuery(eq("tenant-b"), any(), any(), any()))
          .thenReturn(List.of());
      when(userAccountMapper.countByQuery(eq("tenant-b"), any(), any())).thenReturn(0L);

      service.list("tenant-b", null, false, new PageRequest(1, 10));

      verify(userAccountMapper)
          .selectByQuery(eq("tenant-b"), nullable(String.class), eq(false), any());
    }
  }

  @Nested
  @DisplayName("无认证上下文: 内部调用豁免角色守卫")
  class NoPrincipalContext {

    @Test
    @DisplayName("无认证上下文时创建放行, 按入参租户落库")
    void shouldPassThroughWhenNoSecurityContext() {
      // SecurityContextHolder 已 clear,无 principal
      when(userAccountMapper.selectByUsername("eve"))
          .thenReturn(null)
          .thenReturn(accountRow(4L, "system", "eve"));

      service.create("system", "eve", "pw", "Eve", ConsoleRoles.ADMIN);

      verify(userAccountMapper)
          .insert(eq("system"), eq("eve"), any(), any(), any(), nullable(String.class));
    }
  }

  @Nested
  @DisplayName("账号本人改密: 原密码校验、新旧一致与强制改密标记")
  class ChangeOwnPassword {

    private ConsoleUserAccountEntity entity(String username, String tenantId, String hash) {
      ConsoleUserAccountEntity e = new ConsoleUserAccountEntity();
      e.setId(7L);
      e.setUsername(username);
      e.setTenantId(tenantId);
      e.setPasswordHash(hash);
      e.setMustChangePassword(true);
      return e;
    }

    @Test
    @DisplayName("原密码正确时更新密码并清除强制改密标记, 同时失效会话")
    void shouldClearMustChange_whenCurrentPasswordCorrect() {
      // arrange
      when(userAccountMapper.findByUsernameIgnoreCase("admin"))
          .thenReturn(Optional.of(entity("admin", "system", "old-hash")));
      when(passwordHasher.matches("old-pw", "old-hash")).thenReturn(true);
      when(passwordHasher.matches("new-pw", "old-hash")).thenReturn(false);

      // act
      service.changeOwnPassword("admin", "old-pw", "new-pw");

      // assert
      verify(userAccountMapper).updatePasswordHashAndMustChange(7L, "hashed", false);
      verify(sessionRegistry).invalidateSession("admin", "system");
    }

    @Test
    @DisplayName("原密码错误时抛出未认证异常, 且不更新密码")
    void shouldReject_whenCurrentPasswordWrong() {
      when(userAccountMapper.findByUsernameIgnoreCase("admin"))
          .thenReturn(Optional.of(entity("admin", "system", "old-hash")));
      when(passwordHasher.matches("bad", "old-hash")).thenReturn(false);

      assertThatThrownBy(() -> service.changeOwnPassword("admin", "bad", "new-pw"))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.UNAUTHORIZED);
      verify(userAccountMapper, never())
          .updatePasswordHashAndMustChange(anyLong(), any(), anyBoolean());
    }

    @Test
    @DisplayName("新密码与原密码相同时抛出参数非法异常")
    void shouldReject_whenNewPasswordSameAsCurrent() {
      when(userAccountMapper.findByUsernameIgnoreCase("admin"))
          .thenReturn(Optional.of(entity("admin", "system", "old-hash")));
      when(passwordHasher.matches("old-pw", "old-hash")).thenReturn(true);

      assertThatThrownBy(() -> service.changeOwnPassword("admin", "old-pw", "old-pw"))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.INVALID_ARGUMENT);
    }

    @Test
    @DisplayName("账号不存在时改密抛出资源不存在异常")
    void shouldThrow_whenAccountNotFound() {
      when(userAccountMapper.findByUsernameIgnoreCase("ghost")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.changeOwnPassword("ghost", "x", "y"))
          .isInstanceOf(BizException.class)
          .extracting(e -> ((BizException) e).getCode())
          .isEqualTo(ResultCode.NOT_FOUND);
    }
  }
}
