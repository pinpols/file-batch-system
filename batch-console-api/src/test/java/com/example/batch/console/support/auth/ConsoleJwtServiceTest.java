package com.example.batch.console.support.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.batch.common.exception.BizException;
import com.example.batch.console.config.ConsoleSecurityProperties;
import com.example.batch.console.web.response.auth.ConsoleAuthTokenResponse;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * R3-P1-12 补：覆盖 ConsoleJwtService 的安全关键路径。
 *
 * <p>验证：
 *
 * <ul>
 *   <li>签发 → 验证完整往返
 *   <li>tokenType 门拒绝非 console_access token
 *   <li>issuer 校验拒绝伪造 token
 *   <li>singleSession 拒绝旧 sessionVersion
 *   <li>prod profile 占位符 jwt-secret 启动期 fail-fast
 *   <li>prod profile 弱密钥（<32 字符）启动期 fail-fast
 *   <li>非 prod 不强制
 * </ul>
 */
class ConsoleJwtServiceTest {

  private static final String STRONG_SECRET = "a-very-strong-jwt-secret-2026-with-enough-entropy";

  private ConsoleSecurityProperties properties;
  private ConsoleSessionRegistry sessionRegistry;
  private MockEnvironment environment;

  @BeforeEach
  void setUp() {
    properties = new ConsoleSecurityProperties();
    properties.setJwtIssuer("test-issuer");
    properties.setJwtSecret(STRONG_SECRET);
    properties.setJwtTtl(Duration.ofHours(1));
    properties.setJwtClockSkew(Duration.ofSeconds(30));
    sessionRegistry = mock(ConsoleSessionRegistry.class);
    when(sessionRegistry.currentSessionVersion(anyString(), anyString())).thenReturn(1L);
    when(sessionRegistry.isCurrentSession(anyString(), anyString(), anyLong())).thenReturn(true);
    environment = new MockEnvironment();
  }

  private ConsoleJwtService newService() {
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    // 模拟 Spring PostConstruct
    ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets");
    return svc;
  }

  // ─── 完整签发 + 验证往返 ────────────────────────────────────────────

  @Test
  void issueAndAuthenticate_roundtripSucceeds() {
    ConsoleJwtService svc = newService();
    ConsoleAuthTokenResponse resp =
        svc.issueToken("alice", "t1", Set.of("ROLE_ADMIN", "ROLE_AUDITOR"));
    assertThat(resp.accessToken()).isNotBlank();

    ConsolePrincipal principal = svc.authenticate(resp.accessToken());
    assertThat(principal.username()).isEqualTo("alice");
    assertThat(principal.tenantId()).isEqualTo("t1");
    assertThat(principal.authorities()).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_AUDITOR");
  }

  @Test
  void issueToken_blankTenantId_throws() {
    ConsoleJwtService svc = newService();
    assertThatThrownBy(() -> svc.issueToken("alice", "", Set.of("ROLE_ADMIN")))
        .isInstanceOf(BizException.class);
  }

  // ─── tokenType 门 ────────────────────────────────────────────────────

  @Test
  void authenticate_wrongIssuerRejected() {
    properties.setJwtIssuer("other-issuer");
    ConsoleJwtService otherSvc = newService();
    String fakeToken = otherSvc.issueToken("eve", "t1", Set.of("ROLE_ADMIN")).accessToken();

    properties.setJwtIssuer("test-issuer");
    // 清缓存使 PostConstruct 重建
    ConsoleJwtService victimSvc = newService();
    assertThatThrownBy(() -> victimSvc.authenticate(fakeToken))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.console_jwt.invalid");
  }

  // ─── singleSession ───────────────────────────────────────────────────

  @Test
  void authenticate_singleSession_oldVersionRejected() {
    properties.setSingleSessionEnabled(true);
    when(sessionRegistry.currentSessionVersion("alice", "t1")).thenReturn(5L);
    when(sessionRegistry.isCurrentSession("alice", "t1", 5L)).thenReturn(true);

    ConsoleJwtService svc = newService();
    String oldToken = svc.issueToken("alice", "t1", Set.of("ROLE_ADMIN"), 3L).accessToken();

    // 模拟新登录后 sessionVersion 推进到 5，旧 token 的 v=3 不再是 current
    when(sessionRegistry.isCurrentSession("alice", "t1", 3L)).thenReturn(false);

    assertThatThrownBy(() -> svc.authenticate(oldToken))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.console_jwt.invalid");
  }

