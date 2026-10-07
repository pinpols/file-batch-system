package io.github.pinpols.batch.console.domain.rbac.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper;
import io.github.pinpols.batch.console.domain.rbac.mapper.TenantMapper;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleUserAccountService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsolePasswordHasher;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSessionRegistry;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户账号服务: 停用启用、重置密码与会话失效联动")
class ConsoleUserAccountControllerTest {

  @Mock
  private ConsoleUserAccountMapper userAccountMapper;

  @Mock
  private TenantMapper tenantMapper;

  @Mock
  private ConsolePasswordHasher passwordHasher;

  @Mock
  private ConsoleSessionRegistry sessionRegistry;

  private ConsoleUserAccountService service;

  private static final Map<String, Object> ACCOUNT = Map.of(
      "id", 42L,
      "tenant_id", "tenant-a",
      "username", "user-a",
      "display_name", "User A",
      "authorities_csv", "ROLE_TENANT_USER",
      "enabled", true,
      "created_at", "2026-01-01T00:00:00",
      "updated_at", "2026-01-01T00:00:00");

  private static final Map<String, Object> DISABLED_ACCOUNT = Map.of(
      "id", 42L,
      "tenant_id", "tenant-a",
      "username", "user-a",
      "display_name", "User A",
      "authorities_csv", "ROLE_TENANT_USER",
      "enabled", false,
      "created_at", "2026-01-01T00:00:00",
      "updated_at", "2026-01-01T00:00:00");

  @BeforeEach
  void setUp() {
    service = new ConsoleUserAccountService(
        userAccountMapper, tenantMapper, passwordHasher, sessionRegistry);
  }

  @Test
  @DisplayName("停用账号后使该用户既有会话失效")
  @SuppressWarnings("unchecked")
  void disable_invalidatesSession() {
    when(userAccountMapper.selectById(42L)).thenReturn(ACCOUNT, DISABLED_ACCOUNT);

    service.disable(42L);

    verify(userAccountMapper).updateEnabled(42L, false);
    verify(sessionRegistry).invalidateSession("user-a", "tenant-a");
  }

  @Test
  @DisplayName("停用不存在的账号时按业务异常拒绝且不更新状态")
  void disable_nonExistentAccount_throwsBizException() {
    when(userAccountMapper.selectById(99L)).thenReturn(null);

    assertThatThrownBy(() -> service.disable(99L))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 不含原文,改用 messageArgs 检查
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs())
            .anyMatch(a -> a != null && a.toString().contains("user account not found")));

    verify(userAccountMapper, never()).updateEnabled(anyLong(), any(Boolean.class));
    verify(sessionRegistry, never()).invalidateSession(anyString(), anyString());
  }

  @Test
  @DisplayName("重置密码后使该用户既有会话失效并要求下次改密")
  void resetPassword_invalidatesSession() {
    when(userAccountMapper.selectById(42L)).thenReturn(ACCOUNT);
    when(passwordHasher.encode("newSecurePass")).thenReturn("$argon2id$...");

    service.resetPassword(42L, "newSecurePass");

    // 管理员 reset 置 must_change_password=true,提醒被重置者尽快改密
    verify(userAccountMapper).updatePasswordHashAndMustChange(42L, "$argon2id$...", true);
    verify(sessionRegistry).invalidateSession("user-a", "tenant-a");
  }

  @Test
  @DisplayName("重置不存在账号的密码时按业务异常拒绝")
  void resetPassword_nonExistentAccount_throwsBizException() {
    when(userAccountMapper.selectById(99L)).thenReturn(null);

    assertThatThrownBy(() -> service.resetPassword(99L, "newSecurePass"))
        .isInstanceOf(BizException.class)
        // i18n: messageKey 不含原文,改用 messageArgs 检查
        .satisfies(ex -> assertThat(((BizException) ex).getMessageArgs())
            .anyMatch(a -> a != null && a.toString().contains("user account not found")));

    verify(userAccountMapper, never())
        .updatePasswordHashAndMustChange(anyLong(), anyString(), anyBoolean());
    verify(sessionRegistry, never()).invalidateSession(anyString(), anyString());
  }

  @Test
  @DisplayName("启用账号不触发会话失效")
  void enable_doesNotInvalidateSession() {
    when(userAccountMapper.selectById(42L)).thenReturn(ACCOUNT);

    service.enable(42L);

    verify(userAccountMapper).updateEnabled(42L, true);
    verify(sessionRegistry, never()).invalidateSession(anyString(), anyString());
  }
}
