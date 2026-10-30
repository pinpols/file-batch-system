package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.security.SecretComparator;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.web.ServletRequestPaths;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyPrincipal;
import io.github.pinpols.batch.trigger.auth.TriggerApiKeyVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Trigger 模块安全配置。
 *
 * <p>内部端点使用 {@code X-Internal-Secret}；受限租户 API Key 仅能访问外部 EVENT 启动及其状态查询。
 * 当 {@code batch.security.bypass-mode=true} 时跳过校验，保持本地联调体验不变。
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@RequiredArgsConstructor
public class TriggerSecurityConfiguration {

  private final BatchSecurityProperties securityProperties;
  private final TriggerApiKeyVerifier apiKeyVerifier;

  @Bean
  @SuppressWarnings("java:S4502")
  public SecurityFilterChain triggerSecurityFilterChain(HttpSecurity http) throws Exception {
    // 内部密钥请求无法由浏览器伪造，因为该密钥不存储在 Cookie 中。
    // 其他请求仍须启用 CSRF，避免未来新增认证路径时无意间
    // 继承未受保护的状态变更接口。
    http.csrf(csrf -> csrf.ignoringRequestMatchers(
            new InternalSecretRequestMatcher(securityProperties), new EventApiKeyRequestMatcher()))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth ->
            auth.requestMatchers("/actuator/**").permitAll().anyRequest().authenticated())
        .addFilterBefore(
            new InternalSecretFilter(securityProperties, apiKeyVerifier),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  static final class InternalSecretRequestMatcher implements RequestMatcher {

    private final BatchSecurityProperties securityProperties;

    InternalSecretRequestMatcher(BatchSecurityProperties securityProperties) {
      this.securityProperties = securityProperties;
    }

    @Override
    public boolean matches(HttpServletRequest request) {
      return securityProperties.isBypassMode()
          || SecretComparator.constantTimeEquals(
              securityProperties.getInternalSecret(),
              request.getHeader(CommonConstants.INTERNAL_SECRET_HEADER));
    }
  }

  /** 跨已启用日历汇总的原始业务日期补扫积压低基数视图。 */
  static final class EventApiKeyRequestMatcher implements RequestMatcher {

    @Override
    public boolean matches(HttpServletRequest request) {
      return isApiKeyAllowedUri(request)
          && EmptyChecks.isNotBlank(request.getHeader(CommonConstants.BATCH_API_KEY_HEADER));
    }
  }

  /**
   * 内部接口共享密钥校验过滤器，复用 batch-common 的 {@link BatchSecurityProperties}。
   *
   * <p>允许受限的租户 API Key 认证外部 EVENT source；其他路径仍要求平台内部密钥。
   */
  static class InternalSecretFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = CommonConstants.INTERNAL_SECRET_HEADER;
    private static final String UNAUTHORIZED_BODY =
        "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Secret\"}";

    private final BatchSecurityProperties securityProperties;
    private final TriggerApiKeyVerifier apiKeyVerifier;

    InternalSecretFilter(
        BatchSecurityProperties securityProperties, TriggerApiKeyVerifier apiKeyVerifier) {
      this.securityProperties = securityProperties;
      this.apiKeyVerifier = apiKeyVerifier;
    }

    InternalSecretFilter(BatchSecurityProperties securityProperties) {
      this(securityProperties, null);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
      // actuator 健康检查由 docker / k8s 探针调用，不可能携带 X-Internal-Secret；
      // SecurityFilterChain 已对 /actuator/** permitAll，但 OncePerRequestFilter 链
      // 优先于 SecurityFilterChain 执行，必须在此显式放行。
      return request.getRequestURI().startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {

      if (securityProperties.isBypassMode()) {
        setAuthenticated();
        chain.doFilter(request, response);
        return;
      }

      String apiKey = request.getHeader(CommonConstants.BATCH_API_KEY_HEADER);
      if (EmptyChecks.isNotBlank(apiKey)) {
        String tenantId = request.getHeader(CommonConstants.BATCH_TENANT_ID_HEADER);
        boolean apiKeyAuthenticationCanRun =
            EmptyChecks.isNotNull(apiKeyVerifier) && isApiKeyAllowedUri(request);
        if (apiKeyAuthenticationCanRun) {
          Optional<TriggerApiKeyPrincipal> principal =
              apiKeyVerifier.authenticate(apiKey, tenantId);
          if (principal.isPresent()) {
            setExternalEventSourceAuthenticated(principal.orElseThrow());
            chain.doFilter(request, response);
            return;
          }
        }
        writeUnauthorized(response);
        return;
      }

      String header = request.getHeader(HEADER_NAME);
      if (EmptyChecks.isNotNull(header)
          && SecretComparator.constantTimeEquals(securityProperties.getInternalSecret(), header)) {
        setAuthenticated();
        chain.doFilter(request, response);
      } else {
        writeUnauthorized(response);
      }
    }

    private void setAuthenticated() {
      // 必须用非匿名令牌——Spring Security 的 .authenticated() 匹配器通过
      // AuthenticatedAuthorizationManager#isGranted 调用 trustResolver.isAnonymous()，
      // 任何 AnonymousAuthenticationToken 都会被判定未认证而返回 403。
      UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
          "internal", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
      SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void setExternalEventSourceAuthenticated(TriggerApiKeyPrincipal principal) {
      UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
          principal, null, List.of(new SimpleGrantedAuthority("ROLE_EXTERNAL_EVENT_SOURCE")));
      SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static void writeUnauthorized(HttpServletResponse response) throws IOException {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(UNAUTHORIZED_BODY);
    }
  }

  private static boolean isApiKeyAllowedUri(HttpServletRequest request) {
    String path = ServletRequestPaths.applicationPath(request);
    return ("POST".equalsIgnoreCase(request.getMethod()) && "/api/triggers/launch".equals(path))
        || ("GET".equalsIgnoreCase(request.getMethod())
            && "/api/triggers/launch/status".equals(path))
        || ("POST".equalsIgnoreCase(request.getMethod()) && "/api/triggers/events".equals(path))
        || ("GET".equalsIgnoreCase(request.getMethod())
            && "/api/triggers/events/status".equals(path));
  }
}
