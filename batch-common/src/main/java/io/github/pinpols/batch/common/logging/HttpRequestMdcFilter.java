package io.github.pinpols.batch.common.logging;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.utils.CorrelationIds;
import io.github.pinpols.batch.common.utils.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/** 从标准 HTTP 请求头填充 MDC，适用于基于 Servlet 的服务（orchestrator、trigger、worker HTTP）。 */
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class HttpRequestMdcFilter extends OncePerRequestFilter {

  @Value("${spring.application.name:batch}")
  private String applicationName;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = CorrelationIds.normalize(
        request.getHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER),
        IdGenerator.newBusinessNo("req"));
    String traceId = CorrelationIds.normalize(
        request.getHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER), IdGenerator.newTraceId());
    String requestedTenantId = request.getHeader(CommonConstants.DEFAULT_TENANT_ID_HEADER);
    Map<String, String> previousContext = BatchMdc.snapshot();
    try {
      BatchMdc.put(StructuredLogField.SERVICE, applicationName);
      BatchMdc.put(StructuredLogField.REQUEST_ID, requestId);
      BatchMdc.put(StructuredLogField.TRACE_ID, traceId);
      // 通用 filter 执行时认证尚未完成，不能把请求头当成可信租户。
      BatchMdc.put(StructuredLogField.REQUESTED_TENANT_ID, requestedTenantId);
      response.setHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId);
      response.setHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId);
      filterChain.doFilter(request, response);
    } finally {
      BatchMdc.restore(previousContext);
    }
  }
}
