package com.example.batch.orchestrator.controller;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.i18n.BizMessageResolver;
import com.example.batch.common.web.AbstractApiExceptionHandler;
import com.example.batch.orchestrator.application.service.governance.DeadLetterOrphanSourceException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackageClasses = LaunchController.class)
public class OrchestratorApiExceptionHandler extends AbstractApiExceptionHandler {

  /** 测试 no-arg(standalone MockMvc):不注入 i18n resolver。 */
  public OrchestratorApiExceptionHandler() {
    super();
  }

  @Autowired
  public OrchestratorApiExceptionHandler(
      ObjectProvider<BizMessageResolver> bizMessageResolverProvider) {
    super(bizMessageResolverProvider);
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

  /** orchestrator 特有：把 Spring 的 ResponseStatusException 映射回 CommonResponse。 */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<CommonResponse<Void>> handleResponseStatus(
      ResponseStatusException exception) {
    ResultCode code =
        switch (exception.getStatusCode().value()) {
          case 404 -> ResultCode.NOT_FOUND;
          case 409 -> ResultCode.CONFLICT;
          default -> ResultCode.SYSTEM_ERROR;
        };
    String message = exception.getReason() != null ? exception.getReason() : code.defaultMessage();
    return ResponseEntity.status(exception.getStatusCode())
        .body(CommonResponse.failure(code, message));
  }
}
