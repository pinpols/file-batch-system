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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@DisplayName("OIDC 登录成功处理:仅为显式映射的本地账号签发平台会话")
class ConsoleOidcAuthenticationSuccessHandlerTest {

  private static final String TENANT_ID = "tenant-a";
  private static final String ISSUER = "https://idp.example.com/issuer";
  private static final String REGISTRATION_ID = "pilot-tenant";

  private ConsoleOidcProperties properties;
  private ConsoleOidcIdentityMapper identityMapper;
  private ConsoleAuthApplicationService authApplicationService;
  private ConsoleOidcLoginAudit loginAudit;
  private ConsoleOidcAuthenticationSuccessHandler handler;

  @BeforeEach
  void setUp() {
    properties = new ConsoleOidcProperties();
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
            Set.of("ROLE_TENANT_USER"),
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

  @Test
  @DisplayName("OIDC 开关关闭时拒绝身份且不查询映射")
  void shouldRejectInvalidIdentity_whenOidcDisabled() throws Exception {
    properties.setEnabled(false);

    MockHttpServletResponse response = invoke("external-sub", ISSUER, REGISTRATION_ID);

    assertInvalidIdentityRejected(response);
  }

  @Test
  @DisplayName("认证对象不是 OAuth2 令牌时拒绝身份且不查询映射")
  void shouldRejectInvalidIdentity_whenAuthenticationIsNotOAuth2Token() throws Exception {
    OidcUser user = oidcUser("external-sub", ISSUER);

    MockHttpServletResponse response = invoke(new TestingAuthenticationToken(user, null));

    assertInvalidIdentityRejected(response);
  }

  @Test
  @DisplayName("issuer 缺失时拒绝身份且不抛出空指针")
  void shouldRejectInvalidIdentity_whenIssuerMissing() throws Exception {
    MockHttpServletResponse response = invoke("external-sub", null, REGISTRATION_ID);

    assertInvalidIdentityRejected(response);
  }

  @Test
  @DisplayName("subject 为空白时拒绝身份且不查询映射")
  void shouldRejectInvalidIdentity_whenSubjectBlank() throws Exception {
    MockHttpServletResponse response = invoke("   ", ISSUER, REGISTRATION_ID);

    assertInvalidIdentityRejected(response);
  }

  @Test
  @DisplayName("subject 超过 255 个字符时拒绝身份且不查询映射")
  void shouldRejectInvalidIdentity_whenSubjectLongerThan255() throws Exception {
    MockHttpServletResponse response = invoke("a".repeat(256), ISSUER, REGISTRATION_ID);

    assertInvalidIdentityRejected(response);
  }

  @Test
  @DisplayName("subject 含非 ASCII 字符时拒绝身份且不查询映射")
  void shouldRejectInvalidIdentity_whenSubjectNotAscii() throws Exception {
    MockHttpServletResponse response = invoke("用户", ISSUER, REGISTRATION_ID);

    assertInvalidIdentityRejected(response);
  }

  @Test
  @DisplayName("subject 恰好 255 个 ASCII 字符时进入身份映射")
  void shouldAcceptSubjectOfExactly255Ascii() throws Exception {
    String subject = "a".repeat(255);
    when(identityMapper.findEnabledAccount(TENANT_ID, ISSUER, subject))
        .thenReturn(Optional.empty());

    invoke(subject, ISSUER, REGISTRATION_ID);

    verify(identityMapper).findEnabledAccount(TENANT_ID, ISSUER, subject);
  }

  private void assertInvalidIdentityRejected(MockHttpServletResponse response) {
    assertThat(response.getStatus()).isEqualTo(302);
    assertThat(response.getRedirectedUrl()).isEqualTo("/login?authError=oidc");
    assertThat(response.getHeader(HttpHeaders.SET_COOKIE)).isNull();
    verify(loginAudit).failed(TENANT_ID, "OIDC_IDENTITY_INVALID");
    verify(identityMapper, never()).findEnabledAccount(any(), any(), any());
  }

  private MockHttpServletResponse invoke(String subject, String issuer, String registrationId)
      throws Exception {
    OidcUser user = oidcUser(subject, issuer);
    OAuth2AuthenticationToken authentication =
        new OAuth2AuthenticationToken(user, user.getAuthorities(), registrationId);
    return invoke(authentication);
  }

  private MockHttpServletResponse invoke(Authentication authentication) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
    return response;
  }

  // issuer 传 null 时模拟 IdP 未提供 iss 声明
  private OidcUser oidcUser(String subject, String issuer) throws Exception {
    OidcUser user = mock(OidcUser.class);
    when(user.getIssuer()).thenReturn(issuer == null ? null : URI.create(issuer).toURL());
    when(user.getSubject()).thenReturn(subject);
    List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
    doReturn(authorities).when(user).getAuthorities();
    return user;
  }
}
