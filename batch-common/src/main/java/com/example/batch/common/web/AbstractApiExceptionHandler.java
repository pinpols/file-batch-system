package com.example.batch.common.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * 通用 API 异常处理基类：把 BizException / SystemException / 未预期异常的处理集中到一处，
 * 子类只在 {@link #modulePrefix()} 里提供日志前缀即可，避免 Trigger / Orchestrator /
 * Console 三个模块各写一份 99% 相同的 handler。
 *
 * <p>子类仍可通过 {@code @ExceptionHandler} 追加模块特有异常（如 Orchestrator 的
 * {@code ResponseStatusException}、Trigger 的 {@code MissingRequestHeaderException}）。
 */
public abstract class AbstractApiExceptionHandler {

  private final Logger log = LoggerFactory.getLogger(getClass());

  /** 日志前缀，用于区分模块来源（"trigger" / "orchestrator" / "console"）。 */
  protected abstract String modulePrefix();

  @ExceptionHandler(BizException.class)
  public ResponseEntity<CommonResponse<Void>> handleBizException(BizException exception) {
    log.warn("{} biz exception", modulePrefix(), exception);
    return ResponseEntity.status(exception.getCode().httpStatus())
        .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
  }

  @ExceptionHandler(SystemException.class)
  public ResponseEntity<CommonResponse<Void>> handleSystemException(SystemException exception) {
    log.error("{} system exception", modulePrefix(), exception);
    return ResponseEntity.status(exception.getCode().httpStatus())
        .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
  }

  /**
   * 乐观锁冲突：两个线程同时改同一行记录（@Version CAS 失败）。属可预期并发，
   * 返回 409 CONFLICT + WARN（不是 ERROR）；上游可按 "同样 tick 重试" 处理。
   */
  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<CommonResponse<Void>> handleOptimisticLock(
      OptimisticLockingFailureException exception) {
    log.warn(
        "{} optimistic lock conflict (concurrent modification; upstream may retry): {}",
        modulePrefix(),
        exception.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(CommonResponse.failure(ResultCode.CONFLICT, "concurrent modification; retry"));
  }

  /**
   * 唯一键冲突：create-if-absent 并发撞键。属可预期并发，返回 409 CONFLICT + WARN，
   * 上游应该用"重新读取→改走 update 分支"的方式恢复，不是当成 500 重试。
   */
  @ExceptionHandler(DuplicateKeyException.class)
  public ResponseEntity<CommonResponse<Void>> handleDuplicateKey(DuplicateKeyException exception) {
    log.warn(
        "{} duplicate key conflict (concurrent insert; upstream may retry as update): {}",
        modulePrefix(),
        exception.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(CommonResponse.failure(ResultCode.CONFLICT, "concurrent insert; retry"));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<CommonResponse<Void>> handleException(Exception exception) {
    log.error("{} unexpected exception", modulePrefix(), exception);
    return ResponseEntity.internalServerError()
        .body(
            CommonResponse.failure(
                ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.defaultMessage()));
  }
}
