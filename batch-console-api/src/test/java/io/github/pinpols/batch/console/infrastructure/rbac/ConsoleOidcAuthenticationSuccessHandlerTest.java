package io.github.pinpols.batch.console.infrastructure.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.config.ConsoleOidcProperties;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.audit.support.ConsoleOidcLoginAudit;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleAuthTokenResponse;
import io.github.pinpols.batch.console.domain.rbac.entity.ConsoleUserAccountEntity;
import io.github.pinpols.batch.console.domain.rbac.mapper.ConsoleOidcIdentityMapper;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleAuthApplicationService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTokenCookieWriter;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@DisplayName("OIDC 登录成功处理:仅为显式映射的本地账号签发平台会话")
class ConsoleOidcAuthenticationSuccessHandlerTest {

  private static final String TENANT_ID = "tenant-a";
  private static final String ISSUER = "https://idp.example.com/issuer";
  private static final String REGISTRATION_ID = "pilot-tenant";

  private ConsoleOidcIdentityMapper identityMapper;
  private ConsoleAuthApplicationService authApplicationService;
  private ConsoleOidcLoginAudit loginAudit;
  private ConsoleOidcAuthenticationSuccessHandler handler;

  @BeforeEach
  void setUp() {
    ConsoleOidcProperties properties = new ConsoleOidcProperties();
    properties.setEnabled(true);
    properties.setTenantId(TENANT_ID);
    properties.setIssuerUri(ISSUER);
    properties.setRegistrationId(REGISTRATION_ID);
    identityMapper = mock(ConsoleOidcIdentityMapper.class);
    authApplicationService = mock(ConsoleAuthApplicationService.class);
    loginAudit = mock(ConsoleOidcLoginAudit.class);
    handler = new ConsoleOidcAuthenticationSuccessHandler(
        properties,
        identityMapper,
        authApplicationService,
        new ConsoleTokenCookieWriter(new ConsoleSecurityProperties()),
        loginAudit);
  }

  @Test
  @DisplayName("主体映射成功时使用本地角色签发平台 Cookie")
  void shouldIssuePlatformCookie_whenSubjectMapsToLocalAccount() throws Exception {
    ConsoleUserAccountEntity account = new ConsoleUserAccountEntity();
    account.setId(42L);
    account.setTenantId(TENANT_ID);
    account.setUsername("local-user");
    account.setAuthoritiesCsv("ROLE_TENANT_USER");
    account.setEnabled(true);
    when(identityMapper.findEnabledAccount(TENANT_ID, ISSUER, "external-sub"))
        .thenReturn(Optional.of(account));
    when(authApplicationService.issueToken(any(ConsolePrincipal.class)))
        .thenReturn(new ConsoleAuthTokenResponse(
            "platform-jwt",
            "Bearer",
            Instant.parse("2026-10-09T00:00:00Z"),
            Instant.parse("2026-10-09T02:00:00Z"),
            "local-user",
            TENANT_ID,
            java.util.Set.of("ROLE_TENANT_USER"),
            false));

    MockHttpServletResponse response = invoke("external-sub", ISSUER, REGISTRATION_ID);

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getRedirectedUrl()).isEqualTo("/");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE))
        .contains("batch_console_token=platform-jwt")
        .contains("HttpOnly");
    ArgumentCaptor<ConsolePrincipal> principal = ArgumentCaptor.forClass(ConsolePrincipal.class);
    verify(authApplicationService).issueToken(principal.capture());
    assertThat(principal.getValue().authorities()).containsExactly("ROLE_TENANT_USER");
    verify(loginAudit).succeeded(TENANT_ID, "local-user");
  }

  @Test
  @DisplayName("主体未绑定时拒绝签发平台 Cookie")
  void shouldRejectUnboundSubject_withoutIssuingPlatformCookie() throws Exception {
    when(identityMapper.findEnabledAccount(TENANT_ID, ISSUER, "unbound-sub"))
        .thenReturn(Optional.empty());

    MockHttpServletResponse response = invoke("unbound-sub", ISSUER, REGISTRATION_ID);

    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getRedirectedUrl()).isEqualTo("/login?authError=oidc");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    verify(authApplicationService, never()).issueToken(any(ConsolePrincipal.class));
    verify(loginAudit).failed(TENANT_ID, "OIDC_IDENTITY_UNBOUND");
  }

  @Test
  @DisplayName("issuer 或 registration 不匹配时不查询身份映射")
  void shouldRejectIdentityLookup_whenIssuerOrRegistrationMismatches() throws Exception {
    MockHttpServletResponse response =
        invoke("external-sub", "https://attacker.example/issuer", REGISTRATION_ID);

    assertThat(response.getRedirectedUrl()).isEqualTo("/login?authError=oidc");
    verify(identityMapper, never()).findEnabledAccount(any(), any(), any());
    verify(authApplicationService, never()).issueToken(any(ConsolePrincipal.class));
  }

  private MockHttpServletResponse invoke(String subject, String issuer, String registrationId)
      throws Exception {
    OidcUser user = mock(OidcUser.class);
    when(user.getIssuer()).thenReturn(URI.create(issuer).toURL());
    when(user.getSubject()).thenReturn(subject);
    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
    doReturn(authorities).when(user).getAuthorities();
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(user, authorities, registrationId);
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
    return response;
  }
}
