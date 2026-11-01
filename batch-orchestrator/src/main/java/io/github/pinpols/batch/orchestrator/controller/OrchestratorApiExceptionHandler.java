package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.i18n.BizMessageResolver;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.web.AbstractApiExceptionHandler;
import io.github.pinpols.batch.orchestrator.application.error.OperationFailure;
import io.github.pinpols.batch.orchestrator.application.service.governance.DeadLetterOrphanSourceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = LaunchController.class)
@Slf4j
public class OrchestratorApiExceptionHandler extends AbstractApiExceptionHandler {

  public OrchestratorApiExceptionHandler(BizMessageResolver bizMessageResolver) {
    super(bizMessageResolver);
  }

  /** standalone MockMvc 测试用 — 无 Spring 容器,resolver=null 走父类降级路径返回 raw message。 */
  public static OrchestratorApiExceptionHandler forStandaloneTest() {
    return new OrchestratorApiExceptionHandler(null);
  }

  @Override
  protected String modulePrefix() {
    return "orchestrator";
  }

  @Override
  protected boolean logBizExceptionAtInfo(BizException exception) {
    return "error.task.claim_not_available".equals(exception.getMessageKey());
  }

  /** 死信指向的分区 / 作业实例已不存在（常见于历史数据清理）。 */
  @ExceptionHandler(DeadLetterOrphanSourceException.class)
  public ResponseEntity<CommonResponse<Void>> handleDeadLetterOrphanSource(
      DeadLetterOrphanSourceException exception) {
    String message = resolveCommonCode(ResultCode.CONFLICT, ResultCode.CONFLICT.label());
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.CONFLICT, message);
    return ResponseEntity.status(HttpStatus.CONFLICT).body(failureBody);
  }

  /**
   * 请求体反序列化失败（如 enum 非法值 {@code DryRunLevel="BOGUS_LEVEL"}、JSON 格式错）→ 400 INVALID_ARGUMENT， 而非落到
   * {@code Exception} 回退的 500 系统错误。对标 console 侧同款处理,保证非法入参对外是 4xx。
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<CommonResponse<Void>> handleMessageNotReadable(
      HttpMessageNotReadableException exception) {
    ResponseEntity.BodyBuilder response = ResponseEntity.badRequest();
    String message =
        resolveCommonCode(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.label());
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.INVALID_ARGUMENT, message);
    return response.body(failureBody);
  }

  /**
   * 瞬时 DB 并发失败(死锁 / 锁获取超时,如并发 task-outcome 推进同一 job 多分区时的行锁竞争)→ 503 可重试, 而非落到 {@code Exception} 回退的
   * 500「unexpected exception」(语义=服务端 bug + 刷 ERROR 噪声)。 report / launch 等入口幂等(idempotency key + 状态机
   * CAS),调用方(worker SDK §C)按 5xx 退避重投即收敛。 {@link TransientDataAccessException}
   * 覆盖死锁、锁获取失败、查询超时等一族瞬时错。
   */
  @ExceptionHandler(TransientDataAccessException.class)
  public ResponseEntity<CommonResponse<Void>> handleTransientDataAccess(
      TransientDataAccessException exception) {
    log.warn(
        "transient DB concurrency failure (retryable, mapped to 503): {}",
        SwallowedExceptionLogger.summary(exception));
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE);
    String message =
        resolveCommonCode(ResultCode.SERVICE_UNAVAILABLE, ResultCode.SERVICE_UNAVAILABLE.label());
    CommonResponse<Void> failureBody =
        CommonResponse.failure(ResultCode.SERVICE_UNAVAILABLE, message);
    return response.body(failureBody);
  }

  /** 用例失败按既有内部 HTTP 契约转换。 */
  @ExceptionHandler(OperationFailure.class)
  public ResponseEntity<CommonResponse<Void>> handleOperationFailure(OperationFailure exception) {
    HttpStatus status = switch (exception.kind()) {
      case INVALID_INPUT -> HttpStatus.BAD_REQUEST;
      case NOT_FOUND -> HttpStatus.NOT_FOUND;
      case CONFLICT -> HttpStatus.CONFLICT;
      case INVALID_CONFIGURATION -> HttpStatus.UNPROCESSABLE_CONTENT;
      case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
      case TEMPORARILY_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
    };
    ResultCode code = switch (exception.kind()) {
      case INVALID_INPUT -> ResultCode.INVALID_ARGUMENT;
      case NOT_FOUND -> ResultCode.NOT_FOUND;
      case CONFLICT -> ResultCode.CONFLICT;
      case RATE_LIMITED -> ResultCode.RATE_LIMITED;
      case INVALID_CONFIGURATION, TEMPORARILY_UNAVAILABLE -> ResultCode.SYSTEM_ERROR;
    };
    String message = resolveCommonCode(code, code.label());
    return ResponseEntity.status(status).body(CommonResponse.failure(code, message));
  }

  /** orchestrator 特有：把 Spring 的 ResponseStatusException 映射回 CommonResponse。 */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<CommonResponse<Void>> handleResponseStatus(
      ResponseStatusException exception) {
    ResultCode code =
        switch (exception.getStatusCode().value()) {
          case 400 -> ResultCode.INVALID_ARGUMENT;
          case 401 -> ResultCode.UNAUTHORIZED;
          case 403 -> ResultCode.FORBIDDEN;
          case 404 -> ResultCode.NOT_FOUND;
          case 409 -> ResultCode.CONFLICT;
          case 429 -> ResultCode.RATE_LIMITED;
          default -> ResultCode.SYSTEM_ERROR;
        };
    String message = resolveCommonCode(code, code.label());
    ResponseEntity.BodyBuilder response = ResponseEntity.status(exception.getStatusCode());
    CommonResponse<Void> failureBody = CommonResponse.failure(code, message);
    return response.body(failureBody);
  }
}
