package io.github.pinpols.batch.console.domain.job.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** {@link BuiltinTaskTypeGuard} 单测 — 守 ADR-035 §使用边界。 */
class BuiltinTaskTypeGuardTest {

  private final BuiltinTaskTypeGuard guard = new BuiltinTaskTypeGuard();

  @AfterEach
  void clearSecurity() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void allowsNonBuiltinForAnyone() {
    setAuthorities("ROLE_TENANT_USER");
    guard.assertAllowed("IMPORT");
    guard.assertAllowed("EXPORT");
    guard.assertAllowed("sftp_push");
    guard.assertAllowed("custom_tenant_type");
  }

  @Test
  void allowsNullOrBlankJobType() {
    setAuthorities("ROLE_TENANT_USER");
    guard.assertAllowed(null);
    guard.assertAllowed("   ");
  }

  @Test
  void rejectsAllBuiltinForNonAdmin() {
    setAuthorities("ROLE_TENANT_USER");
    for (String t : BuiltinTaskTypeGuard.RESERVED_BUILTIN_TASK_TYPES) {
      assertThatThrownBy(() -> guard.assertAllowed(t))
          .isInstanceOf(BizException.class)
          .satisfies(
              ex -> assertThat(((BizException) ex).getCode()).isEqualTo(ResultCode.FORBIDDEN));
    }
  }

  @Test
  void rejectsBuiltinForTenantAdmin() {
    setAuthorities("ROLE_TENANT_ADMIN");
    assertThatThrownBy(() -> guard.assertAllowed("shell")).isInstanceOf(BizException.class);
  }

  @Test
  void allowsBuiltinForPlatformAdmin() {
    setAuthorities("ROLE_ADMIN");
    guard.assertAllowed("shell");
    guard.assertAllowed("sql");
    guard.assertAllowed("stored_proc");
    guard.assertAllowed("http");
  }

  @Test
  void multiRoleIncludingAdminPasses() {
    setAuthorities("ROLE_TENANT_USER", "ROLE_ADMIN", "ROLE_AUDITOR");
    guard.assertAllowed("shell");
  }

  @Test
  void caseInsensitiveMatch() {
    setAuthorities("ROLE_TENANT_USER");
    assertThatThrownBy(() -> guard.assertAllowed("SHELL")).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> guard.assertAllowed("Stored_Proc")).isInstanceOf(BizException.class);
    assertThatThrownBy(() -> guard.assertAllowed("  http  ")).isInstanceOf(BizException.class);
  }

  @Test
  void rejectsBuiltinWhenNoAuthentication() {
    SecurityContextHolder.clearContext();
    assertThatThrownBy(() -> guard.assertAllowed("shell")).isInstanceOf(BizException.class);
  }

  private static void setAuthorities(String... authorities) {
    var grants = java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
    var auth = new UsernamePasswordAuthenticationToken("test-user", "n/a", grants);
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
}
