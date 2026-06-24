package io.github.pinpols.batch.orchestrator.security;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.InternalAuthFilter;
import io.github.pinpols.batch.orchestrator.security.RequestSignatureVerifier.Result;
import io.github.pinpols.batch.orchestrator.security.RequestSignatureVerifier.SignedRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求签名校验过滤器（方案 A，opt-in，order=2 排在 {@link InternalAuthFilter} 之后）。
 *
 * <p>仅对<b>携带 {@code X-Batch-Api-Key} 的写请求</b>（POST/PUT/PATCH/DELETE）强制校验签名 + ts 窗口 + nonce； 内部
 * secret 调用（可信网络）与读请求不强制。校验不过返 401。开关 {@code batch.request-signing.enabled} 默认关。
 */
@Slf4j
@RequiredArgsConstructor
public class RequestSignatureFilter extends OncePerRequestFilter {

  private static final String HEADER_API_KEY = "X-Batch-Api-Key";
  private static final String HEADER_TIMESTAMP = "X-Batch-Timestamp";
  private static final String HEADER_NONCE = "X-Batch-Nonce";
  private static final String HEADER_SIGNATURE = "X-Batch-Signature";

  private final RequestSigningProperties properties;
  private final RequestSignatureVerifier verifier;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    String apiKey = request.getHeader(HEADER_API_KEY);
    if (!properties.isEnabled() || !isMutating(request.getMethod()) || !Texts.hasText(apiKey)) {
      chain.doFilter(request, response);
      return;
    }

    byte[] body = StreamUtils.copyToByteArray(request.getInputStream());
    Object tenantAttr = request.getAttribute(InternalAuthFilter.ATTR_RESOLVED_TENANT_ID);
    String tenantId = tenantAttr == null ? null : tenantAttr.toString();
    SignedRequest signed =
        new SignedRequest(
            apiKey,
            request.getMethod(),
            request.getRequestURI(),
            body,
            request.getHeader(HEADER_TIMESTAMP),
            request.getHeader(HEADER_NONCE),
            request.getHeader(HEADER_SIGNATURE),
            tenantId);

    Result result = verifier.verify(signed, System.currentTimeMillis());
    if (result != Result.OK) {
      log.warn(
          "request signature rejected: result={} tenant={} method={} uri={}",
          result,
          tenantId,
          request.getMethod(),
          request.getRequestURI());
      writeUnauthorized(response, result);
      return;
    }
    chain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
  }

  private static boolean isMutating(String method) {
    return "POST".equalsIgnoreCase(method)
        || "PUT".equalsIgnoreCase(method)
        || "PATCH".equalsIgnoreCase(method)
        || "DELETE".equalsIgnoreCase(method);
  }

  private static void writeUnauthorized(HttpServletResponse response, Result result)
      throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response
        .getWriter()
        .write("{\"code\":\"SIGNATURE_INVALID\",\"message\":\"" + result.name() + "\"}");
  }
}
