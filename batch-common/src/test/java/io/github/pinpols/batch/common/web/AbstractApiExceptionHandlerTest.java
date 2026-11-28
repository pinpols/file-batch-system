package io.github.pinpols.batch.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;

@DisplayName("通用异常处理:数据库资源故障返回可重试语义")
class AbstractApiExceptionHandlerTest {

    private final TestHandler handler = new TestHandler();

    @Test
    @DisplayName("连接池耗尽映射为 503 + Retry-After,而不是 500")
    void shouldMapConnectionPoolExhaustionToRetryableServiceUnavailable() {
        CannotGetJdbcConnectionException exception = new CannotGetJdbcConnectionException(
                "Failed to obtain JDBC Connection", new SQLException("connection is not available"));

        ResponseEntity<CommonResponse<Void>> response = handler.handleDataAccessResourceFailure(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ResultCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("未预期异常仍按 500 处理,不被数据库资源映射吞掉")
    void shouldKeepUnexpectedExceptionAsServerError() {
        ResponseEntity<CommonResponse<Void>> response = handler.handleException(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ResultCode.SYSTEM_ERROR);
    }

    private static final class TestHandler extends AbstractApiExceptionHandler {

        private TestHandler() {
            super(null);
        }

        @Override
        protected String modulePrefix() {
            return "test";
        }
    }
}
