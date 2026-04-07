package com.example.batch.common.logging;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.utils.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从标准 HTTP 请求头填充 MDC，适用于基于 Servlet 的服务（orchestrator、trigger、worker HTTP）。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class HttpRequestMdcFilter extends OncePerRequestFilter {

    @Value("${spring.application.name:batch}")
    private String applicationName;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = firstNonBlank(request.getHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER), IdGenerator.newBusinessNo("req"));
        String traceId = firstNonBlank(request.getHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER), IdGenerator.newTraceId());
        String tenantId = request.getHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER);
        try {
            BatchMdc.put(StructuredLogField.SERVICE, applicationName);
            BatchMdc.put(StructuredLogField.REQUEST_ID, requestId);
            BatchMdc.put(StructuredLogField.TRACE_ID, traceId);
            BatchMdc.put(StructuredLogField.TENANT_ID, tenantId);
            response.setHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId);
            response.setHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId);
            filterChain.doFilter(request, response);
        } finally {
            BatchMdc.clear();
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
