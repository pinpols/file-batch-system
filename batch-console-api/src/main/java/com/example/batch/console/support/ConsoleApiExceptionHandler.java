package com.example.batch.console.support;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.utils.JsonUtils;
import jakarta.validation.ConstraintViolationException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;

@RestControllerAdvice
@RequiredArgsConstructor
@Slf4j
public class ConsoleApiExceptionHandler {

    private final ConsoleResponseFactory responseFactory;
    private final BatchSecurityProperties batchSecurityProperties;

    @ExceptionHandler(BizException.class)
    public ResponseEntity<?> handleBizException(BizException exception) {
        log.warn("console biz exception", exception);
        return ResponseEntity.status(exception.getCode().httpStatus())
                .body(responseFactory.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<?> handleSystemException(SystemException exception) {
        log.error("console system exception", exception);
        return ResponseEntity.status(exception.getCode().httpStatus())
                .body(responseFactory.failure(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleMethodArgumentNotValidException(MethodArgumentNotValidException exception) {
        log.warn("console validation exception", exception);
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(responseFactory.failure(ResultCode.VALIDATION_ERROR,
                        message.isBlank() ? CommonErrorMessages.VALIDATION_FAILED : message));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<?> handleConstraintViolationException(ConstraintViolationException exception) {
        log.warn("console constraint violation exception", exception);
        return ResponseEntity.badRequest()
                .body(responseFactory.failure(ResultCode.VALIDATION_ERROR, exception.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<?> handleMissingRequestHeaderException(MissingRequestHeaderException exception) {
        log.warn("console missing request header exception", exception);
        ResultCode code = CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())
                ? ResultCode.MISSING_IDEMPOTENCY_KEY
                : ResultCode.INVALID_ARGUMENT;
        return ResponseEntity.badRequest()
                .body(responseFactory.failure(code,
                        CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())
                                ? CommonErrorMessages.MISSING_IDEMPOTENCY_KEY
                                : CommonErrorMessages.INVALID_ARGUMENT));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        log.warn("console method not supported", exception);
        return ResponseEntity.status(405)
                .body(responseFactory.failure(ResultCode.INVALID_ARGUMENT, exception.getMessage()));
    }

    /**
     * Console 作为 BFF 调用下游（orchestrator/trigger）时，RestClient 会直接抛出异常。
     * 这里尽量把下游返回的 {@link CommonResponse} 语义透传给前端，避免一律降级成 SYSTEM_ERROR。
     */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<?> handleDownstreamRestError(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        log.warn("console downstream rest error: status={}, body={}", exception.getStatusCode().value(), body);
        try {
            CommonResponse<?> downstream = JsonUtils.fromJson(body, CommonResponse.class);
            if (downstream != null && downstream.code() != null) {
                // 以业务 code 为准，HTTP status 使用 code.httpStatus()（更稳定、跨服务一致）
                return ResponseEntity.status(downstream.code().httpStatus())
                        .body(responseFactory.failure(downstream.code(),
                                downstream.message() == null || downstream.message().isBlank()
                                        ? downstream.code().defaultMessage()
                                        : downstream.message()));
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        // 无法解析下游 body 时，至少保留真实 HTTP status（例如 409/404），避免前端只看到 500
        return ResponseEntity.status(exception.getStatusCode())
                .body(responseFactory.failure(ResultCode.SYSTEM_ERROR,
                        body == null || body.isBlank() ? exception.getMessage() : body));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleException(Exception exception) {
        if (batchSecurityProperties.isDemoOpen()) {
            StringWriter sw = new StringWriter();
            exception.printStackTrace(new PrintWriter(sw));
            // 防止堆栈过长影响前端联调展示；截断后仍保留关键顶部异常信息。
            String stack = sw.toString();
            int maxLen = 4000;
            if (stack.length() > maxLen) {
                stack = stack.substring(0, maxLen) + "...(truncated)";
            }
            log.error("console unexpected exception (demo-open)", exception);
            return ResponseEntity.internalServerError()
                    .body(responseFactory.failure(ResultCode.SYSTEM_ERROR, stack));
        }
        log.error("console unexpected exception", exception);
        return ResponseEntity.internalServerError()
                .body(responseFactory.failure(ResultCode.SYSTEM_ERROR, CommonErrorMessages.SYSTEM_ERROR));
    }
}
