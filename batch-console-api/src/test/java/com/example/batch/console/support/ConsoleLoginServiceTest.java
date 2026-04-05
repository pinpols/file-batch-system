package com.example.batch.console.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.web.request.ConsoleLoginRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
        loginService = new ConsoleLoginService(properties, jwtService, sessionRegistry, userAccountService, passwordHasher);
    }

    @Test
    void shouldIssueJwtForSeededUser() {
        ConsoleLoginRequest request = new ConsoleLoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        Mockito.when(userAccountService.findByTenantAndUsername("default-tenant", "admin"))
                .thenReturn(java.util.Optional.of(new ConsoleUserAccount(
                        "default-tenant",
                        "admin",
                        "Console Admin",
                        "$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s",
                        java.util.Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN"),
                        true
                )));
        Mockito.when(passwordHasher.matches("admin123", "$argon2id$v=19$m=16384,t=2,p=1$k18enAVVcHofGDMPXPxj5A$5TityFxKIX2z6bkuDXRHqmwuPcfr+G9MEA36Kr6fC4s"))
                .thenReturn(true);
        Mockito.when(sessionRegistry.nextSessionVersion("admin", "default-tenant")).thenReturn(7L);
        Mockito.when(jwtService.issueToken("admin", "default-tenant", java.util.Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN"), 7L))
                .thenReturn(new com.example.batch.console.web.response.ConsoleAuthTokenResponse(
                        "jwt",
                        "Bearer",
                        java.time.Instant.parse("2026-04-05T00:00:00Z"),
                        java.time.Instant.parse("2026-04-05T08:00:00Z"),
                        "admin",
                        "default-tenant",
                        java.util.Set.of("ROLE_ADMIN", "ROLE_AUDITOR", "ROLE_CONFIG_ADMIN")
                ));

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
        Mockito.when(userAccountService.findByTenantAndUsername("default-tenant", "admin"))
                .thenReturn(java.util.Optional.of(new ConsoleUserAccount(
                        "default-tenant",
                        "admin",
                        "Console Admin",
                        "hash",
                        java.util.Set.of("ROLE_ADMIN"),
                        true
                )));
        Mockito.when(passwordHasher.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("invalid username or password");
    }

    @Test
    void shouldRejectMissingAccountOnAnotherTenant() {
        ConsoleLoginRequest request = new ConsoleLoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        request.setTenantId("other-tenant");
        Mockito.when(userAccountService.findByTenantAndUsername("other-tenant", "admin"))
                .thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> loginService.login(request))
                .isInstanceOf(BizException.class);
    }
}
