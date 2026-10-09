package io.github.pinpols.batch.console.infrastructure.rbac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.console.config.ConsoleSecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

@DisplayName("OIDC 授权请求仓库:短期保存并原子消费 state")
class RedisOidcAuthorizationRequestRepositoryTest {

  @SuppressWarnings("unchecked")
  @Test
  @DisplayName("有效 state 只能原子消费一次")
  void shouldConsumeAuthorizationRequestOnlyOnce_whenStateIsValid() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    AtomicReference<String> savedKey = new AtomicReference<>();
    AtomicReference<String> savedValue = new AtomicReference<>();
    AtomicReference<Cookie> savedCookie = new AtomicReference<>();
    doAnswer(invocation -> {
          savedKey.set(invocation.getArgument(0));
          savedValue.set(invocation.getArgument(1));
          return null;
        })
        .when(values)
        .set(anyString(), anyString(), any(Duration.class));
    when(values.getAndDelete(anyString())).thenAnswer(invocation -> {
      if (invocation.getArgument(0).equals(savedKey.get())) {
        return savedValue.getAndSet(null);
      }
      return null;
    });
    RedisOidcAuthorizationRequestRepository repository =
        new RedisOidcAuthorizationRequestRepository(
            redis, new ObjectMapper(), new ConsoleSecurityProperties());
    OAuth2AuthorizationRequest original = OAuth2AuthorizationRequest.authorizationCode()
        .authorizationUri("https://idp.example.com/authorize")
        .clientId("console-client")
        .redirectUri("https://console.example.com/login/oauth2/code/pilot-tenant")
        .scopes(Set.of("openid", "profile"))
        .state("state-that-must-not-be-a-redis-key")
        .additionalParameters(Map.of("prompt", "login"))
        .attributes(Map.of(
            "registration_id", "pilot-tenant",
            "nonce", "one-time-nonce",
            "code_verifier", "pkce-verifier"))
        .build();

    MockHttpServletResponse authorizationResponse = new MockHttpServletResponse();
    repository.saveAuthorizationRequest(
        original, new MockHttpServletRequest(), authorizationResponse);
    savedCookie.set(cookieFrom(authorizationResponse.getHeader("Set-Cookie")));

    assertThat(savedKey.get()).startsWith("batch:console:oidc:auth-request:");
    assertThat(savedKey.get()).doesNotContain("state-that-must-not-be-a-redis-key");
    assertThat(savedKey.get()).hasSize("batch:console:oidc:auth-request:".length() + 129);
    assertThat(savedValue.get()).contains("pkce-verifier");
    assertThat(savedCookie.get().getName()).startsWith("batch_oidc_browser_");
    assertThat(authorizationResponse.getHeader("Set-Cookie"))
        .contains("HttpOnly")
        .contains("SameSite=Lax")
        .contains("Path=/");
    verify(values).set(savedKey.get(), savedValue.get(), Duration.ofMinutes(5));

    MockHttpServletRequest callback = new MockHttpServletRequest();
    callback.setParameter("state", original.getState());
    assertThat(repository.removeAuthorizationRequest(callback, mock(HttpServletResponse.class)))
        .as("缺少发起浏览器 Cookie 时不能消费 state")
        .isNull();
    verify(values, org.mockito.Mockito.never()).getAndDelete(savedKey.get());

    callback.setCookies(new Cookie(savedCookie.get().getName(), "A".repeat(43)));
    assertThat(repository.removeAuthorizationRequest(callback, mock(HttpServletResponse.class)))
        .as("其他浏览器的 Cookie 不能消费 state")
        .isNull();
    verify(values, org.mockito.Mockito.never()).getAndDelete(savedKey.get());

    callback.setCookies(savedCookie.get());
    MockHttpServletResponse callbackResponse = new MockHttpServletResponse();
    OAuth2AuthorizationRequest restored =
        repository.removeAuthorizationRequest(callback, callbackResponse);

    assertThat(restored.getState()).isEqualTo(original.getState());
    assertThat(restored.getAttributes()).containsEntry("nonce", "one-time-nonce");
    assertThat(restored.getAttributes()).containsEntry("code_verifier", "pkce-verifier");
    assertThat(restored.getAdditionalParameters()).containsEntry("prompt", "login");
    assertThat(callbackResponse.getHeader("Set-Cookie"))
        .contains("Max-Age=0")
        .contains("HttpOnly")
        .contains("SameSite=Lax");
    assertThat(repository.removeAuthorizationRequest(callback, mock(HttpServletResponse.class)))
        .isNull();
  }

  @Test
  @DisplayName("回调缺少 state 时不访问 Redis")
  void shouldSkipRedisLookup_whenCallbackHasNoState() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RedisOidcAuthorizationRequestRepository repository =
        new RedisOidcAuthorizationRequestRepository(
            redis, new ObjectMapper(), new ConsoleSecurityProperties());

    assertThat(repository.removeAuthorizationRequest(
            new MockHttpServletRequest(), new MockHttpServletResponse()))
        .isNull();
    org.mockito.Mockito.verifyNoInteractions(redis);
  }

  private static Cookie cookieFrom(String setCookieHeader) {
    String pair = setCookieHeader.substring(0, setCookieHeader.indexOf(';'));
    int separator = pair.indexOf('=');
    return new Cookie(pair.substring(0, separator), pair.substring(separator + 1));
  }
}
