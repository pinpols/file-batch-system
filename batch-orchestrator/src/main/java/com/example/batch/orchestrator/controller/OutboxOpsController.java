package com.example.batch.orchestrator.controller;

import com.example.batch.orchestrator.application.service.governance.OutboxOpsApplicationService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Outbox 运维内部接口（仅供 console 通过 ConsoleOrchestratorProxyService HTTP 转发调用）。
 *
 * <p>承接 console 的 cleanup（按 retainDays 删 PUBLISHED/GIVE_UP）和 republish（FAILED/GIVE_UP → NEW）操作。设立此
 * controller 的原因：CLAUDE.md「Orchestrator 是唯一状态主机」硬约束，console 不能直接 UPDATE/DELETE outbox_event；改由
 * orchestrator 在自己事务里执行。
 */
@RestController
@RequestMapping("/internal/outbox")
@RequiredArgsConstructor
public class OutboxOpsController {

  private final OutboxOpsApplicationService outboxOpsApplicationService;

  /**
   * 删除已终结事件（PUBLISHED + GIVE_UP 且 updated_at 早于 retainDays）。
   *
   * @return key=published / giveUp 的删除条数
   */
  @PostMapping("/cleanup")
  public Map<String, Integer> cleanup(
      @RequestParam("tenantId") String tenantId, @RequestParam("retainDays") int retainDays) {
    return outboxOpsApplicationService.cleanup(tenantId, retainDays);
  }

  /** 重投递 FAILED / GIVE_UP 事件（reset 为 NEW，由 OutboxForwarder 重发）。 */
  @PostMapping("/republish")
  public Map<String, Integer> republish(
      @RequestParam("tenantId") String tenantId, @RequestBody RepublishRequest request) {
    int reset = outboxOpsApplicationService.republish(tenantId, request.ids());
    return Map.of("requested", request.ids() == null ? 0 : request.ids().size(), "reset", reset);
  }

  public record RepublishRequest(List<Long> ids) {}
}
