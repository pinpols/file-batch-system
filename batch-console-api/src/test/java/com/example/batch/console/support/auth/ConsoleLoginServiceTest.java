package com.example.batch.console.support.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.web.request.auth.ConsoleLoginRequest;
import com.example.batch.console.web.response.auth.ConsoleAuthTokenResponse;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import com.example.batch.console.config.ConsoleSecurityProperties;

class ConsoleLoginServiceTest {

  private ConsoleLoginService loginService;
  private ConsoleSessionRegistry sessionRegistry;
  private ConsoleJwtService jwtService;
  private ConsoleUserAccountService userAccountService;
  private ConsolePasswordHasher passwordHasher;

  @BeforeEach
  void setUp() {
    ConsoleSecurityProperties properties = new ConsoleSecurityProperties();
    properties.setJwtIssuer("batch-console-api");
    properties.setJwtSecret("console-jwt-secret");
    sessionRegistry = Mockito.mock(ConsoleSessionRegistry.class);
    jwtService = Mockito.mock(ConsoleJwtService.class);
    userAccountService = Mockito.mock(ConsoleUserAccountService.class);
    passwordHasher = Mockito.mock(ConsolePasswordHasher.class);
    loginService =
        new ConsoleLoginService(
            properties, jwtService, sessionRegistry, userAccountService, passwordHasher);
  }

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
                    true)));
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
                Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN")));

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
                    true)));
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
