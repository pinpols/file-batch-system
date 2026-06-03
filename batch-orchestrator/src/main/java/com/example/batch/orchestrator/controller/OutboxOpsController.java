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
 * Outbox 运维内部接口(仅供 console 通过 ConsoleOrchestratorProxyService HTTP 转发调用)。
 *
 * <p>承接 console 的 cleanup(按 retainDays 删 PUBLISHED/GIVE_UP)和 republish(FAILED/GIVE_UP → NEW)操作。设立此
 * controller 的原因:CLAUDE.md「Orchestrator 是唯一状态主机」硬约束,console 不能直接 UPDATE/DELETE outbox_event;改由
 * orchestrator 在自己事务里执行。
 *
 * <p>P1-3 (2026-06-03 deep-scan): 加 dryRun query 参数 + operatorId/reason body 字段, dryRun=true 只
 * SELECT COUNT 不 DELETE/UPDATE,生产环境运维误删前可先预演;每次实操写 audit。
 */
@RestController
@RequestMapping("/internal/outbox")
@RequiredArgsConstructor
public class OutboxOpsController {

  private final OutboxOpsApplicationService outboxOpsApplicationService;

  /**
   * 删除已终结事件(PUBLISHED + GIVE_UP 且 updated_at 早于 retainDays)。
   *
   * @return key=published / giveUp 的删除条数(dryRun=true 时返回预估条数, 不实际删除)
   */
  @PostMapping("/cleanup")
  public Map<String, Integer> cleanup(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("retainDays") int retainDays,
      @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
      @RequestBody(required = false) OutboxOpsRequest body) {
    String operatorId = body == null ? null : body.operatorId();
    String reason = body == null ? null : body.reason();
    return outboxOpsApplicationService.cleanup(tenantId, retainDays, dryRun, operatorId, reason);
  }

  /** 重投递 FAILED / GIVE_UP 事件(reset 为 NEW,由 OutboxForwarder 重发);dryRun=true 只返回 candidate 数。 */
  @PostMapping("/republish")
  public Map<String, Integer> republish(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun,
      @RequestBody RepublishRequest request) {
    int affected =
        outboxOpsApplicationService.republish(
            tenantId, request.ids(), dryRun, request.operatorId(), request.reason());
    return Map.of(
        "requested", request.ids() == null ? 0 : request.ids().size(),
        "reset", affected,
        "dryRun", dryRun ? 1 : 0);
  }

  public record OutboxOpsRequest(String operatorId, String reason) {}

  public record RepublishRequest(List<Long> ids, String operatorId, String reason) {}
}
