package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

/** 部署期默认密码守护:prod fail-fast vs 非 prod WARN。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("部署期默认密码守护:生产环境命中出厂口令即失败, 非生产仅告警, 改密或无内置账号时放行")
class ConsoleDefaultPasswordGuardTest {

  @Mock
  private io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleUserAccountMapper mapper;

  @Mock
  private ConsolePasswordHasher passwordHasher;

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
  @DisplayName("生产环境:内置账号仍为出厂口令时快速失败, 并报出账号名")
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
  @DisplayName("非生产环境:仍为出厂口令时不抛异常, 仅记录告警")
  void shouldOnlyWarn_whenNonProdAndStillFactoryDefault() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("local"); // 显式非 prod(空 profile 会被 fail-secure 当 prod)
    when(mapper.selectBuiltinSystemAccounts(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES))
        .thenReturn(List.of(account("admin", "$argon2id$seed")));
    when(passwordHasher.matches("admin123", "$argon2id$seed")).thenReturn(true);

    assertThatCode(() -> guard(env).checkBuiltinDefaultPasswords()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("已改密:内置账号口令变更后直接放行")
  void shouldPass_whenBuiltinPasswordChanged() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    when(mapper.selectBuiltinSystemAccounts(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES))
        .thenReturn(List.of(account("admin", "$argon2id$changed")));
    when(passwordHasher.matches("admin123", "$argon2id$changed")).thenReturn(false);

    assertThatCode(() -> guard(env).checkBuiltinDefaultPasswords()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("无内置账号:未查到内置账号时直接放行")
  void shouldPass_whenNoBuiltinAccounts() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    when(mapper.selectBuiltinSystemAccounts(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES))
        .thenReturn(List.of());

    assertThatCode(() -> guard(env).checkBuiltinDefaultPasswords()).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("内置账号清单:与初始化种子账号完全一致")
  void shouldCoverSeededAccounts_whenListingBuiltinUsernames() {
    assertThat(ConsoleDefaultPasswordGuard.BUILTIN_USERNAMES)
        .containsExactlyInAnyOrder("admin", "auditor", "config-admin");
  }
}
