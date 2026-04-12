package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.RetryGovernanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
