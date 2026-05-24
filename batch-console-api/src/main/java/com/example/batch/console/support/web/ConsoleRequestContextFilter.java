package com.example.batch.console.support.web;

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
    // P1(2026-05-23 audit):与 ConsoleJwtService.hashClientIp 统一身份绑定来源,只取 RemoteAddr,
    // 不信任 X-Forwarded-For — 之前 XFF fallback 会让伪造 XFF 的请求与 JWT 绑定的 ipHash 不一致,
    // 同时让审计 ipHash 可被攻击者主动选定。
    // XFF 头改作 audit log 单独字段(MDC.xffHeaderValue)观察记录,不参与身份判断;
    // 真要走 trusted-proxy XFF,需在更前置的 ForwardedHeaderFilter 上统一处理。
    String clientIp = request.getRemoteAddr();
    String xffHeaderValue = request.getHeader(CommonConstants.DEFAULT_FORWARDED_FOR_HEADER);
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
      // AuditFieldsInterceptor 拦截 insert/update 时读 MDC operatorId 自动填 created_by/updated_by
      if (operatorId != null && !operatorId.isBlank()) {
        BatchMdc.put(StructuredLogField.OPERATOR_ID, operatorId);
      }
      // P1(2026-05-23 audit):XFF 仅写入 audit 日志,不参与身份绑定;按 trusted-proxy 实际部署可观察伪造。
      if (xffHeaderValue != null && !xffHeaderValue.isBlank()) {
        BatchMdc.put("xffHeaderValue", xffHeaderValue);
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
