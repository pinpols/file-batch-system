package io.github.pinpols.batch.common.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;

/** Resolves an application-relative path for servlet filters. */
public final class ServletRequestPaths {

  private ServletRequestPaths() {}

  public static String applicationPath(HttpServletRequest request) {
    String servletPath = request.getServletPath();
    if (servletPath != null && !servletPath.isEmpty()) {
      return servletPath;
    }

    String requestUri = Objects.toString(request.getRequestURI(), "");
    String contextPath = Objects.toString(request.getContextPath(), "");
    if (!contextPath.isEmpty()
        && (requestUri.equals(contextPath) || requestUri.startsWith(contextPath + "/"))) {
      return requestUri.substring(contextPath.length());
    }
    return requestUri;
  }
}
