package com.example.batch.trigger.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Trigger 模块全局异常处理器，统一将业务异常、系统异常、参数校验异常及缺失请求头异常
 * 转换为标准 {@link CommonResponse} 格式并返回对应 HTTP 状态码。
 * 业务异常以 WARN 级别记录，系统异常和未预期异常以 ERROR 级别记录，方便日志分级告警。
 */
@RestControllerAdvice
@Slf4j
public class TriggerApiExceptionHandler {

  @ExceptionHandler(BizException.class)
  public ResponseEntity<CommonResponse<Void>> handleBizException(BizException exception) {
    log.warn("trigger biz exception", exception);
    return ResponseEntity.status(exception.getCode().httpStatus())
        .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(SystemException.class)
  public ResponseEntity<CommonResponse<Void>> handleSystemException(SystemException exception) {
    log.error("trigger system exception", exception);
    return ResponseEntity.status(exception.getCode().httpStatus())
        .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<CommonResponse<Void>> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException exception) {
    log.warn("trigger validation exception", exception);
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
    return ResponseEntity.badRequest()
        .body(
            CommonResponse.failure(
                ResultCode.VALIDATION_ERROR,
                message.isBlank() ? ResultCode.VALIDATION_ERROR.defaultMessage() : message));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<CommonResponse<Void>> handleConstraintViolationException(
      ConstraintViolationException exception) {
    log.warn("trigger constraint violation exception", exception);
    return ResponseEntity.badRequest()
        .body(CommonResponse.failure(ResultCode.VALIDATION_ERROR, exception.getMessage()));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<CommonResponse<Void>> handleMissingHeader(
      MissingRequestHeaderException exception) {
    log.warn("trigger missing request header exception", exception);
    ResultCode code =
        CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())
            ? ResultCode.MISSING_IDEMPOTENCY_KEY
            : ResultCode.INVALID_ARGUMENT;
    return ResponseEntity.badRequest().body(CommonResponse.failure(code, exception.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<CommonResponse<Void>> handleException(Exception exception) {
    log.error("trigger unexpected exception", exception);
    return ResponseEntity.internalServerError()
        .body(
            CommonResponse.failure(
                ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.defaultMessage()));
  }
}
