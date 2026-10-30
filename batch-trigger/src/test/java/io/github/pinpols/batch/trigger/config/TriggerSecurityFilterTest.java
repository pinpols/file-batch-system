package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyPrincipal;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * S5-c 守护测试:验证 trigger 模块 {@code X-Internal-Secret} 鉴权过滤器在 4 类边界条件上行为正确。
 *
 * <ul>
 *   <li>无 header → 401
 *   <li>错误 header → 401
 *   <li>正确 header → pass + 设置认证
 *   <li>{@code /actuator/**} 路径 → 跳过 filter(健康探针不带 header)
 *   <li>{@code bypass-mode=true} + 无 header → pass(本地联调放行)
 * </ul>
 *
 * <p>不开 Spring 上下文,直接构造 {@link
 * io.github.pinpols.batch.trigger.config.TriggerSecurityConfiguration.InternalSecretFilter},单测速度。
 */
@DisplayName("Trigger 内部密钥过滤器:校验 X-Internal-Secret 头在缺失/不匹配/匹配/豁免路径下的放行与拒绝")
class TriggerSecurityFilterTest {

  private static final String SECRET = "trigger-internal-secret";
  private static final String HEADER = CommonConstants.INTERNAL_SECRET_HEADER;

  private BatchSecurityProperties securityProperties;
  private TriggerSecurityConfiguration.InternalSecretFilter filter;
  private TriggerApiKeyVerifier apiKeyVerifier;

  @BeforeEach
  void setUp() {
    securityProperties = new BatchSecurityProperties();
    securityProperties.setInternalSecret(SECRET);
    securityProperties.setBypassMode(false);
    apiKeyVerifier = mock(TriggerApiKeyVerifier.class);
    filter =
        new TriggerSecurityConfiguration.InternalSecretFilter(securityProperties, apiKeyVerifier);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("缺少内部密钥请求头时返回 401 UNAUTHORIZED,且不继续执行过滤器链")
  void headerMissing_returns401() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/triggers/launch");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("请求头密钥与配置值不一致时返回 401,且不放行到后续过滤器链")
  void headerMismatched_returns401() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/triggers/launch");
    request.addHeader(HEADER, "wrong-secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("密钥匹配时放行请求,并在 SecurityContext 写入已认证的 internal 身份")
  void headerMatches_passesAndSetsAuthenticated() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/triggers/launch");
    request.addHeader(HEADER, SECRET);
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    // SecurityContextHolder 在 setAuthenticated 里被填,filter 不主动清(由 OncePerRequestFilter
    // 的外层链路负责),这里仅断言确实非 anonymous
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
        .isEqualTo("internal");
    assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated())
        .isTrue();
  }

  @Test
  @DisplayName("/actuator 健康探针路径直接放行,不校验密钥也不向 SecurityContext 写认证")
  void actuatorEndpoints_skipFilter() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    // shouldNotFilter 返回 true → 整个 filter 直接放行,doFilterInternal 不执行
    verify(chain).doFilter(request, response);
    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    // SecurityContext 不被设(filter 没跑)
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  @Test
  @DisplayName("bypass-mode 打开时无密钥请求也放行,并写入 internal 身份供本地联调")
  void bypassMode_allowsAnyRequest() throws Exception {
    securityProperties.setBypassMode(true);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/triggers/launch");
    // 不带 header
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
        .isEqualTo("internal");
  }

  @Test
  @DisplayName("租户 API Key 仅可认证事件启动/状态路由并写入来源 principal")
  void tenantApiKey_authenticatesOnlyEventRoutes() throws Exception {
    TriggerApiKeyPrincipal principal =
        new TriggerApiKeyPrincipal(7L, "tenant-a", "billing", "trigger.event", null);
    when(apiKeyVerifier.authenticate("source-key", "tenant-a")).thenReturn(Optional.of(principal));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/triggers/launch");
    request.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "source-key");
    request.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tenant-a");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    verify(chain).doFilter(request, response);
    assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
        .isEqualTo(principal);
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .contains("ROLE_EXTERNAL_EVENT_SOURCE");
  }

  @Test
  @DisplayName("API Key 不得访问 catch-up 或通过错误 key 回退共享密钥")
  void tenantApiKey_isRestrictedAndDoesNotFallback() throws Exception {
    MockHttpServletRequest forbiddenPath =
        new MockHttpServletRequest("POST", "/api/triggers/catch-up/approve");
    forbiddenPath.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "source-key");
    forbiddenPath.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tenant-a");
    MockHttpServletResponse forbiddenResponse = new MockHttpServletResponse();
    FilterChain forbiddenChain = mock(FilterChain.class);
    filter.doFilter(forbiddenPath, forbiddenResponse, forbiddenChain);
    assertThat(forbiddenResponse.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(forbiddenChain, never()).doFilter(any(), any());

    MockHttpServletRequest invalidKey = new MockHttpServletRequest("POST", "/api/triggers/launch");
    invalidKey.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "invalid-key");
    invalidKey.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tenant-a");
    invalidKey.addHeader(HEADER, SECRET);
    MockHttpServletResponse invalidResponse = new MockHttpServletResponse();
    FilterChain invalidChain = mock(FilterChain.class);
    filter.doFilter(invalidKey, invalidResponse, invalidChain);
    assertThat(invalidResponse.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(invalidChain, never()).doFilter(any(), any());
  }

  @Test
  @DisplayName("补跑核验内部入口只接受内部密钥，不接受租户 API Key")
  void catchUpVerification_requiresInternalSecret() throws Exception {
    MockHttpServletRequest external =
        new MockHttpServletRequest("POST", "/internal/trigger/catch-up/verify");
    external.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "source-key");
    external.addHeader(CommonConstants.BATCH_TENANT_ID_HEADER, "tenant-a");
    MockHttpServletResponse denied = new MockHttpServletResponse();
    FilterChain externalChain = mock(FilterChain.class);
    filter.doFilter(external, denied, externalChain);
    assertThat(denied.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(externalChain, never()).doFilter(any(), any());
    MockHttpServletRequest internal =
        new MockHttpServletRequest("POST", "/internal/trigger/catch-up/verify");
    internal.addHeader(HEADER, SECRET);
    MockHttpServletResponse allowed = new MockHttpServletResponse();
    FilterChain internalChain = mock(FilterChain.class);
    filter.doFilter(internal, allowed, internalChain);
    verify(internalChain).doFilter(internal, allowed);
  }

  @Test
  @DisplayName("CSRF 仅忽略带正确内部密钥的请求,缺失或错误密钥仍受保护")
  void csrfMatcher_ignoresOnlyAuthenticatedInternalRequests() {
    TriggerSecurityConfiguration.InternalSecretRequestMatcher matcher =
        new TriggerSecurityConfiguration.InternalSecretRequestMatcher(securityProperties);
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/triggers/launch");

    assertThat(matcher.matches(request)).isFalse();

    request.addHeader(HEADER, "wrong-secret");
    assertThat(matcher.matches(request)).isFalse();

    MockHttpServletRequest authenticatedRequest =
        new MockHttpServletRequest("POST", "/api/triggers/launch");
    authenticatedRequest.addHeader(HEADER, SECRET);
    assertThat(matcher.matches(authenticatedRequest)).isTrue();

    securityProperties.setBypassMode(true);
    assertThat(matcher.matches(new MockHttpServletRequest("POST", "/api/triggers/launch")))
        .isTrue();
  }

  @Test
  @DisplayName("外部来源 API Key 只在事件接纳与状态路由忽略 CSRF")
  void eventApiKeyCsrfMatcher_isRestrictedToEventRoutes() {
    TriggerSecurityConfiguration.EventApiKeyRequestMatcher matcher =
        new TriggerSecurityConfiguration.EventApiKeyRequestMatcher();
    MockHttpServletRequest launch = new MockHttpServletRequest("POST", "/api/triggers/launch");
    launch.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "source-key");
    MockHttpServletRequest status =
        new MockHttpServletRequest("GET", "/api/triggers/launch/status");
    status.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "source-key");
    MockHttpServletRequest catchUp =
        new MockHttpServletRequest("POST", "/api/triggers/catch-up/approve");
    catchUp.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "source-key");
    MockHttpServletRequest event = new MockHttpServletRequest("POST", "/api/triggers/events");
    event.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "source-key");
    MockHttpServletRequest eventStatus =
        new MockHttpServletRequest("GET", "/api/triggers/events/status");
    eventStatus.addHeader(CommonConstants.BATCH_API_KEY_HEADER, "source-key");

    assertThat(matcher.matches(launch)).isTrue();
    assertThat(matcher.matches(status)).isTrue();
    assertThat(matcher.matches(event)).isTrue();
    assertThat(matcher.matches(eventStatus)).isTrue();
    assertThat(matcher.matches(catchUp)).isFalse();
    assertThat(matcher.matches(new MockHttpServletRequest("POST", "/api/triggers/launch")))
        .isFalse();
  }
}
