package io.github.pinpols.batch.console.domain.rbac.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.ResponseMeta;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.CorrelationIds;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.EncodingUtils;
import io.github.pinpols.batch.common.utils.IdGenerator;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConsoleSecurityResponseWriter {

  private final ObjectMapper objectMapper;

  public void write(
      HttpServletResponse response, HttpStatus status, ResultCode code, String message)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    response.setStatus(status.value());
    response.setCharacterEncoding(EncodingUtils.UTF_8);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    String requestId = resolveCorrelationId(
        response,
        CommonConstants.DEFAULT_REQUEST_ID_HEADER,
        StructuredLogField.REQUEST_ID,
        IdGenerator.newBusinessNo("req"));
    String traceId = resolveCorrelationId(
        response,
        CommonConstants.DEFAULT_TRACE_ID_HEADER,
        StructuredLogField.TRACE_ID,
        IdGenerator.newTraceId());
    response.setHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestId);
    response.setHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER, traceId);
    ResponseMeta meta = new ResponseMeta(requestId, traceId, BatchDateTimeSupport.utcNow());
    objectMapper.writeValue(response.getWriter(), CommonResponse.failure(code, message, meta));
  }

  private static String resolveCorrelationId(
      HttpServletResponse response, String headerName, String mdcKey, String fallback) {
    String responseValue = CorrelationIds.normalize(response.getHeader(headerName));
    if (EmptyChecks.isNotNull(responseValue)) {
      return responseValue;
    }
    return CorrelationIds.normalize(BatchMdc.get(mdcKey), fallback);
  }
}
