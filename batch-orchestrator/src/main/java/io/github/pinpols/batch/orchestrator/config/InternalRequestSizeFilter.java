package io.github.pinpols.batch.orchestrator.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 缺口③：内部端点（{@code /internal/**}）请求体大小硬上限过滤器。
 *
 * <p>仅作用于 {@code /internal/**} 的 POST / PUT；按 {@code Content-Length} 超过 {@link
 * InternalRequestProperties#getMaxBodyBytes()} 即返回 413，不进 controller，防超大 report / outputs 撑爆内存。
 *
 * <ul>
 *   <li>{@code maxBodyBytes <= 0}：不限（放行）。
 *   <li>Content-Length 缺失（chunked transfer）：放行（无法预判大小，简单起见不拦）。
 *   <li>multipart：放行（文件上传走 Spring multipart 60MB 限制，不在本过滤器管辖）。
 * </ul>
 *
 * <p>排在 {@link InternalAuthFilter}（order=1）之前（order=0），让超大体在鉴权前就被廉价拦掉。
 */
@RequiredArgsConstructor
public class InternalRequestSizeFilter extends OncePerRequestFilter {

  private static final String PAYLOAD_TOO_LARGE_BODY =
      "{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"internal request body exceeds the configured"
          + " limit\"}";

  private final InternalRequestProperties properties;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    long max = properties.getMaxBodyBytes();
    String uri = request.getRequestURI();
    if (max <= 0 || uri == null || !uri.startsWith("/internal/") || !isWriteMethod(request)) {
      chain.doFilter(request, response);
      return;
    }
    if (isMultipart(request)) {
      chain.doFilter(request, response);
      return;
    }
    long contentLength = request.getContentLengthLong();
    if (contentLength > max) {
      writePayloadTooLarge(response);
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean isWriteMethod(HttpServletRequest request) {
    String method = request.getMethod();
    return HttpMethod.POST.matches(method) || HttpMethod.PUT.matches(method);
  }

  private static boolean isMultipart(HttpServletRequest request) {
    String contentType = request.getContentType();
    return contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
  }

  private static void writePayloadTooLarge(HttpServletResponse response) throws IOException {
    response.setStatus(HttpStatus.CONTENT_TOO_LARGE.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader(HttpHeaders.CONNECTION, "close");
    response.getWriter().write(PAYLOAD_TOO_LARGE_BODY);
  }
}
