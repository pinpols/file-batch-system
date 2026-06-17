package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.i18n.BizMessageResolver;
import com.example.batch.common.web.AbstractApiExceptionHandler;
import com.example.batch.orchestrator.application.service.governance.DeadLetterOrphanSourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = LaunchController.class)
public class OrchestratorApiExceptionHandler extends AbstractApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(OrchestratorApiExceptionHandler.class);

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

  /** 死信指向的分区 / 作业实例已不存在（常见于历史数据清理）。 */
  @ExceptionHandler(DeadLetterOrphanSourceException.class)
  public ResponseEntity<CommonResponse<Void>> handleDeadLetterOrphanSource(
      DeadLetterOrphanSourceException exception) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(CommonResponse.failure(ResultCode.CONFLICT, exception.getMessage()));
  }

  /**
   * 请求体反序列化失败（如 enum 非法值 {@code DryRunLevel="BOGUS_LEVEL"}、JSON 格式错）→ 400 INVALID_ARGUMENT， 而非落到
   * {@code Exception} 兜底的 500 系统错误。对标 console 侧同款处理,保证非法入参对外是 4xx。
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<CommonResponse<Void>> handleMessageNotReadable(
      HttpMessageNotReadableException exception) {
    return ResponseEntity.badRequest()
        .body(
            CommonResponse.failure(
                ResultCode.INVALID_ARGUMENT,
                resolveCommonCode(
                    ResultCode.INVALID_ARGUMENT, ResultCode.INVALID_ARGUMENT.label())));
  }

  /**
   * 瞬时 DB 并发失败(死锁 / 锁获取超时,如并发 task-outcome 推进同一 job 多分区时的行锁竞争)→ 503 可重试, 而非落到 {@code Exception} 兜底的
   * 500「unexpected exception」(语义=服务端 bug + 刷 ERROR 噪声)。 report / launch 等入口幂等(idempotency key + 状态机
   * CAS),调用方(worker SDK §C)按 5xx 退避重投即收敛。 {@link TransientDataAccessException} 覆盖
   * DeadlockLoserDataAccessException / CannotAcquireLockException / QueryTimeoutException 等一族瞬时错。
   */
  @ExceptionHandler(TransientDataAccessException.class)
  public ResponseEntity<CommonResponse<Void>> handleTransientDataAccess(
      TransientDataAccessException exception) {
    log.warn(
        "transient DB concurrency failure (retryable, mapped to 503): {}", exception.getMessage());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(
            CommonResponse.failure(
                ResultCode.SERVICE_UNAVAILABLE,
                resolveCommonCode(
                    ResultCode.SERVICE_UNAVAILABLE, ResultCode.SERVICE_UNAVAILABLE.label())));
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
    String message = exception.getReason() != null ? exception.getReason() : code.defaultMessage();
    return ResponseEntity.status(exception.getStatusCode())
        .body(CommonResponse.failure(code, message));
  }
}
