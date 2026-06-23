package io.github.pinpols.batch.console.domain.rbac.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.utils.EncodingUtils;
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
    objectMapper.writeValue(response.getWriter(), CommonResponse.failure(code, message));
  }
}
