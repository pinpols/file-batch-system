package com.example.batch.console.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.auth.ConsoleJwtService;
import com.example.batch.console.support.auth.ConsoleLoginService;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.example.batch.console.support.auth.ConsoleSessionRegistry;
import com.example.batch.console.web.request.auth.ConsoleLoginRequest;
import com.example.batch.console.web.response.auth.ConsoleAuthProfileResponse;
import com.example.batch.console.web.response.auth.ConsoleAuthTokenResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
class ConsoleAuthApplicationServiceTest {

  @Mock private ConsoleJwtService jwtService;
  @Mock private ConsoleLoginService loginService;
  @Mock private ConsoleSessionRegistry sessionRegistry;
  @Mock private ConsoleRequestMetadataResolver requestMetadataResolver;

  private ConsoleSecurityProperties securityProperties;
  private ConsoleAuthApplicationService service;

  @BeforeEach
  void setUp() {
    securityProperties = new ConsoleSecurityProperties();
    securityProperties.setDefaultTenantId("default-tenant");
    securityProperties.setDefaultAuthorities(List.of("ROLE_ADMIN"));

    service =
        new ConsoleAuthApplicationService(
            jwtService, loginService, sessionRegistry, securityProperties, requestMetadataResolver);
  }

  @Test
  void login_delegatesToLoginService() {
    ConsoleLoginRequest request = new ConsoleLoginRequest();
    request.setUsername("admin");
    request.setPassword("pass");
    ConsoleAuthTokenResponse response = stubTokenResponse();
    when(loginService.login(request)).thenReturn(response);

    ConsoleAuthTokenResponse result = service.login(request);

    assertThat(result).isSameAs(response);
    verify(loginService).login(request);
  }

  @Test
  void issueToken_usesConsolePrincipalWhenPresent() {
    ConsolePrincipal principal = new ConsolePrincipal("alice", "t1", Set.of("ROLE_ADMIN"));
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            principal, "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    when(sessionRegistry.nextSessionVersion("alice", "t1")).thenReturn(1L);
    when(jwtService.issueToken(anyString(), anyString(), any(), anyLong()))
        .thenReturn(stubTokenResponse());

    service.issueToken(auth);

    verify(jwtService).issueToken("alice", "t1", Set.of("ROLE_ADMIN"), 1L);
  }

  @Test
  void profile_returnsConsolePrincipalFieldsWhenPresent() {
    ConsolePrincipal principal = new ConsolePrincipal("alice", "t1", Set.of("ROLE_ADMIN"));
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            principal, "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.username()).isEqualTo("alice");
    assertThat(response.tenantId()).isEqualTo("t1");
    assertThat(response.authorities()).containsExactly("ROLE_ADMIN");
  }

  @Test
  void profile_usesRequestMetadataTenantWhenNoConsolePrincipal() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            "non-principal-user", "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "tr-1", null, null, null, "127.0.0.1"));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.tenantId())
        .isEqualTo("default-tenant"); // metadata.tenantId() is null → fallback
  }

  @Test
  void profile_fallsBackToDefaultTenantWhenMetadataEmpty() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            "user", "creds", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "tr-1", null, null, null, "127.0.0.1"));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.tenantId()).isEqualTo("default-tenant");
  }

  @Test
  void profile_usesDefaultAuthoritiesWhenGrantedContainsOnlyRoleUser() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            "user", "creds", List.of(new SimpleGrantedAuthority("ROLE_USER")));
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "tr-1", null, null, null, "127.0.0.1"));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.authorities())
        .containsExactlyElementsOf(securityProperties.getDefaultAuthorities());
  }

  @Test
  void profile_handlesNullAuthentication() {
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "tr-1", null, null, null, "127.0.0.1"));

    ConsoleAuthProfileResponse response = service.profile(null);

    assertThat(response.username()).isNull();
    assertThat(response.tenantId()).isEqualTo("default-tenant");
  }

  private ConsoleAuthTokenResponse stubTokenResponse() {
    return new ConsoleAuthTokenResponse(
        "jwt-token",
        "Bearer",
        Instant.now(),
        Instant.now().plusSeconds(3600),
        "admin",
        "t1",
        Set.of("ROLE_ADMIN"));
  }
}
