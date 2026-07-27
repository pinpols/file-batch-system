package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.common.web.BoundedRequestBodyReader;
import io.github.pinpols.batch.common.web.BoundedRequestBodyReader.RequestBodyLimitExceededException;
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
 * console-api 内部端点({@code /internal/**})请求体大小硬上限过滤器。
 *
 * <p>仅作用于 {@code /internal/**} 的 POST / PUT / PATCH / DELETE;先按 {@code Content-Length} 廉价拒绝, 再实际
 * bounded 读取并缓存 body —— 这些端点在 {@code ConsoleSecurityConfiguration} 里 permitAll,{@code @RequestBody}
 * 会在 controller 自校验 bearer token 之前就被 MVC 反序列化,超大体会在鉴权前撑爆内存。
 *
 * <p>与 orchestrator 的 {@code InternalRequestSizeFilter} 同思路(见任务报告 S6 小节)。
 *
 * <ul>
 *   <li>{@code maxBodyBytes <= 0}:不限(放行)。
 *   <li>Content-Length 缺失(chunked transfer):按实际读取字节数拦截。
 *   <li>multipart:放行(文件上传另有 Spring multipart 限制,不在本过滤器管辖)。
 * </ul>
 */
@RequiredArgsConstructor
public class ConsoleInternalRequestSizeFilter extends OncePerRequestFilter {

  private static final String PAYLOAD_TOO_LARGE_BODY =
      "{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"internal request body exceeds the configured"
          + " limit\"}";

  private final ConsoleInternalRequestProperties properties;

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
    try {
      chain.doFilter(
          BoundedRequestBodyReader.readAndCache(request, max, "console-internal"), response);
    } catch (RequestBodyLimitExceededException ex) {
      writePayloadTooLarge(response);
    }
  }

  private static boolean isWriteMethod(HttpServletRequest request) {
    String method = request.getMethod();
    return HttpMethod.POST.matches(method)
        || HttpMethod.PUT.matches(method)
        || HttpMethod.PATCH.matches(method)
        || HttpMethod.DELETE.matches(method);
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
