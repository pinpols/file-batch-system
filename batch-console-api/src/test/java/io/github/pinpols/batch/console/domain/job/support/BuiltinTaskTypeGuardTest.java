package io.github.pinpols.batch.console.domain.job.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@link BuiltinTaskTypeGuard} 单测 — 守 ADR-035 §使用边界。 */
@DisplayName("内置任务类型守卫: 按调用者角色与类型文本判定内置任务是否放行")
class BuiltinTaskTypeGuardTest {

  private final BuiltinTaskTypeGuard guard = new BuiltinTaskTypeGuard();

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("非内置类型对任意角色一律放行")
  void shouldAllowNonBuiltinType_whenCallerHasAnyRole() {
    setAuthorities("ROLE_TENANT_USER");
    guard.assertAllowed("IMPORT");
    guard.assertAllowed("EXPORT");
    guard.assertAllowed("sftp_push");
    guard.assertAllowed("custom_tenant_type");
  }

  @Test
  @DisplayName("作业类型缺失或仅含空白字符时直接放行")
  void shouldAllowType_whenJobTypeNullOrBlank() {
    setAuthorities("ROLE_TENANT_USER");
    guard.assertAllowed(null);
    guard.assertAllowed("   ");
  }

  @Test
  @DisplayName("租户普通用户提交任一内置类型均被判为无权限")
  void shouldRejectEveryBuiltinType_whenTenantUser() {
    setAuthorities("ROLE_TENANT_USER");
    for (String t : BuiltinTaskTypeGuard.RESERVED_BUILTIN_TASK_TYPES) {
      assertThatThrownBy(() -> guard.assertAllowed(t))
          .isInstanceOf(BizException.class)
          .satisfies(
              ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.FORBIDDEN));
    }
  }

  @Test
  @DisplayName("租户管理员同样不允许使用内置类型")
  void shouldRejectBuiltinType_whenTenantAdmin() {
    setAuthorities("ROLE_TENANT_ADMIN");
    assertThatThrownBy(() -> guard.assertAllowed("shell")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("平台管理员可使用全部内置类型")
  void shouldAllowBuiltinTypes_whenPlatformAdmin() {
    setAuthorities("ROLE_ADMIN");
    guard.assertAllowed("shell");
    guard.assertAllowed("sql");
    guard.assertAllowed("stored_proc");
    guard.assertAllowed("http");
  }

  @Test
  @DisplayName("多角色中只要含平台管理员即放行内置类型")
  void shouldAllowBuiltinType_whenAnyGrantedRoleIsPlatformAdmin() {
    setAuthorities("ROLE_TENANT_USER", "ROLE_ADMIN", "ROLE_AUDITOR");
    guard.assertAllowed("shell");
  }

  @Test
  @DisplayName("内置类型识别忽略大小写与首尾空白")
  void shouldMatchBuiltinTypesIgnoringCaseAndSurroundingSpaces() {
    setAuthorities("ROLE_TENANT_USER");
    assertThatThrownBy(() -> guard.assertAllowed("SHELL")).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> guard.assertAllowed("Stored_Proc")).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> guard.assertAllowed("  http  ")).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("无认证信息时拒绝使用内置类型")
  void shouldRejectBuiltinType_whenNoAuthentication() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> guard.assertAllowed("shell")).isInstanceOf(BizException.class);
  }

  private static void setAuthorities(String... authorities) {
    var grants =
        java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    var auth = new UsernamePasswordAuthenticationToken("test-user", "n/a", grants);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
