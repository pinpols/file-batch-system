package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("安全响应写入:链路标识头部与响应体元信息保持一致")
class ConsoleSecurityResponseWriterTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  private final ConsoleSecurityResponseWriter writer =
      new ConsoleSecurityResponseWriter(objectMapper);

  @AfterEach
  void tearDown() {
    BatchMdc.clear();
  }

  @Test
  @DisplayName("响应写入:请求与追踪标识同时出现在头部与元信息, 时间戳非空")
  void shouldExposeCorrelationHeadersAndResponseMeta() throws Exception {
    BatchMdc.put(StructuredLogField.REQUEST_ID, "req-security-1");
    BatchMdc.put(StructuredLogField.TRACE_ID, "trace-security-1");
    MockHttpServletResponse response = new MockHttpServletResponse();

    writer.write(response, HttpStatus.UNAUTHORIZED, ResultCode.UNAUTHORIZED, "未认证");

    assertThat(response.getHeader(CommonConstants.DEFAULT_REQUEST_ID_HEADER))
        .isEqualTo("req-security-1");
    assertThat(response.getHeader(CommonConstants.DEFAULT_TRACE_ID_HEADER))
        .isEqualTo("trace-security-1");
    JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
    assertThat(body.path("meta").path("requestId").asText()).isEqualTo("req-security-1");
    assertThat(body.path("meta").path("traceId").asText()).isEqualTo("trace-security-1");
    assertThat(body.path("meta").path("timestamp").asText()).isNotBlank();
  }
}
