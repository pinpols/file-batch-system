package io.github.pinpols.batch.orchestrator.controller;

import io.github.pinpols.batch.orchestrator.application.service.PersistentWaitReadService;
import io.github.pinpols.batch.orchestrator.application.service.PersistentWaitReadService.PersistentWaitPage;
import io.github.pinpols.batch.orchestrator.domain.query.PersistentWaitReadQuery;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Console 到 Orchestrator 的持久等待只读内部端点；由 internal-secret 过滤器保护。 */
@RestController
@RequestMapping("/internal/ops/persistent-waits")
@RequiredArgsConstructor
public class PersistentWaitReadInternalController {
  private final PersistentWaitReadService service;

  @GetMapping
  public PersistentWaitPage list(@Valid @ModelAttribute PersistentWaitReadQuery query) {
    return service.list(query);
  }
}
