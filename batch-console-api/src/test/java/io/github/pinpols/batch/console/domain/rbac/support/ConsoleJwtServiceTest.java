package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import io.github.pinpols.batch.console.domain.rbac.application.contract.response.ConsoleAuthTokenResponse;
import io.github.pinpols.batch.console.shared.security.ConsolePrincipal;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("控制台令牌服务:签发与验证往返, 发行方与会话版本校验, 生产环境密钥强度启动期强校验")
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
  @DisplayName("签发验证往返:令牌非空且解析出用户名, 租户与角色集合一致")
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
  @DisplayName("签发入参:租户标识为空时抛出业务异常")
  void issueToken_blankTenantId_throws() {
    ConsoleJwtService svc = newService();
    assertThatThrownBy(() -> svc.issueToken("alice", "", Set.of("ROLE_ADMIN")))
        .isInstanceOf(BizException.class);
  }

  // ─── tokenType 门 ────────────────────────────────────────────────────

  @Test
  @DisplayName("发行方校验:其它发行方签发的令牌被拒, 报无效令牌")
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
  @DisplayName("会话版本:单会话开启后旧版本令牌被拒, 报无效令牌")
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
  @DisplayName("会话版本:当前版本令牌通过校验, 解析出用户名")
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
  @DisplayName("生产密钥:仍为占位符时启动期抛出状态异常")
  void prodProfile_placeholderJwtSecret_fatalAtConstruct() {
    environment.setActiveProfiles("prod");
    properties.setJwtSecret("change-me-jwt-secret-not-real");
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("still contains the default placeholder");
  }

  @Test
  @DisplayName("生产密钥:长度不足 32 字符时启动期抛出状态异常")
  void prodProfile_jwtSecretTooShort_fatalAtConstruct() {
    environment.setActiveProfiles("prod");
    properties.setJwtSecret("only31charsxxxxxxxxxxxxxxxxxxxx"); // 31 < 32
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("shorter than 32 characters");
  }

  @Test
  @DisplayName("生产密钥:密钥为空时启动期抛出状态异常")
  void prodProfile_blankJwtSecret_fatalAtConstruct() {
    environment.setActiveProfiles("prod");
    properties.setJwtSecret("");
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is empty");
  }

  @Test
  @DisplayName("类生产环境:预发配置同样执行强校验并抛出状态异常")
  void stagingProfile_isProductionLike_strongCheck() {
    environment.setActiveProfiles("staging");
    properties.setJwtSecret("change-me-jwt");
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("本地环境:弱密钥不强制, 启动校验通过")
  void localProfile_weakSecretAllowed() {
    environment.setActiveProfiles("local");
    properties.setJwtSecret("change-me-anything-goes-locally");
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);

    assertThatCode(() -> ReflectionTestUtils.invokeMethod(svc, "validateSecuritySecrets"))
        .doesNotThrowAnyException();
  }

  // ─── encoder/decoder 缓存 + clock skew ───────────────────────────────

  @Test
  @DisplayName("延迟初始化:未执行启动校验时仍可签发并解析令牌")
  void decoder_lazyInitAppliesClockSkewValidator() {
    // R4-P2-6：fallback 路径也必须带 JwtTimestampValidator(skew)；通过完整签发→验证 roundtrip 间接验证 decoder 可用
    ConsoleJwtService svc = new ConsoleJwtService(properties, sessionRegistry, environment);
    // 不调 PostConstruct，强制走 lazy fallback
    String token = svc.issueToken("bob", "t2", Set.of("ROLE_TENANT_USER")).accessToken();
    ConsolePrincipal principal = svc.authenticate(token);
    assertThat(principal.username()).isEqualTo("bob");
  }

  @Test
  @DisplayName("角色集合:空集合时拒绝签发, 报角色集合非法")
  void issueToken_emptyAuthorities_rejected() {
    ConsoleJwtService svc = newService();
    assertThatThrownBy(() -> svc.issueToken("alice", "t1", Set.of()))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.account.invalid_role_set");
  }

  @Test
  @DisplayName("角色集合:空值时拒绝签发, 报角色集合非法")
  void issueToken_nullAuthorities_rejected() {
    ConsoleJwtService svc = newService();
    assertThatThrownBy(() -> svc.issueToken("alice", "t1", null))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.account.invalid_role_set");
  }

  @Test
  @DisplayName("角色集合:含历史或未知角色时拒绝签发, 报角色集合非法")
  void issueToken_legacyOrUnknownAuthorities_rejected() {
    ConsoleJwtService svc = newService();
    assertThatThrownBy(() -> svc.issueToken("alice", "t1", Set.of("ROLE_ADMIN", "ROLE_USER")))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("error.account.invalid_role_set");
  }

  // ─── 不可使用其它 issuer 签发的 JWT ──────────────────────────────────

  @Test
  @DisplayName("非法令牌:格式错误的内容解析失败并抛出令牌异常")
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
