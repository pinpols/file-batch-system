package io.github.pinpols.batch.console.infrastructure.rbac;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/** 跨 Console 副本保存短时 OIDC 请求，并用 Redis GETDEL 保证 callback state 只能消费一次。 */
@Component
public class RedisOidcAuthorizationRequestRepository
    implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

  private static final String KEY_PREFIX = "batch:console:oidc:auth-request:";
  private static final String COOKIE_PREFIX = "batch_oidc_browser_";
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final int MAX_STATE_LENGTH = 512;
  private static final int BROWSER_BINDING_BYTES = 32;

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final ConsoleSecurityProperties securityProperties;
  private final SecureRandom secureRandom = new SecureRandom();

  public RedisOidcAuthorizationRequestRepository(
      StringRedisTemplate redis,
      ObjectMapper objectMapper,
      ConsoleSecurityProperties securityProperties) {
    this.redis = redis;
    this.objectMapper = objectMapper;
    this.securityProperties = securityProperties;
  }

  @Override
  public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
    String state = request.getParameter("state");
    if (!validState(state)) {
      return null;
    }
    String browserBinding = browserBinding(request, state);
    if (EmptyChecks.isNull(browserBinding)) {
      return null;
    }
    return read(redis.opsForValue().get(key(state, browserBinding)));
  }

  @Override
  public void saveAuthorizationRequest(
      OAuth2AuthorizationRequest authorizationRequest,
      HttpServletRequest request,
      HttpServletResponse response) {
    if (EmptyChecks.isNull(authorizationRequest)) {
      removeByState(request, response, request.getParameter("state"));
      return;
    }
    String state = authorizationRequest.getState();
    if (!validState(state)) {
      throw new IllegalArgumentException("OIDC authorization request state is missing or invalid");
    }
    String browserBinding = newBrowserBinding();
    redis
        .opsForValue()
        .set(key(state, browserBinding), write(StoredRequest.from(authorizationRequest)), TTL);
    response.addHeader(
        HttpHeaders.SET_COOKIE, browserCookie(state, browserBinding, TTL).toString());
  }

  @Override
  public OAuth2AuthorizationRequest removeAuthorizationRequest(
      HttpServletRequest request, HttpServletResponse response) {
    String state = request.getParameter("state");
    if (!validState(state)) {
      return null;
    }
    String browserBinding = browserBinding(request, state);
    if (EmptyChecks.isNull(browserBinding)) {
      return null;
    }
    OAuth2AuthorizationRequest authorizationRequest =
        read(redis.opsForValue().getAndDelete(key(state, browserBinding)));
    response.addHeader(
        HttpHeaders.SET_COOKIE, browserCookie(state, "", Duration.ZERO).toString());
    return authorizationRequest;
  }

  private void removeByState(
      HttpServletRequest request, HttpServletResponse response, String state) {
    if (!validState(state)) {
      return;
    }
    String browserBinding = browserBinding(request, state);
    if (EmptyChecks.isNull(browserBinding)) {
      return;
    }
    redis.delete(key(state, browserBinding));
    response.addHeader(
        HttpHeaders.SET_COOKIE, browserCookie(state, "", Duration.ZERO).toString());
  }

  private String newBrowserBinding() {
    byte[] bytes = new byte[BROWSER_BINDING_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private String browserBinding(HttpServletRequest request, String state) {
    String cookieName = cookieName(state);
    if (EmptyChecks.isNull(request.getCookies())) {
      return null;
    }
    for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
      if (cookieName.equals(cookie.getName()) && validBrowserBinding(cookie.getValue())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  private ResponseCookie browserCookie(String state, String value, Duration maxAge) {
    return ResponseCookie.from(cookieName(state), value)
        .httpOnly(true)
        .secure(securityProperties.isCookieSecure())
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }

  private static String cookieName(String state) {
    return COOKIE_PREFIX + digest(state);
  }

  private OAuth2AuthorizationRequest read(String json) {
    if (EmptyChecks.isNull(json)) {
      return null;
    }
    try {
      StoredRequest stored = objectMapper.readValue(json, new TypeReference<>() {});
      return stored.toRequest();
    } catch (IOException | RuntimeException ignored) {
      // 授权 state、nonce 和 PKCE verifier 不得写入日志。
      return null;
    }
  }

  private String write(StoredRequest stored) {
    try {
      return objectMapper.writeValueAsString(stored);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not persist OIDC authorization request", exception);
    }
  }

  private static boolean validState(String state) {
    return EmptyChecks.isNotBlank(state) && state.length() <= MAX_STATE_LENGTH;
  }

  private static boolean validBrowserBinding(String value) {
    return EmptyChecks.isNotNull(value) && value.matches("[A-Za-z0-9_-]{43}");
  }

  private static String key(String state, String browserBinding) {
    return KEY_PREFIX + digest(state) + ":" + digest(browserBinding);
  }

  private static String digest(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record StoredRequest(
      String authorizationUri,
      String clientId,
      String redirectUri,
      Set<String> scopes,
      String state,
      Map<String, Object> additionalParameters,
      Map<String, Object> attributes) {

    static StoredRequest from(OAuth2AuthorizationRequest request) {
      return new StoredRequest(
          request.getAuthorizationUri(),
          request.getClientId(),
          request.getRedirectUri(),
          Set.copyOf(request.getScopes()),
          request.getState(),
          Map.copyOf(request.getAdditionalParameters()),
          Map.copyOf(request.getAttributes()));
    }

    OAuth2AuthorizationRequest toRequest() {
      return OAuth2AuthorizationRequest.authorizationCode()
          .authorizationUri(authorizationUri)
          .clientId(clientId)
          .redirectUri(redirectUri)
          .scopes(scopes)
          .state(state)
          .additionalParameters(additionalParameters)
          .attributes(attributes)
          .build();
    }
  }
}
