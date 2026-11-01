package io.github.pinpols.batch.trigger.web;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.service.readiness.ReadinessRecoveryService;
import io.github.pinpols.batch.trigger.service.readiness.ReadinessRecoveryService.RecoveryLaunchOutcome;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orchestrator 驱动的 readiness 恢复端点（{@code X-Internal-Secret} 保护）。
 *
 * <p>收到原 fire 身份后由 {@link ReadinessRecoveryService} 重建启动命令并走正常启动链路：不依赖 Quartz
 * 回调，但保留 Trigger 的全部守卫（就绪闸、版本/日历校验、幂等）。未就绪返回 409 让 Orchestrator 下轮重试。
 * 本类只做 outcome → HTTP 语义映射，业务逻辑全部在 service。
 */
@RestController
@RequestMapping("/internal/trigger/readiness-waits")
@RequiredArgsConstructor
public class InternalReadinessRecoveryController {

  private final ReadinessRecoveryService readinessRecoveryService;

  @PostMapping("/recover")
  public ResponseEntity<RecoverResponse> recover(@RequestBody RecoverRequest request) {
    if (EmptyChecks.isNull(request)) {
      RecoverResponse invalidRequest =
          new RecoverResponse(RecoveryLaunchOutcome.INVALID_REQUEST.name());
      return ResponseEntity.badRequest().body(invalidRequest);
    }
    RecoveryLaunchOutcome outcome = readinessRecoveryService.recover(
        request.tenantId(), request.workflowDefinitionId(), request.scheduledFireTime());
    HttpStatus status =
        switch (outcome) {
          case LAUNCHED -> HttpStatus.OK;
          case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
          case NOT_FOUND -> HttpStatus.NOT_FOUND;
          case ALREADY_TERMINATED, NOT_READY -> HttpStatus.CONFLICT;
          case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    RecoverResponse body = new RecoverResponse(outcome.name());
    return ResponseEntity.status(status).body(body);
  }

  /** Orchestrator 传入的原 fire 身份。 */
  public record RecoverRequest(
      String tenantId, Long workflowDefinitionId, Instant scheduledFireTime) {}

  /** 恢复结果：LAUNCHED / ALREADY_TERMINATED / NOT_READY / NOT_FOUND / INVALID_REQUEST / FAILED。 */
  public record RecoverResponse(String outcome) {}
}
