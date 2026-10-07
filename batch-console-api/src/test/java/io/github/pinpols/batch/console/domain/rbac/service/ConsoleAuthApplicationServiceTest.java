package io.github.pinpols.batch.console.domain.rbac.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.console.config.ConsoleMenuProperties;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.rbac.application.contract.request.ConsoleLoginRequest;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleAuthProfileResponse;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleAuthTokenResponse;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleJwtService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleLoginService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleMenuRegistry;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSessionRegistry;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleUserAccount;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleUserAccountServiceSupport;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@ExtendWith(MockitoExtension.class)
@DisplayName("认证应用服务: 登录委托、令牌签发与个人资料组装的租户解析")
class ConsoleAuthApplicationServiceTest {

  @Mock
  private ConsoleJwtService jwtService;

  @Mock
  private ConsoleLoginService loginService;

  @Mock
  private ConsoleSessionRegistry sessionRegistry;

  @Mock
  private ConsoleRequestMetadataResolver requestMetadataResolver;

  @Mock
  private ConsoleUserAccountServiceSupport userAccountService;

  private ConsoleSecurityProperties securityProperties;
  private ConsoleAuthApplicationService service;

  @BeforeEach
  void setUp() {
    securityProperties = new ConsoleSecurityProperties();
    securityProperties.setDefaultTenantId("default-tenant");
    securityProperties.setDefaultAuthorities(List.of("ROLE_ADMIN"));

    service = new ConsoleAuthApplicationService(
        jwtService,
        loginService,
        sessionRegistry,
        securityProperties,
        requestMetadataResolver,
        new ConsoleMenuRegistry(new ConsoleMenuProperties()),
        userAccountService,
        List.of((username, authorities) -> Set.of("TEST_CAPABILITY")));
    lenient().when(userAccountService.findByUsername(anyString())).thenReturn(Optional.empty());
  }

  @Test
  @DisplayName("登录请求原样委托给登录服务, 返回结果与下游一致")
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
  @DisplayName("存在已认证主体时按其用户名与租户签发令牌, 并带上会话版本")
  void issueToken_usesConsolePrincipalWhenPresent() {
    ConsolePrincipal principal = new ConsolePrincipal("alice", "t1", Set.of("ROLE_ADMIN"));
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        principal, "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    when(sessionRegistry.nextSessionVersion("alice", "t1")).thenReturn(1L);
    when(jwtService.issueToken(anyString(), anyString(), any(), anyLong()))
        .thenReturn(stubTokenResponse());

    service.issueToken(auth);

    verify(jwtService).issueToken("alice", "t1", Set.of("ROLE_ADMIN"), 1L);
  }

  @Test
  @DisplayName("存在已认证主体时资料回包用户名、租户、权限与能力集")
  void profile_returnsConsolePrincipalFieldsWhenPresent() {
    ConsolePrincipal principal = new ConsolePrincipal("alice", "t1", Set.of("ROLE_ADMIN"));
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        principal, "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.username()).isEqualTo("alice");
    assertThat(response.tenantId()).isEqualTo("t1");
    assertThat(response.authorities()).containsExactly("ROLE_ADMIN");
    assertThat(response.capabilities()).containsExactly("TEST_CAPABILITY");
    assertThat(response.mustChangePassword()).isFalse();
  }

  @Test
  @DisplayName("账号要求强制改密时资料回包该标记为真")
  void profile_includesMustChangePasswordFromUserAccount() {
    ConsolePrincipal principal = new ConsolePrincipal("alice", "t1", Set.of("ROLE_ADMIN"));
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        principal, "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    when(userAccountService.findByUsername("alice"))
        .thenReturn(Optional.of(new ConsoleUserAccount(
            "t1", "alice", "Alice", "hash", Set.of("ROLE_ADMIN"), true, true)));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.mustChangePassword()).isTrue();
  }

  @Test
  @DisplayName("无已认证主体时仍返回资料, 租户取默认值")
  void profile_usesRequestMetadataTenantWhenNoConsolePrincipal() {
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        "non-principal-user", "creds", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "tr-1", null, null, null, "127.0.0.1"));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.tenantId())
        .isEqualTo("default-tenant"); // metadata.tenantId() is null → fallback
  }

  @Test
  @DisplayName("权限为租户级用户且请求元数据无租户时, 租户回落默认值")
  void profile_fallsBackToDefaultTenantWhenMetadataEmpty() {
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        "user", "creds", List.of(new SimpleGrantedAuthority("ROLE_TENANT_USER")));
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "tr-1", null, null, null, "127.0.0.1"));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.tenantId()).isEqualTo("default-tenant");
  }

  @Test
  @DisplayName("租户级用户权限在资料回包中原样保留")
  void profile_keepsTenantUserAuthority() {
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        "user", "creds", List.of(new SimpleGrantedAuthority("ROLE_TENANT_USER")));
    when(requestMetadataResolver.current())
        .thenReturn(new ConsoleRequestMetadata("req-1", "tr-1", null, null, null, "127.0.0.1"));

    ConsoleAuthProfileResponse response = service.profile(auth);

    assertThat(response.authorities()).containsExactly("ROLE_TENANT_USER");
  }

  @Test
  @DisplayName("携带历史遗留权限查询资料时抛出业务异常")
  void profile_rejectsLegacyAuthority() {
    ConsolePrincipal principal = new ConsolePrincipal("legacy", "t1", Set.of("ROLE_USER"));
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        principal, "creds", List.of(new SimpleGrantedAuthority("ROLE_USER")));

    assertThatThrownBy(() -> service.profile(auth)).isInstanceOf(BizException.class);
  }

  @Test
  @DisplayName("认证上下文为空时资料回包用户名为空且租户取默认值")
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
        BatchDateTimeSupport.utcNow(),
        BatchDateTimeSupport.utcNow().plusSeconds(3600),
        "admin",
        "t1",
        Set.of("ROLE_ADMIN"),
        false);
  }
}
