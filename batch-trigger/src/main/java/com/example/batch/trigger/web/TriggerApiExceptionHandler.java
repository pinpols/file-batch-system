package com.example.batch.trigger.web;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.web.AbstractApiExceptionHandler;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Trigger 模块全局异常处理器：继承 {@link AbstractApiExceptionHandler} 取得 Biz / System / 通用异常的统一处理；额外追加 Spring
 * Web 校验相关异常 （MethodArgumentNotValid / ConstraintViolation / MissingRequestHeader）的映射。
 */
@RestControllerAdvice
public class TriggerApiExceptionHandler extends AbstractApiExceptionHandler {

  @Override
  protected String modulePrefix() {
    return "trigger";
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<CommonResponse<Void>> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException exception) {
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
    return ResponseEntity.badRequest()
        .body(CommonResponse.failure(ResultCode.VALIDATION_ERROR, exception.getMessage()));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<CommonResponse<Void>> handleMissingHeader(
      MissingRequestHeaderException exception) {
    ResultCode code =
        CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())
            ? ResultCode.MISSING_IDEMPOTENCY_KEY
            : ResultCode.INVALID_ARGUMENT;
    // 不再透出 Spring 默认英文 "Required request header 'X' for method parameter type ..."
    // 走 i18n,前端按 Accept-Language 拿中/英文。
    return ResponseEntity.badRequest()
        .body(CommonResponse.failure(code, resolveCommonCode(code, code.label())));
  }
}
