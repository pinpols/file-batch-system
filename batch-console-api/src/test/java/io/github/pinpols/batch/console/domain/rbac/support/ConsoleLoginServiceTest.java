package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.domain.rbac.web.request.ConsoleLoginRequest;
import io.github.pinpols.batch.console.domain.rbac.web.response.ConsoleAuthTokenResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConsoleLoginServiceTest {

  @Mock private ConsoleSessionRegistry sessionRegistry;
  @Mock private ConsoleJwtService jwtService;
  @Mock private ConsoleUserAccountServiceSupport userAccountService;
  @Mock private ConsolePasswordHasher passwordHasher;
  // 默认 mock:登录防护方法均 void/no-op,等效总开关关 → 既有用例行为不变。
  @Mock private LoginProtectionService loginProtectionService;

  @Mock
  private io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver
      requestMetadataResolver;

  @InjectMocks private ConsoleLoginService loginService;

  @Test
  void shouldIssueJwtForSeededUser() {
    ConsoleLoginRequest request = new ConsoleLoginRequest();
    request.setUsername("admin");
    request.setPassword("admin123");
    // 用户名全局唯一，按 username 查找，租户从账号记录中获取
    Mockito.when(userAccountService.findByUsername("admin"))
        .thenReturn(
            Optional.of(
                new ConsoleUserAccount(
                    "default-tenant",
                    "admin",
                    "Console Admin",
                    ConsolePasswordHasherTest.SEED_ARGON2_ADMIN123,
                    Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN"),
                    true,
                    false)));
    Mockito.when(passwordHasher.matches("admin123", ConsolePasswordHasherTest.SEED_ARGON2_ADMIN123))
        .thenReturn(true);
    Mockito.when(sessionRegistry.nextSessionVersion("admin", "default-tenant")).thenReturn(7L);
    Mockito.when(
            jwtService.issueToken(
                "admin",
                "default-tenant",
                Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN"),
                7L))
        .thenReturn(
            new ConsoleAuthTokenResponse(
                "jwt",
                "Bearer",
                Instant.parse("2026-04-05T00:00:00Z"),
                Instant.parse("2026-04-05T08:00:00Z"),
                "admin",
                "default-tenant",
                Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN"),
                false));

    var response = loginService.login(request);
    assertThat(response.username()).isEqualTo("admin");
    assertThat(response.tenantId()).isEqualTo("default-tenant");
    assertThat(response.authorities()).contains("ROLE_ADMIN");
  }

  @Test
  void shouldRejectWrongPassword() {
    ConsoleLoginRequest request = new ConsoleLoginRequest();
    request.setUsername("admin");
    request.setPassword("wrong");
    Mockito.when(userAccountService.findByUsername("admin"))
        .thenReturn(
            Optional.of(
                new ConsoleUserAccount(
                    "default-tenant",
                    "admin",
                    "Console Admin",
                    "hash",
                    Set.of("ROLE_ADMIN"),
                    true,
                    false)));
    Mockito.when(passwordHasher.matches("wrong", "hash")).thenReturn(false);

    assertThatThrownBy(() -> loginService.login(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("invalid_credentials");
  }

  @Test
  void shouldRejectUnknownUsername() {
    ConsoleLoginRequest request = new ConsoleLoginRequest();
    request.setUsername("nonexistent");
    request.setPassword("admin123");
    Mockito.when(userAccountService.findByUsername("nonexistent")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> loginService.login(request))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("invalid_credentials");
  }
}
