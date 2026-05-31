package com.example.batch.orchestrator.config;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.orchestrator.auth.ApiKeyRecord;
import com.example.batch.orchestrator.auth.ApiKeyVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 内部接口共享密钥校验过滤器。
 *
 * <p>注册于 {@code /internal/**}。两类 caller 可通过:
 *
 * <ol>
 *   <li>**主项目可信 worker / orchestrator 内部互调** — 通过 {@code X-Internal-Secret} header(单一共享 secret,
 *       legacy)
 *   <li>**ADR-035 租户自托管 worker** — 通过 {@code X-Batch-Api-Key} + {@code X-Batch-Tenant-Id} 双 header
 *       (per-tenant API key,SHA-256 hash 比对 + tenant 校验)
 * </ol>
 *
 * <p>任一通过即放行;两者皆缺/不匹配返 401。{@code batch.security.bypass-mode=true} 时全放行(本地联调)。
 *
 * <p>API key 校验成功后,把 resolved tenantId 写到 request attribute {@value #ATTR_RESOLVED_TENANT_ID} 让下游
 * controller 用(防租户冒充);secret 校验不写(secret 不绑租户)。
 */
@RequiredArgsConstructor
public class InternalAuthFilter extends OncePerRequestFilter {

  private static final String HEADER_SECRET = "X-Internal-Secret";
  private static final String HEADER_API_KEY = "X-Batch-Api-Key";
  private static final String HEADER_TENANT = "X-Batch-Tenant-Id";

  /** Filter 校验通过后写入,controller 通过 {@code request.getAttribute} 取。 */
  public static final String ATTR_RESOLVED_TENANT_ID = "batch.auth.resolvedTenantId";

  /** Filter 校验通过后写入,值为 {@link ApiKeyRecord};secret 通道写 null。 */
  public static final String ATTR_API_KEY_RECORD = "batch.auth.apiKeyRecord";

  private static final String UNAUTHORIZED_BODY =
      "{\"code\":\"UNAUTHORIZED\",\"message\":\"Missing or invalid credentials"
          + " (X-Internal-Secret or X-Batch-Api-Key+X-Batch-Tenant-Id)\"}";

  private final BatchSecurityProperties securityProperties;

  /** P2 新增 — 可为 null(测试 / 老接线下兼容)。null 时只走 secret 路径。 */
  private final ApiKeyVerifier apiKeyVerifier;

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

    // Path 1: API Key (ADR-035 P2 租户自托管 worker)
    String apiKey = request.getHeader(HEADER_API_KEY);
    String tenantHeader = request.getHeader(HEADER_TENANT);
    if (apiKey != null && !apiKey.isBlank() && apiKeyVerifier != null) {
      // /internal/workers/* 和 /internal/tasks/* 走 worker 操作类 endpoint,强制
      // worker.execute scope(老 key scopes='*' 通配通过,无需轮转)。
      String requiredScope =
          (uri.startsWith("/internal/workers/") || uri.startsWith("/internal/tasks/"))
              ? ApiKeyVerifier.SCOPE_WORKER_EXECUTE
              : null;
      Optional<ApiKeyRecord> rec =
          requiredScope == null
              ? apiKeyVerifier.verify(apiKey, tenantHeader)
              : apiKeyVerifier.verifyWithScope(apiKey, tenantHeader, requiredScope);
      if (rec.isPresent()) {
        request.setAttribute(ATTR_RESOLVED_TENANT_ID, rec.get().tenantId());
        request.setAttribute(ATTR_API_KEY_RECORD, rec.get());
        chain.doFilter(request, response);
        return;
      }
      // API key 提供但校验失败 → 401(不 fallback secret,防 key 泄漏后凭 secret 冒充)
      writeUnauthorized(response);
      return;
    }

    // Path 2: legacy X-Internal-Secret(主项目 worker / orchestrator 内部互调)
    String header = request.getHeader(HEADER_SECRET);
    if (header != null
        && MessageDigest.isEqual(
            securityProperties.getInternalSecret().getBytes(StandardCharsets.UTF_8),
            header.getBytes(StandardCharsets.UTF_8))) {
      chain.doFilter(request, response);
      return;
    }

    writeUnauthorized(response);
  }

  private static void writeUnauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(UNAUTHORIZED_BODY);
  }
}
