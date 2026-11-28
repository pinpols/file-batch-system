package io.github.pinpols.batch.common.web;

import java.util.Objects;

import io.github.pinpols.batch.common.utils.EmptyValues;

import jakarta.servlet.http.HttpServletRequest;

/** 为 Servlet 过滤器解析相对于应用根路径的路径。 */
public final class ServletRequestPaths {

    private ServletRequestPaths() {}

    public static String applicationPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (EmptyValues.isNotEmpty(servletPath)) {
            return servletPath;
        }

        String requestUri = Objects.toString(request.getRequestURI(), "");
        String contextPath = Objects.toString(request.getContextPath(), "");
        boolean requestUriIncludesContextPath = EmptyValues.isNotEmpty(contextPath)
                && (requestUri.equals(contextPath) || requestUri.startsWith(contextPath + "/"));
        if (requestUriIncludesContextPath) {
            return requestUri.substring(contextPath.length());
        }
        return requestUri;
    }
}
