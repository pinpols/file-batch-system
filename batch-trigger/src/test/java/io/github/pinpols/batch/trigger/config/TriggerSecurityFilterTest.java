package io.github.pinpols.batch.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class TriggerSecurityFilterTest {

  private static final String SECRET = "trigger-internal-secret";
  private static final String HEADER = "X-Internal-Secret";

  private BatchSecurityProperties securityProperties;
  private TriggerSecurityConfiguration.InternalSecretFilter filter;

  @BeforeEach
  void setUp() {
    securityProperties = new BatchSecurityProperties();
    securityProperties.setInternalSecret(SECRET);
    securityProperties.setBypassMode(false);
    filter = new TriggerSecurityConfiguration.InternalSecretFilter(securityProperties);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void rejects401WhenHeaderMissing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/triggers/launch");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void rejects401WhenHeaderMismatched() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/triggers/launch");
    request.addHeader(HEADER, "wrong-secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    verify(chain, never()).doFilter(any(), any());
  }

  @Test
  void passesAndSetsAuthenticatedWhenHeaderMatches() throws Exception {
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
    assertThat(SecurityContextHolder.getContext().getAuthentication().isAuthenticated()).isTrue();
  }

  @Test
  void skipsFilterForActuatorEndpoints() throws Exception {
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
  void allowsAnyRequestInBypassMode() throws Exception {
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
}
