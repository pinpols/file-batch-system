package io.github.pinpols.batch.orchestrator.domain.entity;

import lombok.Data;

/**
 * ADR-010 闭环 reconciler 投影行:trigger_request × job_instance 按 (tenant_id, dedup_key) JOIN 出来的最小列集,
 * 仅供 {@code TriggerRequestLaunchReconciler} 把卡 ACCEPTED 的 trigger_request 推到 LAUNCHED 用。
 */
@Data
public class TriggerRequestLaunchReconcileRow {

  private String tenantId;
  private String requestId;
  private Long jobInstanceId;
}
