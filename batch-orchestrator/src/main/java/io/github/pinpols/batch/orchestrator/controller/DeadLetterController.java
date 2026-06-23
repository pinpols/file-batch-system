package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信消息重放内部控制器,基础路径 {@code /internal/dead-letters}。 提供单一端点 {@code POST
 * /{deadLetterId}/replay},触发指定死信消息的重新投递, 委托 {@link
 * io.github.pinpols.batch.orchestrator.application.service.governance.RetryGovernanceService}
 * 处理。仅限内部运维或补偿流程调用,不对外暴露。
 *
 * <p>P1-1 (2026-06-03 deep-scan-be-business-ops): 接收 operatorId / reason / idempotencyKey 三字段,写入
 * job_execution_log audit 行,补齐运维侧追溯链。
 */
@RestController
@RequestMapping("/internal/dead-letters")
@RequiredArgsConstructor
public class DeadLetterController {

  private final RetryGovernanceService retryGovernanceService;

  @PostMapping("/{deadLetterId}/replay")
  public void replay(
      @PathVariable Long deadLetterId, @RequestBody DeadLetterReplayRequest request) {
    retryGovernanceService.replayDeadLetter(
        request.tenantId(),
        deadLetterId,
        request.operatorId(),
        request.reason(),
        request.idempotencyKey());
  }

  /**
   * @param tenantId 必填,定位死信
   * @param operatorId 触发者,人工 console 触发必填;自动重放走 SYSTEM 别名
   * @param reason 重放原因(供运维事后追溯,非空建议)
   * @param idempotencyKey 上游可用于 dedupe 的幂等键;当前仅入 audit,不参与 CAS,未来扩展点
   */
  public record DeadLetterReplayRequest(
      String tenantId, String operatorId, String reason, String idempotencyKey) {}
}
