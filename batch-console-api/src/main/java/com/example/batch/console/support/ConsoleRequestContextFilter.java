package com.example.batch.console.support;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.logging.BatchMdc;
import com.example.batch.common.logging.StructuredLogField;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.console.support.auth.ConsolePrincipal;
import com.example.batch.console.support.auth.ConsoleRoles;
import com.example.batch.console.support.auth.ConsoleSecurityResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class ConsoleRequestContextFilter extends OncePerRequestFilter {

  public static final String REQUEST_METADATA_ATTRIBUTE = "consoleRequestMetadata";

  @Value("${spring.application.name:batch-console-api}")
  private String applicationName;

  private final ConsoleSecurityResponseWriter responseWriter;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId =
        firstNonBlank(
            request.getHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER),
            IdGenerator.newBusinessNo("req"));
    String traceId =
        firstNonBlank(
            request.getHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER), IdGenerator.newTraceId());
    String operatorId = request.getHeader(CommonConstants.DEFAULT_OPERATOR_ID_HEADER);
    String idempotencyKey = request.getHeader(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER);
    String clientIp =
        firstNonBlank(
            request.getHeader(CommonConstants.DEFAULT_FORWARDED_FOR_HEADER),
            request.getRemoteAddr());
    String tenantId =
        resolveTenantId(response, request.getHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER));
    if (tenantId == null && response.isCommitted()) {
      return;
    }

    ConsoleRequestMetadata metadata =
        new ConsoleRequestMetadata(
            requestId, traceId, tenantId, operatorId, idempotencyKey, clientIp);
    request.setAttribute(REQUEST_METADATA_ATTRIBUTE, metadata);
    response.setHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId);
    response.setHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId);
    try {
      BatchMdc.put(StructuredLogField.SERVICE, applicationName);
      BatchMdc.put(StructuredLogField.REQUEST_ID, requestId);
      BatchMdc.put(StructuredLogField.TRACE_ID, traceId);
      if (tenantId != null && !tenantId.isBlank()) {
        BatchMdc.put(StructuredLogField.TENANT_ID, tenantId);
      }
      filterChain.doFilter(request, response);
    } finally {
      BatchMdc.clear();
    }
  }

  private String resolveTenantId(HttpServletResponse response, String requestedTenantId) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null
        && authentication.getPrincipal() instanceof ConsolePrincipal principal) {
      // 全局角色（ADMIN / AUDITOR / CONFIG_ADMIN）允许通过请求头指定目标租户
      if (ConsoleRoles.hasGlobalRole(principal.authorities())) {
        return (requestedTenantId != null && !requestedTenantId.isBlank())
            ? requestedTenantId
            : principal.tenantId();
      }
      // 租户角色：严格匹配
      if (requestedTenantId != null
          && !requestedTenantId.isBlank()
          && !requestedTenantId.equals(principal.tenantId())) {
        try {
          responseWriter.write(
              response,
              HttpStatus.FORBIDDEN,
              ResultCode.FORBIDDEN,
              CommonErrorMessages.TENANT_MISMATCH);
        } catch (IOException exception) {
          throw new IllegalStateException("failed to write tenant mismatch response", exception);
        }
        return null;
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
