package com.example.batch.console.application;

import com.example.batch.console.web.response.ConsoleSchedulerSnapshotHistoryResponse;
import com.example.batch.console.web.response.ConsoleSchedulerSnapshotResponse;
import java.util.List;
import java.util.Map;

/**
 * 编排器代理服务：转发控制台对编排器内部接口的操作，包括实例/分区动作与调度快照查询。
 */
public interface ConsoleOrchestratorProxyService {

    Map<String, Object> instanceAction(Long id, String tenantId, String action);

    Map<String, Object> partitionAction(Long id, String tenantId, String action);

    Map<String, Object> workflowRunAction(Long id, String tenantId, String action);

    Map<String, Object> workflowRunSkipNode(Long id, String tenantId, String nodeCode);

    ConsoleSchedulerSnapshotResponse schedulerSnapshot(String tenantId);

    List<ConsoleSchedulerSnapshotHistoryResponse> schedulerSnapshotHistory(String tenantId, int limit);
}