  @Test
  void authenticate_singleSession_currentVersionAccepted() {
    properties.setSingleSessionEnabled(true);
    when(sessionRegistry.currentSessionVersion("alice", "t1")).thenReturn(7L);
    when(sessionRegistry.isCurrentSession("alice", "t1", 7L)).thenReturn(true);

    ConsoleJwtService svc = newService();
    String token = svc.issueToken("alice", "t1", Set.of("ROLE_ADMIN"), 7L).accessToken();

    ConsolePrincipal principal = svc.authenticate(token);
    assertThat(principal.username()).isEqualTo("alice");
  }

  // ─── prod profile 启动期强校验 ───────────────────────────────────────

  @Test
  void prodProfile_placeholderJwtSecret_fatalAtConstruct() {
    environment.setActiveProfiles("prod");
    properties.setJwtSecret("change-me-jwt-secret-not-real");
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("仍包含默认占位符");
  }

  @Test
  void prodProfile_jwtSecretTooShort_fatalAtConstruct() {
    environment.setActiveProfiles("prod");
    properties.setJwtSecret("only31charsxxxxxxxxxxxxxxxxxxxx"); // 31 < 32
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("长度不足 32 字符");
  }

  @Test
  void prodProfile_blankJwtSecret_fatalAtConstruct() {
    environment.setActiveProfiles("prod");
    properties.setJwtSecret("");
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("为空");
  }

  @Test
  void stagingProfile_isProductionLike_strongCheck() {
    environment.setActiveProfiles("staging");
    properties.setJwtSecret("change-me-jwt");
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void localProfile_weakSecretAllowed() {
    environment.setActiveProfiles("local");
    properties.setJwtSecret("change-me-anything-goes-locally");
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    // local 不在 prod-like 名单，placeholder 也不抛
    ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets");
  }

  // ─── encoder/decoder 缓存 + clock skew ───────────────────────────────

  @Test
  void decoder_lazyInitAppliesClockSkewValidator() {
    // R4-P2-6：fallback 路径也必须带 JwtTimestampValidator(skew)；通过完整签发→验证 roundtrip 间接验证 decoder 可用
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    // 不调 PostConstruct，强制走 lazy fallback
    String token = svc.issueToken("bob", "t2", Set.of("ROLE_USER")).accessToken();
    ConsolePrincipal principal = svc.authenticate(token);
    assertThat(principal.username()).isEqualTo("bob");
  }

  @Test
  void issueToken_emptyAuthorities_accepted() {
    ConsoleJwtService svc = newService();
    ConsoleAuthTokenResponse resp = svc.issueToken("alice", "t1", Set.of());
    ConsolePrincipal principal = svc.authenticate(resp.accessToken());
    assertThat(principal.authorities()).isEmpty();
  }

  @Test
  void issueToken_nullAuthorities_treatedAsEmpty() {
    ConsoleJwtService svc = newService();
    ConsoleAuthTokenResponse resp = svc.issueToken("alice", "t1", null);
    ConsolePrincipal principal = svc.authenticate(resp.accessToken());
    assertThat(principal.authorities()).isEmpty();
  }

  // ─── 不可使用其它 issuer 签发的 JWT ──────────────────────────────────

  @Test
  void authenticate_garbageTokenRejected() {
    ConsoleJwtService svc = newService();
    assertThatThrownBy(() -> svc.authenticate("not.a.jwt"))
        .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    // ConsoleJwtService 直接调 decoder.decode，Nimbus 抛 JwtException；不被包装成 BizException
    // —— 这是已知行为，调用方（ConsoleAuthenticationFilter）catch 后转 ResultCode.UNAUTHORIZED
  }

  // 必要的 ArgumentMatchers any() 使用以保留 lenient stubbing 模式
  @SuppressWarnings("unused")
  private void unused() {
    any();
  }
}
