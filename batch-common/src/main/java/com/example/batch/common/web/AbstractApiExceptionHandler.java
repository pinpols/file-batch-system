package com.example.batch.common.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.exception.SystemException;
import com.example.batch.common.i18n.BizMessageResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 通用 API 异常处理基类：把 BizException / SystemException / 未预期异常的处理集中到一处， 子类只在 {@link #modulePrefix()}
 * 里提供日志前缀即可，避免 Trigger / Orchestrator / Console 三个模块各写一份 99% 相同的 handler。
 *
 * <p>子类仍可通过 {@code @ExceptionHandler} 追加模块特有异常（如 Orchestrator 的 {@code
 * ResponseStatusException}、Trigger 的 {@code MissingRequestHeaderException}）。
 *
 * <p>i18n:BizException 的展示文案统一过 {@link BizMessageResolver},按 Accept-Language 翻译; 老 literal 异常仍透出原
 * message 不变,逐步迁移到 {@code BizException.of(code, key, args)} 路径。
 */
public abstract class AbstractApiExceptionHandler {

  private final Logger log = LoggerFactory.getLogger(getClass());

  private BizMessageResolver bizMessageResolver;

  @Autowired
  public void setBizMessageResolver(BizMessageResolver bizMessageResolver) {
    this.bizMessageResolver = bizMessageResolver;
  }

  /** 日志前缀，用于区分模块来源（"trigger" / "orchestrator" / "console"）。 */
  protected abstract String modulePrefix();

  @ExceptionHandler(BizException.class)
  public ResponseEntity<CommonResponse<Void>> handleBizException(BizException exception) {
    log.warn("{} biz exception", modulePrefix(), exception);
    return ResponseEntity.status(exception.getCode().httpStatus())
        .body(CommonResponse.failure(exception.getCode(), resolveBizMessage(exception)));
  }

  @ExceptionHandler(SystemException.class)
  public ResponseEntity<CommonResponse<Void>> handleSystemException(SystemException exception) {
    log.error("{} system exception", modulePrefix(), exception);
    return ResponseEntity.status(exception.getCode().httpStatus())
        .body(CommonResponse.failure(exception.getCode(), exception.getMessage()));
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
        exception.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            CommonResponse.failure(
                ResultCode.CONFLICT, resolveCommonCode(ResultCode.CONFLICT, "并发修改冲突,请重试")));
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
        exception.getMessage());
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            CommonResponse.failure(
                ResultCode.CONFLICT, resolveCommonCode(ResultCode.CONFLICT, "并发插入冲突,请重试")));
  }

  /**
   * 404 NoResourceFoundException 显式按 404 + INFO 处理,不走 Exception.class 兜底的 500 + ERROR 路径。否则任何请求不存在
   * URL(浏览器探测 favicon.ico / 误填 actuator path)都会被记成 ERROR 刷屏,且返回 500 误导调用方。
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<CommonResponse<Void>> handleNoResourceFound(
      NoResourceFoundException exception) {
    log.info("{} resource not found: {}", modulePrefix(), exception.getResourcePath());
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(
            CommonResponse.failure(
                ResultCode.NOT_FOUND,
                resolveCommonCode(ResultCode.NOT_FOUND, ResultCode.NOT_FOUND.label())));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<CommonResponse<Void>> handleException(Exception exception) {
    log.error("{} unexpected exception", modulePrefix(), exception);
    return ResponseEntity.internalServerError()
        .body(
            CommonResponse.failure(
                ResultCode.SYSTEM_ERROR,
                resolveCommonCode(ResultCode.SYSTEM_ERROR, ResultCode.SYSTEM_ERROR.label())));
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
