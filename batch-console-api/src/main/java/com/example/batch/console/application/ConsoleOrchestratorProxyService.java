package com.example.batch.console.application;

import com.example.batch.console.web.response.ConsoleSchedulerSnapshotHistoryResponse;
import com.example.batch.console.web.response.ConsoleSchedulerSnapshotResponse;
import java.util.List;
import java.util.Map;

/**
 * 编排器代理服务：控制台不直接持有编排器的领域模型，通过此代理将运维动作转发至编排器内部接口，
 * 由实现层负责 HTTP/RPC 调用、错误码映射和响应透传。
 *
 * <p>动作语义（{@code action} 参数均为大写字符串，如 {@code "CANCEL"}、{@code "RETRY"}）：
 * <ul>
 *   <li>instanceAction — 针对 job_instance 的生命周期操作（CANCEL / RETRY / TERMINATE 等）
 *   <li>partitionAction — 针对分区级别的操作（RETRY_PARTITION / CANCEL_PARTITION 等）
 *   <li>workflowRunAction — 针对 workflow_run 的操作（CANCEL / TERMINATE 等）
 *   <li>workflowRunSkipNode — 跳过指定节点并让 DAG 继续推进，用于卡死节点的人工干预
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
}
