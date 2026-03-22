package com.example.batch.console.support;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ConsoleRequestContextFilter extends OncePerRequestFilter {

    public static final String REQUEST_METADATA_ATTRIBUTE = "consoleRequestMetadata";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String requestId = firstNonBlank(request.getHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER), IdGenerator.newBusinessNo("req"));
        String traceId = firstNonBlank(request.getHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER), IdGenerator.newTraceId());
        String operatorId = request.getHeader(CommonConstants.DEFAULT_OPERATOR_ID_HEADER);
        String idempotencyKey = request.getHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER);
        String clientIp = firstNonBlank(request.getHeader(CommonConstants.DEFAULT_FORWARDED_FOR_HEADER), request.getRemoteAddr());
        String tenantId = resolveTenantId(request.getHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER));

        ConsoleRequestMetadata metadata = new ConsoleRequestMetadata(
                requestId,
                traceId,
                tenantId,
                operatorId,
                idempotencyKey,
                clientIp
        );
        request.setAttribute(REQUEST_METADATA_ATTRIBUTE, metadata);
        response.setHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId);
        response.setHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId);
        filterChain.doFilter(request, response);
    }

    private String resolveTenantId(String requestedTenantId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
            if (requestedTenantId != null && !requestedTenantId.isBlank() && !requestedTenantId.equals(principal.tenantId())) {
                throw new BizException(ResultCode.FORBIDDEN, "tenant mismatch");
            }
            return principal.tenantId();
        }
        return requestedTenantId;
    }

    private String firstNonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
