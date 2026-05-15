package com.example.batch.orchestrator.config;

import com.example.batch.common.config.BatchSecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部接口共享密钥校验过滤器。
 *
 * <p>注册于 {@code /internal/**}，校验客户端通过 {@code X-Internal-Secret} header 携带的密钥。 当 {@code
 * batch.security.bypass-mode=true} 时跳过校验，保持本地联调体验不变。
 */
@RequiredArgsConstructor
public class InternalAuthFilter extends OncePerRequestFilter {

  private static final String HEADER_NAME = "X-Internal-Secret";
  private static final String UNAUTHORIZED_BODY =
      "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid X-Internal-Secret\"}";

  private final BatchSecurityProperties securityProperties;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    // R4-P0-1：双层防御 — 即使 ServletContext URL pattern 解释不一致（例如某些容器在 `/internal/*`
    // 与 `/internal/**` 的处理上有差异），过滤器自己再做一次前缀检查。所有 /internal/ 开头路径必须带 secret，
    // 包括深层 /internal/orchestrator/dry-run/plan 这类多段路径。
    String uri = request.getRequestURI();
    if (uri == null || !uri.startsWith("/internal/")) {
      // 不应到这里（URL pattern 已限制），防御性放行
      chain.doFilter(request, response);
      return;
    }

    if (securityProperties.isBypassMode()) {
      chain.doFilter(request, response);
      return;
    }

    String header = request.getHeader(HEADER_NAME);
    if (header != null
        && MessageDigest.isEqual(
            securityProperties.getInternalSecret().getBytes(StandardCharsets.UTF_8),
            header.getBytes(StandardCharsets.UTF_8))) {
      chain.doFilter(request, response);
    } else {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      response.getWriter().write(UNAUTHORIZED_BODY);
    }
  }
}
