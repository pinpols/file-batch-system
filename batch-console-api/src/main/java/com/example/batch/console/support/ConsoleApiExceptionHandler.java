package com.example.batch.console.support;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.constants.CommonErrorMessages;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Console API 统一异常映射：把 Spring / Jakarta Validation / 业务异常翻译为 {@code CommonResponse} 并挑 HTTP status。
 *
 * <p>按语义归为 4 组分支：
 *
 * <ul>
 *   <li><b>业务/系统自定义异常</b>（{@code BizException} / {@code SystemException}）：直接用
 *       {@code ResultCode.httpStatus()} 定 HTTP status，WARN/ERROR 分级 log。
 *   <li><b>请求格式/参数错误</b>（{@code MethodArgumentNotValidException} / {@code ConstraintViolationException}
 *       / {@code MissingRequestHeaderException} / {@code MissingServletRequestParameterException}
 *       / {@code HttpMessageNotReadableException} / {@code HttpRequestMethodNotSupportedException}
 *       / {@code NoResourceFoundException}）：映射到 400 / 404 / 405 + {@code VALIDATION_ERROR / INVALID_ARGUMENT}；
 *       idempotency-key header 缺失特判为 {@code MISSING_IDEMPOTENCY_KEY}。
 *   <li><b>权限拒绝</b>（{@code AuthorizationDeniedException} / {@code AccessDeniedException}）：
 *       统一 403 + {@code FORBIDDEN}，避免暴露内部授权逻辑细节。
 *   <li><b>下游调用异常</b>（{@link #handleDownstreamRestError}）：console 作为 BFF 调 orchestrator / trigger 时，
 *       优先解析下游 {@code CommonResponse} body 透传其 code + message，解析失败至少保留真实 HTTP status
 *       ——防止下游 409 / 404 被一律降级为 500 误导前端。
 * </ul>
 *
 * <p>兜底 {@link #handleException} 捕获未显式处理的 {@code Exception}，返回 500 + {@code SYSTEM_ERROR}
 * 并 ERROR log 带堆栈，便于线上诊断。
 */
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
  public ResponseEntity<?> handleMethodArgumentNotValidException(
      MethodArgumentNotValidException exception) {
    log.warn("console validation exception", exception);
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining("; "));
    return ResponseEntity.badRequest()
        .body(
            responseFactory.failure(
                ResultCode.VALIDATION_ERROR,
                message.isBlank() ? CommonErrorMessages.VALIDATION_FAILED : message));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<?> handleConstraintViolationException(
      ConstraintViolationException exception) {
    log.warn("console constraint violation exception", exception);
    return ResponseEntity.badRequest()
        .body(responseFactory.failure(ResultCode.VALIDATION_ERROR, exception.getMessage()));
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<?> handleMissingRequestHeaderException(
      MissingRequestHeaderException exception) {
    log.warn("console missing request header exception", exception);
    ResultCode code =
        CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(exception.getHeaderName())
            ? ResultCode.MISSING_IDEMPOTENCY_KEY
            : ResultCode.INVALID_ARGUMENT;
    return ResponseEntity.badRequest()
        .body(
            responseFactory.failure(
                code,
                CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER.equalsIgnoreCase(
                        exception.getHeaderName())
                    ? CommonErrorMessages.MISSING_IDEMPOTENCY_KEY
                    : CommonErrorMessages.INVALID_ARGUMENT));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<?> handleMethodNotSupported(
      HttpRequestMethodNotSupportedException exception) {
    log.warn("console method not supported", exception);
    return ResponseEntity.status(405)
        .body(responseFactory.failure(ResultCode.INVALID_ARGUMENT, exception.getMessage()));
  }

  @ExceptionHandler({AuthorizationDeniedException.class, AccessDeniedException.class})
  public ResponseEntity<?> handleAccessDenied(Exception exception) {
    log.warn("console access denied", exception);
    return ResponseEntity.status(ResultCode.FORBIDDEN.httpStatus())
        .body(responseFactory.failure(ResultCode.FORBIDDEN, CommonErrorMessages.ACCESS_DENIED));
  }

  /**
   * Console 作为 BFF 调用下游（orchestrator/trigger）时，RestClient 会直接抛出异常。 这里尽量把下游返回的 {@link
   * CommonResponse} 语义透传给前端，避免一律降级成 SYSTEM_ERROR。
   */
  @ExceptionHandler(RestClientResponseException.class)
  public ResponseEntity<?> handleDownstreamRestError(RestClientResponseException exception) {
    String body = exception.getResponseBodyAsString();
    log.warn(
        "console downstream rest error: status={}, body={}",
        exception.getStatusCode().value(),
        body);
    try {
      CommonResponse<?> downstream = JsonUtils.fromJson(body, CommonResponse.class);
      if (downstream != null && downstream.code() != null) {
        // 以业务 code 为准，HTTP status 使用 code.httpStatus()（更稳定、跨服务一致）
        return ResponseEntity.status(downstream.code().httpStatus())
            .body(
                responseFactory.failure(
                    downstream.code(),
                    downstream.message() == null || downstream.message().isBlank()
                        ? downstream.code().defaultMessage()
                        : downstream.message()));
      }
    } catch (RuntimeException ignored) {
      // 继续执行
    }
    // 无法解析下游 body 时，至少保留真实 HTTP status（例如 409/404），避免前端只看到 500
    return ResponseEntity.status(exception.getStatusCode())
        .body(
            responseFactory.failure(
                ResultCode.SYSTEM_ERROR,
                body == null || body.isBlank() ? exception.getMessage() : body));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<?> handleNoResourceFound(NoResourceFoundException exception) {
    log.warn("console resource not found: {}", exception.getMessage());
    return ResponseEntity.status(404)
        .body(responseFactory.failure(ResultCode.NOT_FOUND, exception.getMessage()));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<?> handleMissingParam(MissingServletRequestParameterException exception) {
    log.warn("console missing request param: {}", exception.getMessage());
    return ResponseEntity.badRequest()
        .body(responseFactory.failure(ResultCode.INVALID_ARGUMENT, exception.getMessage()));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<?> handleMessageNotReadable(HttpMessageNotReadableException exception) {
    log.warn("console message not readable: {}", exception.getMessage());
    return ResponseEntity.badRequest()
        .body(responseFactory.failure(ResultCode.INVALID_ARGUMENT, exception.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<?> handleException(Exception exception) {
    log.error("console unexpected exception", exception);
    return ResponseEntity.internalServerError()
        .body(responseFactory.failure(ResultCode.SYSTEM_ERROR, CommonErrorMessages.SYSTEM_ERROR));
  }
}
