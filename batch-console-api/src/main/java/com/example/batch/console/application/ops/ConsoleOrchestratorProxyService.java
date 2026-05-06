package com.example.batch.console.application.ops;

import com.example.batch.console.web.response.ops.ConsoleSchedulerSnapshotHistoryResponse;
import com.example.batch.console.web.response.ops.ConsoleSchedulerSnapshotResponse;
import java.util.List;
import java.util.Map;

/**
 * 编排器代理服务：控制台不直接持有编排器的领域模型，通过此代理将运维动作转发至编排器内部接口， 由实现层负责 HTTP/RPC 调用、错误码映射和响应透传。
 *
 * <p>动作语义（{@code action} 参数均为大写字符串，如 {@code "CANCEL"}、{@code "RETRY"}）：
 *
 * <ul>
 *   <li>instanceAction — 针对 job_instance 的生命周期操作（CANCEL / RETRY / TERMINATE 等）
 *   <li>partitionAction — 针对分区级别的操作（RETRY_PARTITION / CANCEL_PARTITION 等）
 *   <li>workflowRunAction — 针对 workflow_run 的操作（CANCEL / TERMINATE 等）
 *   <li>workflowRunSkipNode — 跳过指定节点并让 DAG 继续推进，用于卡死节点的人工干预
 *   <li>outboxCleanup / outboxRepublish — outbox_event 表运维（删除终结事件 / 重投递失败事件）， 由 orchestrator
 *       在自己事务里执行；console 不直接写 outbox 表
 * </ul>
 *
 * <p>快照查询（schedulerSnapshot / schedulerSnapshotHistory）为只读，不改变编排器状态。
 */
public interface ConsoleOrchestratorProxyService {

  Map<String, Object> instanceAction(Long id, String tenantId, String action);

  Map<String, Object> partitionAction(Long id, String tenantId, String action);

  Map<String, Object> workflowRunAction(Long id, String tenantId, String action);

  Map<String, Object> workflowRunSkipNode(Long id, String tenantId, String nodeCode);

  ConsoleSchedulerSnapshotResponse schedulerSnapshot(String tenantId);

  List<ConsoleSchedulerSnapshotHistoryResponse> schedulerSnapshotHistory(
      String tenantId, int limit);

  /**
   * 转发 outbox cleanup：删除指定租户中 PUBLISHED + GIVE_UP 且 updated_at 早于 retainDays 的事件。
   *
   * @return key=published / giveUp 的删除条数
   */
  Map<String, Integer> outboxCleanup(String tenantId, int retainDays);

  /**
   * 转发 outbox republish：把 FAILED / GIVE_UP 状态的指定 id 事件 reset 回 NEW，让 OutboxForwarder 重投递。
   *
   * @return key=requested / reset 的统计（reset 可能小于 requested，因有些 id 不在 FAILED/GIVE_UP）
   */
  Map<String, Integer> outboxRepublish(String tenantId, List<Long> ids);

  /**
   * 转发批量日治理动作：FREEZE / RELEASE / SKIP / REOPEN / CLOSE。 状态机由 orchestrator 的
   * BatchDayOperationService 推进，同事务双写 `job_execution_log` + `batch_day_operation_audit`。
   *
   * @return key=batchDayId / dayStatus / frozen / releasedLaunchCount
   */
  Map<String, Object> batchDayOperate(
      String tenantId,
      String calendarCode,
      java.time.LocalDate bizDate,
      String action,
      String operatorId,
      String reason);

  /**
   * ADR-022 v0.1 转发 forensic 取证导出请求 — 同步生成 bundle，返回 exportId / sha256 / size / 路径。
   *
   * @return key=exportId / status / storagePath / fileSizeBytes / sha256
   */
  Map<String, Object> requestForensicExport(
      String tenantId,
      java.time.LocalDate bizDateFrom,
      java.time.LocalDate bizDateTo,
      java.util.List<String> jobCodes,
      String exportFormat,
      String requestedBy);

  /**
   * ADR-022 v0.1 转发 forensic 下载请求 — 返回 zip 字节流（content-type=application/octet-stream）。
   *
   * <p>orchestrator 内部接口已经把 sha256 放在 `X-Forensic-Sha256` header；console 透传给前端。
   */
  byte[] downloadForensicExport(String tenantId, String exportId);
}
