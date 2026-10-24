package io.github.pinpols.batch.common.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.exception.SystemException;
import io.github.pinpols.batch.common.i18n.BizMessageResolver;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 通用 API 异常处理基类：把 BizException / SystemException / 未预期异常的处理集中到一处， 子类只在 {@link #modulePrefix()}
 * 里提供日志前缀即可，避免 Trigger / Orchestrator / Console 三个模块各写一份 99% 相同的 handler。
 *
 * <p>子类仍可通过 {@code @ExceptionHandler} 追加模块特有异常（如 Orchestrator 的 {@code
 * ResponseStatusException}、Trigger 的 {@code MissingRequestHeaderException}）。
 *
 * <p>i18n:业务异常与通用错误码文案统一过 {@link BizMessageResolver},按 Accept-Language 翻译；系统异常的内部 message 仅进入
 * 日志，不作为 API 响应返回。
 */
public abstract class AbstractApiExceptionHandler {

  private final Logger log = LoggerFactory.getLogger(getClass());

  private final BizMessageResolver bizMessageResolver;

  // docs/agent-baseline.md §Java #3:构造器注入(子类 super 调用一致传入)。
  protected AbstractApiExceptionHandler(BizMessageResolver bizMessageResolver) {
    this.bizMessageResolver = bizMessageResolver;
  }

  // standalone MockMvc 测试场景(无 Spring 容器),提供 no-arg 回退:
  // bizMessageResolver=null 时,resolveBizMessage / resolveCommonCode 已有 null 短路降级。
  protected AbstractApiExceptionHandler() {
    this(null);
  }

  /** 日志前缀，用于区分模块来源（"trigger" / "orchestrator" / "console"）。 */
  protected abstract String modulePrefix();

  /** 模块可将明确预期的业务拒绝降为 INFO；默认仍记录 WARN。 */
  protected boolean logBizExceptionAtInfo(BizException exception) {
    return false;
  }

  @ExceptionHandler(BizException.class)
  public ResponseEntity<CommonResponse<Void>> handleBizException(BizException exception) {
    if (logBizExceptionAtInfo(exception)) {
      log.info(
          "{} biz request not accepted: code={}, key={}, message={}",
          modulePrefix(),
          exception.getCode().code(),
          exception.getMessageKey(),
          SwallowedExceptionLogger.summary(exception));
    } else if (exception.getCode().httpStatus() >= 500) {
      log.warn("{} biz exception", modulePrefix(), exception);
    } else {
      log.warn(
          "{} biz exception: code={}, key={}, message={}",
          modulePrefix(),
          exception.getCode().code(),
          exception.getMessageKey(),
          SwallowedExceptionLogger.summary(exception));
    }
    ResultCode responseCode = exception.getCode();
    int responseStatus = responseCode.httpStatus();
    ResponseEntity.BodyBuilder response = ResponseEntity.status(responseStatus);
    ResultCode code = exception.getCode();
    String message = resolveBizMessage(exception);
    CommonResponse<Void> failureBody = CommonResponse.failure(code, message);
    return response.body(failureBody);
  }

  @ExceptionHandler(SystemException.class)
  public ResponseEntity<CommonResponse<Void>> handleSystemException(SystemException exception) {
    log.error("{} system exception", modulePrefix(), exception);
    ResultCode code = exception.getCode();
    int status = code.httpStatus();
    String message = resolveCommonCode(code, code.label());
    CommonResponse<Void> failureBody = CommonResponse.failure(code, message);
    return ResponseEntity.status(status).body(failureBody);
  }

  /**
   * 乐观锁冲突：两个线程同时改同一行记录（@Version CAS 失败）。属可预期并发， 返回 409 CONFLICT + WARN（不是 ERROR）；上游可按 "同样 tick 重试"
   * 处理。
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<CommonResponse<Void>> handleOptimisticLock(
      OptimisticLockingFailureException exception) {
    log.warn(
        "{} optimistic lock conflict (concurrent modification; upstream may retry): {}",
        modulePrefix(),
        SwallowedExceptionLogger.summary(exception));
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CONFLICT);
    String message = resolveCommonCode(ResultCode.CONFLICT, "并发修改冲突,请重试");
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.CONFLICT, message);
    return response.body(failureBody);
  }

  /**
   * 唯一键冲突：create-if-absent 并发撞键。属可预期并发，返回 409 CONFLICT + WARN， 上游应该用"重新读取→改走 update 分支"的方式恢复，不是当成
   * 500 重试。
   */
  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<CommonResponse<Void>> handleDuplicateKey(DuplicateKeyException exception) {
    log.warn(
        "{} duplicate key conflict (concurrent insert; upstream may retry as update): {}",
        modulePrefix(),
        SwallowedExceptionLogger.summary(exception));
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CONFLICT);
    String message = resolveCommonCode(ResultCode.CONFLICT, "并发插入冲突,请重试");
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.CONFLICT, message);
    return response.body(failureBody);
  }

  /**
   * 404 NoResourceFoundException 显式按 404 + INFO 处理,不走 Exception.class 回退的 500 + ERROR 路径。否则任何请求不存在
   * URL(浏览器探测 favicon.ico / 误填 actuator path)都会被记成 ERROR 产生日志噪音,且返回 500 误导调用方。
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<CommonResponse<Void>> handleNoResourceFound(
      NoResourceFoundException exception) {
    log.info("{} resource not found: {}", modulePrefix(), exception.getResourcePath());
    ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.NOT_FOUND);
    String message = resolveCommonCode(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.label());
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.NOT_FOUND, message);
    return response.body(failureBody);
  }

  /**
   * 400 path variable / 查询参数类型转换失败(如 {@code /internal/x/{id}} 的 id 传了非数字)。否则落到 Exception.class 回退的
   * 500,console BFF 透传下游 status 会把这类入参错误当系统崩溃展示给操作员。
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<CommonResponse<Void>> handleTypeMismatch(
      MethodArgumentTypeMismatchException exception) {
    log.info("{} argument type mismatch: name={}", modulePrefix(), exception.getName());
    ResponseEntity.BodyBuilder response = ResponseEntity.badRequest();
    String message =
        resolveCommonCode(ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.label());
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.INVALID_ARGUMENT, message);
    return response.body(failureBody);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<CommonResponse<Void>> handleException(Exception exception) {
    log.error("{} unexpected exception", modulePrefix(), exception);
    ResponseEntity.BodyBuilder response = ResponseEntity.internalServerError();
    String message = resolveCommonCode(ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.label());
    CommonResponse<Void> failureBody = CommonResponse.failure(ResultCode.SYSTEM_ERROR, message);
    return response.body(failureBody);
  }

  private String resolveBizMessage(BizException exception) {
    return bizMessageResolver == null
        ? exception.getMessage()
        : bizMessageResolver.resolve(exception);
  }

  /** 子类可调用,按当前 Locale 把通用 ResultCode 翻译成本地化文案;无 resolver 时返回 fallback。 */
  protected String resolveCommonCode(ResultCode code, String fallback) {
    if (bizMessageResolver == null) {
      return fallback;
    }
    String resolved = bizMessageResolver.resolve(code);
    return resolved == null || resolved.isBlank() ? fallback : resolved;
  }
}
