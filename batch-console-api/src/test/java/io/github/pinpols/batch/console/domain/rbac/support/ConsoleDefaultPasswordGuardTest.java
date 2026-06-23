package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

/** 部署期默认密码守护:prod fail-fast vs 非 prod WARN。 */
@ExtendWith(MockitoExtension.class)
class ConsoleDefaultPasswordGuardTest {

  @Mock private io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper mapper;
  @Mock private ConsolePasswordHasher passwordHasher;

  private ConsoleUserAccountEntity account(String username, String hash) {
    ConsoleUserAccountEntity e = new ConsoleUserAccountEntity();
    e.setUsername(username);
    e.setTenantId("system");
    e.setPasswordHash(hash);
    return e;
  }

  private ConsoleDefaultPasswordGuard guard(MockEnvironment env) {
    return new ConsoleDefaultPasswordGuard(mapper, passwordHasher, env);
  }

  @Test
  void shouldFailFast_whenProdAndBuiltinStillFactoryDefault() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    when(mapper.selectBuiltinSystemAccounts(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES))
        .thenReturn(List.of(account("admin", "$argon2id$seed")));
    when(passwordHasher.matches("admin123", "$argon2id$seed")).thenReturn(true);

    assertThatThrownBy(() -> guard(env).checkBuiltinDefaultPasswords())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("admin");
  }

  @Test
  void shouldOnlyWarn_whenNonProdAndStillFactoryDefault() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("local"); // 显式非 prod(空 profile 会被 fail-secure 当 prod)
    when(mapper.selectBuiltinSystemAccounts(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES))
        .thenReturn(List.of(account("admin", "$argon2id$seed")));
    when(passwordHasher.matches("admin123", "$argon2id$seed")).thenReturn(true);

    assertThatCode(() -> guard(env).checkBuiltinDefaultPasswords()).doesNotThrowAnyException();
  }

  @Test
  void shouldPass_whenBuiltinPasswordChanged() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    when(mapper.selectBuiltinSystemAccounts(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES))
        .thenReturn(List.of(account("admin", "$argon2id$changed")));
    when(passwordHasher.matches("admin123", "$argon2id$changed")).thenReturn(false);

    assertThatCode(() -> guard(env).checkBuiltinDefaultPasswords()).doesNotThrowAnyException();
  }

  @Test
  void shouldPass_whenNoBuiltinAccounts() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    when(mapper.selectBuiltinSystemAccounts(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES))
        .thenReturn(List.of());

    assertThatCode(() -> guard(env).checkBuiltinDefaultPasswords()).doesNotThrowAnyException();
  }

  @Test
  void builtinUsernamesCoverSeededAccounts() {
    assertThat(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES)
        .containsExactlyInAnyOrder("admin", "auditor", "config-admin");
  }
}
