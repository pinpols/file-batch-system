package io.github.pinpols.batch.trigger.config;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.security.SecretComparator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Trigger 模块安全配置。
 *
 * <p>所有端点都必须通过 {@code X-Internal-Secret} header 校验。 当 {@code batch.security.bypass-mode=true}
 * 时跳过校验，保持本地联调体验不变。
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class TriggerSecurityConfiguration {

  private final BatchSecurityProperties securityProperties;

  @Bean
  public SecurityFilterChain triggerSecurityFilterChain(HttpSecurity http) throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> auth.requestMatchers("/actuator/**").permitAll().anyRequest().authenticated())
        .addFilterBefore(
            new InternalSecretFilter(securityProperties),
            UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  /**
   * 内部接口共享密钥校验过滤器，复用 batch-common 的 {@link BatchSecurityProperties}。
   *
   * <p>当密钥校验通过（或 bypass-mode 模式）时，设置一个匿名认证令牌以满足 Spring Security 的 {@code authenticated()} 要求。
   */
  @RequiredArgsConstructor
  static class InternalSecretFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "X-Internal-Secret";
    private static final String UNAUTHORIZED_BODY =
        "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Secret\"}";

    private final BatchSecurityProperties securityProperties;

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

      String header = request.getHeader(HEADER_NAME);
      if (header != null
          && SecretComparator.constantTimeEquals(securityProperties.getInternalSecret(), header)) {
        setAuthenticated();
        chain.doFilter(request, response);
      } else {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(UNAUTHORIZED_BODY);
      }
    }

    private void setAuthenticated() {
      // 必须用非匿名令牌——Spring Security 的 .authenticated() 匹配器通过
      // AuthenticatedAuthorizationManager#isGranted 调用 trustResolver.isAnonymous()，
      // 任何 AnonymousAuthenticationToken 都会被判定未认证而返回 403。
      var auth =
          UsernamePasswordAuthenticationToken.authenticated(
              "internal", null, List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
      SecurityContextHolder.getContext().setAuthentication(auth);
    }
  }
}
