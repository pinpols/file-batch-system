package com.example.batch.worker.imports.web;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.security.SecretComparator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * import worker 内部接口({@code /internal/**})共享密钥校验过滤器。
 *
 * <p>背景:import worker 暴露 {@code /internal/import/events/object-arrival} 供对象存储事件源在文件落地时
 * 触发即时扫描。该入口只应被可信内部事件源调用——此前无任何鉴权,一旦生产开启 event-arrival 开关,任何能 打到 worker 端口的请求都能触发全量 ingress scan,形成
 * DoS / 扫描放大风险。
 *
 * <p>本 filter 复用平台既有内部密钥体系({@link BatchSecurityProperties#getInternalSecret()},经 {@code
 * X-Internal-Secret} header 携带),与 orchestrator 的 {@code InternalAuthFilter} 同源——但 worker 侧不需要
 * ADR-035 per-tenant API key 通道(那是 orchestrator 面向自托管 worker 的入口),故只保留 secret 通道。
 *
 * <p>{@code batch.security.bypass-mode=true} 时全放行(本地/联调);非 {@code /internal/} 路径直接放行。
 */
@RequiredArgsConstructor
public class ImportInternalAuthFilter extends OncePerRequestFilter {

  private static final String HEADER_SECRET = "X-Internal-Secret";

  private static final String UNAUTHORIZED_BODY =
      "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Secret\"}";

  private final BatchSecurityProperties securityProperties;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String uri = request.getRequestURI();
    if (uri == null || !uri.startsWith("/internal/")) {
      chain.doFilter(request, response);
      return;
    }

    if (securityProperties.isBypassMode()) {
      chain.doFilter(request, response);
      return;
    }

    String header = request.getHeader(HEADER_SECRET);
    if (header != null
        && SecretComparator.constantTimeEquals(securityProperties.getInternalSecret(), header)) {
      chain.doFilter(request, response);
      return;
    }

    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(UNAUTHORIZED_BODY);
  }
}
