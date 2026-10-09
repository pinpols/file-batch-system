package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleAuthenticationFilter;
import io.github.pinpols.batch.console.infrastructure.rbac.RedisOidcAuthorizationRequestRepository;
import io.github.pinpols.batch.console.support.maintenance.MaintenanceModeFilter;
import io.github.pinpols.batch.console.support.ratelimit.ConsoleOidcAuthorizationRateLimitFilter;
import io.github.pinpols.batch.console.support.ratelimit.ConsoleRateLimitFilter;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.WebAttributes;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

/** 经 Spring Security OAuth2 Client 和真实 Redis state store 执行完整授权码回调。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    properties = {
      "batch.console.sso.oidc.enabled=true",
      "batch.console.sso.oidc.registration-id=pilot-tenant",
      "batch.console.sso.oidc.tenant-id=oidc-flow-it",
      "batch.console.sso.oidc.client-id=console-client",
      "batch.console.sso.oidc.client-secret=test-client-secret",
      "batch.console.sso.oidc.redirect-uri=http://localhost:18080/login/oauth2/code/pilot-tenant",
      "batch.console.security.rate-limit.login-ip-limit-per-minute=1",
      "batch.startup-self-check.enabled=false"
    })
@DisplayName("OIDC Authorization Code 集成闭环")
class ConsoleOidcAuthorizationCodeIntegrationTest extends AbstractIntegrationTest {

  private static final MockWebServer IDP = startIdp();
  private static final RSAKey SIGNING_KEY = createSigningKey();
  private static final AtomicReference<String> EXPECTED_NONCE = new AtomicReference<>();
  private static final AtomicReference<String> EXPECTED_CHALLENGE = new AtomicReference<>();
  private static final AtomicBoolean PKCE_VERIFIED = new AtomicBoolean();
  private static final List<String> IDP_REQUEST_PATHS = new CopyOnWriteArrayList<>();
  private static final String ISSUER = IDP.url("/issuer").toString();
  private static final String CALLBACK = "http://localhost:18080/login/oauth2/code/pilot-tenant";

  private final JdbcTemplate jdbcTemplate;
  private final WebApplicationContext applicationContext;
  private final FilterChainProxy securityFilterChain;

  @MockitoSpyBean
  private RedisOidcAuthorizationRequestRepository authorizationRequestRepository;

  private final StringRedisTemplate redis;
  private MockMvc mockMvc;
  private String tenantId;
  private String username;

  @Autowired
  ConsoleOidcAuthorizationCodeIntegrationTest(
      JdbcTemplate jdbcTemplate,
      WebApplicationContext applicationContext,
      FilterChainProxy securityFilterChain,
      StringRedisTemplate redis) {
    this.jdbcTemplate = jdbcTemplate;
    this.applicationContext = applicationContext;
    this.securityFilterChain = securityFilterChain;
    this.redis = redis;
  }

  @DynamicPropertySource
  static void oidcProperties(DynamicPropertyRegistry registry) {
    registry.add("batch.console.sso.oidc.issuer-uri", () -> ISSUER);
  }

  @BeforeEach
  void createMappedAccount() {
    mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
        .addFilters(securityFilterChain)
        .build();
    PKCE_VERIFIED.set(false);
    EXPECTED_NONCE.set(null);
    EXPECTED_CHALLENGE.set(null);
    IDP_REQUEST_PATHS.clear();
    Set<String> staleStateKeys = redis.keys("batch:console:oidc:auth-request:*");
    if (staleStateKeys != null && !staleStateKeys.isEmpty()) {
      redis.delete(staleStateKeys);
    }
    String suffix = UUID.randomUUID().toString().replace("-", "");
    tenantId = "oidc-flow-it";
    username = "oidc-flow-user-" + suffix;
    jdbcTemplate.update(
        "insert into batch.tenant (tenant_id, tenant_name, status, created_by) "
            + "values (?, ?, 'ACTIVE', 'oidc-flow-it')",
        tenantId,
        tenantId);
    jdbcTemplate.update(
        "insert into batch.console_user_account "
            + "(tenant_id, username, display_name, password_hash, authorities_csv, enabled) "
            + "values (?, ?, 'OIDC flow test', 'test-only-not-a-password', 'ROLE_TENANT_USER', true)",
        tenantId,
        username);
    jdbcTemplate.update("""
        insert into batch.console_external_identity
            (tenant_id, account_id, issuer, subject, linked_by)
        select ?, id, ?, 'test-subject-oidc', 'oidc-flow-it'
        from batch.console_user_account where tenant_id = ? and username = ?
        """, tenantId, ISSUER, tenantId, username);
  }

  @AfterEach
  void removeMappedAccount() {
    if (tenantId == null) {
      return;
    }
    jdbcTemplate.update(
        "delete from batch.console_user_account where tenant_id = ? and username = ?",
        tenantId,
        username);
    jdbcTemplate.update("delete from batch.tenant where tenant_id = ?", tenantId);
  }

  @Test
  @DisplayName("校验 state、nonce、PKCE 与签名后仅签发本地平台 Cookie，state 不可重放")
  void shouldCompleteAuthorizationCodeFlow_whenStateIsValidAndRejectReplay() throws Exception {
    MvcResult authorization = mockMvc
        .perform(get("/oauth2/authorization/pilot-tenant"))
        .andExpect(status().is3xxRedirection())
        .andReturn();
    String authorizationUrl = authorization.getResponse().getRedirectedUrl();
    assertThat(authorizationUrl).startsWith(IDP.url("/authorize").toString());
    var parameters =
        UriComponentsBuilder.fromUriString(authorizationUrl).build().getQueryParams();
    String state = URLDecoder.decode(parameters.getFirst("state"), StandardCharsets.UTF_8);
    Cookie browserCookie =
        browserCookie(authorization.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
    String nonce = parameters.getFirst("nonce");
    EXPECTED_NONCE.set(nonce);
    EXPECTED_CHALLENGE.set(parameters.getFirst("code_challenge"));
    ArgumentCaptor<org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest>
        savedRequest = ArgumentCaptor.forClass(
            org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest.class);
    verify(authorizationRequestRepository)
        .saveAuthorizationRequest(
            savedRequest.capture(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    String savedState = savedRequest.getValue().getState();
    assertThat(state.equals(savedState))
        .as("redirect state hash=%s, stored state hash=%s", hash(state), hash(savedState))
        .isTrue();
    assertThat(state).isNotBlank();
    assertThat(nonce).isNotBlank();
    assertThat(parameters.getFirst("code_challenge_method")).isEqualTo("S256");
    MockHttpServletRequest callbackRequest = new MockHttpServletRequest();
    callbackRequest.setParameter("state", state);
    callbackRequest.setCookies(browserCookie);
    String stateKeyPrefix = "batch:console:oidc:auth-request:"
        + HexFormat.of()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.UTF_8)))
        + ":";
    Set<String> currentStateKeys = redis.keys("batch:console:oidc:auth-request:*");
    String stateKey = currentStateKeys == null
        ? null
        : currentStateKeys.stream()
            .filter(key -> key.startsWith(stateKeyPrefix))
            .findFirst()
            .orElse(null);
    String storedAuthorizationRequest =
        stateKey == null ? null : redis.opsForValue().get(stateKey);
    assertThat(storedAuthorizationRequest)
        .as("expected state hash; Redis state keys=%s", currentStateKeys)
        .isNotNull();
    assertThat(authorizationRequestRepository.loadAuthorizationRequest(callbackRequest))
        .as("Redis must restore state before callback")
        .isNotNull();

    MvcResult unboundCallback = mockMvc
        .perform(
            get(CALLBACK).queryParam("code", "test-authorization-code").queryParam("state", state))
        .andExpect(status().is3xxRedirection())
        .andReturn();
    assertThat(unboundCallback.getResponse().getRedirectedUrl())
        .as("another browser must not consume the authorization request")
        .isEqualTo("/login?authError=oidc");
    assertThat(String.join(" ", unboundCallback.getResponse().getHeaders(HttpHeaders.SET_COOKIE)))
        .doesNotContain("batch_console_token=");

    MvcResult callback = mockMvc
        .perform(get(CALLBACK)
            .cookie(browserCookie)
            .queryParam("code", "test-authorization-code")
            .queryParam("state", state))
        .andExpect(status().is3xxRedirection())
        .andReturn();

    String latestErrorCode = jdbcTemplate.query(
        "select error_code from batch.console_operation_audit "
            + "where tenant_id = ? and action = 'auth.oidc.login' order by id desc limit 1",
        result -> result.next() ? result.getString(1) : null,
        tenantId);
    Object authenticationFailure =
        callback.getRequest().getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION);
    assertThat(callback.getResponse().getRedirectedUrl())
        .as(
            "IdP paths=%s, PKCE verified=%s, last audit error=%s, auth exception=%s",
            IDP_REQUEST_PATHS,
            PKCE_VERIFIED.get(),
            latestErrorCode,
            authenticationFailure == null
                ? "none"
                : authenticationFailure.getClass().getSimpleName())
        .isEqualTo("/");
    String callbackCookies =
        String.join(" ", callback.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
    assertThat(callbackCookies)
        .contains("batch_console_token=")
        .contains("HttpOnly")
        .contains("SameSite=Lax");
    assertThat(callbackCookies).contains(browserCookie.getName() + "=").contains("Max-Age=0");
    assertThat(callback.getResponse().getContentAsString()).doesNotContain("access_token");
    assertThat(PKCE_VERIFIED.get()).isTrue();

    MvcResult replay = mockMvc
        .perform(get(CALLBACK)
            .cookie(browserCookie)
            .queryParam("code", "test-authorization-code")
            .queryParam("state", state))
        .andExpect(status().is3xxRedirection())
        .andReturn();
    assertThat(replay.getResponse().getRedirectedUrl()).isEqualTo("/login?authError=oidc");
    assertThat(String.join(" ", replay.getResponse().getHeaders(HttpHeaders.SET_COOKIE)))
        .doesNotContain("batch_console_token=");
  }

  private static Cookie browserCookie(List<String> setCookieHeaders) {
    String header = setCookieHeaders.stream()
        .filter(value -> value.startsWith("batch_oidc_browser_"))
        .findFirst()
        .orElseThrow(() -> new AssertionError("OIDC browser-binding cookie was not issued"));
    String pair = header.substring(0, header.indexOf(';'));
    int separator = pair.indexOf('=');
    return new Cookie(pair.substring(0, separator), pair.substring(separator + 1));
  }

  @Test
  @DisplayName("真实安全过滤器链在 OAuth2 重定向处理前运行 OIDC 登录限流")
  void shouldRunOidcRateLimitBeforeAuthorizationRedirectFilter() {
    List<jakarta.servlet.Filter> filters =
        securityFilterChain.getFilters("/oauth2/authorization/pilot-tenant");
    int rateLimitIndex = indexOfFilter(filters, ConsoleOidcAuthorizationRateLimitFilter.class);
    int redirectIndex = indexOfFilter(filters, OAuth2AuthorizationRequestRedirectFilter.class);

    assertThat(rateLimitIndex).isGreaterThanOrEqualTo(0);
    assertThat(redirectIndex).isGreaterThan(rateLimitIndex);

    assertServletRegistrationDisabled("consoleOidcAuthorizationRateLimitFilterRegistration");
    assertServletRegistrationDisabled("consoleRateLimitFilterRegistration");
    assertServletRegistrationDisabled("consoleAuthenticationFilterRegistration");
    assertServletRegistrationDisabled("maintenanceModeFilterRegistration");
    assertThat(indexOfFilter(filters, ConsoleAuthenticationFilter.class)).isGreaterThanOrEqualTo(0);
    assertThat(indexOfFilter(filters, MaintenanceModeFilter.class)).isGreaterThanOrEqualTo(0);
    assertThat(indexOfFilter(filters, ConsoleRateLimitFilter.class)).isGreaterThanOrEqualTo(0);
  }

  private void assertServletRegistrationDisabled(String beanName) {
    Object registration = applicationContext.getBean(beanName);
    assertThat(registration).isInstanceOf(FilterRegistrationBean.class);
    assertThat(((FilterRegistrationBean<?>) registration).isEnabled())
        .as("%s must run only inside the Spring Security filter chain", beanName)
        .isFalse();
  }

  @Test
  @DisplayName("同一 IP 超过限额后在跳转 IdP 前返回 429")
  void shouldRejectAuthorizationStartBeforeRedirect_whenIpLimitIsReached() throws Exception {
    mockMvc
        .perform(get("/oauth2/authorization/pilot-tenant").with(request -> {
          request.setRemoteAddr("192.0.2.41");
          return request;
        }))
        .andExpect(status().is3xxRedirection());

    mockMvc
        .perform(get("/oauth2/authorization/pilot-tenant").with(request -> {
          request.setRemoteAddr("192.0.2.41");
          return request;
        }))
        .andExpect(status().isTooManyRequests());
  }

  private int indexOfFilter(
      List<jakarta.servlet.Filter> filters, Class<? extends jakarta.servlet.Filter> filterType) {
    for (int index = 0; index < filters.size(); index++) {
      if (filterType.isInstance(filters.get(index))) {
        return index;
      }
    }
    return -1;
  }

  @AfterAll
  static void stopIdp() throws Exception {
    IDP.close();
  }

  private static MockWebServer startIdp() {
    MockWebServer server = new MockWebServer();
    server.setDispatcher(new Dispatcher() {
      @Override
      public MockResponse dispatch(RecordedRequest request) {
        String path = request.getUrl().encodedPath();
        IDP_REQUEST_PATHS.add(path);
        if ("/issuer/.well-known/openid-configuration".equals(path)) {
          return json("""
              {"issuer":"%s","authorization_endpoint":"%s","token_endpoint":"%s",
               "jwks_uri":"%s","userinfo_endpoint":"%s","response_types_supported":["code"],
               "subject_types_supported":["public"],"id_token_signing_alg_values_supported":["RS256"],
               "token_endpoint_auth_methods_supported":["client_secret_basic"],
               "grant_types_supported":["authorization_code"],"scopes_supported":["openid","profile"],
               "code_challenge_methods_supported":["S256"]}
              """.formatted(
                  IDP.url("/issuer"),
                  IDP.url("/authorize"),
                  IDP.url("/token"),
                  IDP.url("/jwks"),
                  IDP.url("/userinfo")));
        }
        if ("/jwks".equals(path)) {
          return json(new JWKSet(SIGNING_KEY.toPublicJWK()).toString());
        }
        if ("/token".equals(path)) {
          String form = request.getBody().utf8();
          String verifier = formValue(form, "code_verifier");
          try {
            String actualChallenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            PKCE_VERIFIED.set(actualChallenge.equals(EXPECTED_CHALLENGE.get()));
            return json("""
                {"access_token":"test-access-token","token_type":"Bearer","expires_in":300,
                 "id_token":"%s"}
                """.formatted(idToken(EXPECTED_NONCE.get())));
          } catch (Exception exception) {
            return new MockResponse.Builder().code(500).build();
          }
        }
        if ("/userinfo".equals(path)) {
          return json("{" + "\"sub\":\"test-subject-oidc\"}");
        }
        return new MockResponse.Builder().code(404).build();
      }
    });
    try {
      server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
      return server;
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static MockResponse json(String body) {
    return new MockResponse.Builder()
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build();
  }

  private static RSAKey createSigningKey() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      KeyPair pair = generator.generateKeyPair();
      return new RSAKey.Builder((java.security.interfaces.RSAPublicKey) pair.getPublic())
          .privateKey(pair.getPrivate())
          .keyID("oidc-it-key")
          .build();
    } catch (Exception exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static String idToken(String nonce) throws Exception {
    Instant now = Instant.now();
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .subject("test-subject-oidc")
        .audience("console-client")
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(300)))
        .claim("nonce", nonce)
        .build();
    SignedJWT jwt = new SignedJWT(
        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(SIGNING_KEY.getKeyID()).build(), claims);
    jwt.sign(new RSASSASigner(SIGNING_KEY));
    return jwt.serialize();
  }

  private static String formValue(String form, String name) {
    for (String pair : form.split("&")) {
      String[] parts = pair.split("=", 2);
      if (parts.length == 2 && name.equals(URLDecoder.decode(parts[0], StandardCharsets.UTF_8))) {
        return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private static String hash(String value) {
    if (value == null) {
      return "null";
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
