package io.github.pinpols.batch.common.resilience;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 在当前 Web 请求上标记实际使用过的下游降级来源。 */
public final class DegradedResponseHeaders {

  public static final String HEADER_NAME = "X-Degraded-Source";

  private DegradedResponseHeaders() {}

  /**
   * 记录一个内部服务名；无 Web 请求或响应已提交时静默跳过。
   *
   * <p>服务名只允许内部固定标识，写入响应前仍做字符收敛，避免把异常文本带入响应头。
   */
  public static void mark(String service) {
    String normalized = normalize(service);
    if (EmptyChecks.isNull(normalized)) {
      return;
    }
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
      return;
    }
    HttpServletResponse response = servletAttributes.getResponse();
    if (response == null || response.isCommitted()) { // empty-check: allow - Sonar 需识别响应为空分支
      return;
    }
    Set<String> sources = new LinkedHashSet<>();
    String current = response.getHeader(HEADER_NAME);
    if (EmptyChecks.isNotBlank(current)) {
      for (String source : current.split(",")) {
        String value = normalize(source);
        if (EmptyChecks.isNotNull(value)) {
          sources.add(value);
        }
      }
    }
    sources.add(normalized);
    response.setHeader(HEADER_NAME, String.join(",", sources));
  }

  private static String normalize(String value) {
    if (EmptyChecks.isBlank(value)) {
      return null;
    }
    String normalized = value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    return EmptyChecks.isBlank(normalized) ? null : normalized;
  }
}
