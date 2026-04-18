package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.RetryGovernanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 死信消息重放内部控制器，基础路径 {@code /internal/dead-letters}。
 * 提供单一端点 {@code POST /{deadLetterId}/replay}，触发指定死信消息的重新投递，
 * 委托 {@link com.example.batch.orchestrator.application.service.RetryGovernanceService} 处理。
 * 仅限内部运维或补偿流程调用，不对外暴露。
 */
@RestController
@RequestMapping("/internal/dead-letters")
@RequiredArgsConstructor
public class DeadLetterController {

  private final RetryGovernanceService retryGovernanceService;

  @PostMapping("/{deadLetterId}/replay")
  public void replay(
      @PathVariable Long deadLetterId, @RequestBody DeadLetterReplayRequest request) {
    retryGovernanceService.replayDeadLetter(request.tenantId(), deadLetterId);
  }

  public record DeadLetterReplayRequest(String tenantId) {}
}
