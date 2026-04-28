package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.common.utils.Guard;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.UpdateNodeRunStatusParam;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 工作流运行实例的生命周期管理应用服务，提供取消、终止和跳过节点等运维操作。
 *
 * <p>取消和终止最终均将运行状态置为 {@code TERMINATED}，区别在于允许的前置状态范围不同： 取消允许从 {@code CREATED} 或 {@code RUNNING}
 * 发起，而终止仅允许 {@code RUNNING} 状态。 节点跳过（{@link #skipNode}）仅作用于 {@code FAILED} 的节点运行记录， 可在工作流整体处于
 * {@code RUNNING} 或 {@code FAILED} 时使用，以便跳过卡点后续继续推进。
 *
 * <p>所有状态变更通过 Mapper 层完成，不经过完整的编排引擎，属于直接的运维干预手段。
 */
@Service
@RequiredArgsConstructor
public class WorkflowRunManagementApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String STATUS_TERMINATED = "TERMINATED";

  private static final Set<String> CANCELLABLE = Set.of("CREATED", "RUNNING");
  private static final Set<String> TERMINABLE = Set.of("RUNNING");

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;

  public Map<String, Object> cancel(String tenantId, Long id) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!CANCELLABLE.contains(run.getRunStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.workflow.cancel_invalid_state", run.getRunStatus());
    }
    workflowRunMapper.updateStatus(
        tenantId, id, STATUS_TERMINATED, run.getCurrentNodeCode(), Instant.now());
    return Map.of("id", id, "status", STATUS_TERMINATED);
  }

  public Map<String, Object> terminate(String tenantId, Long id) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!TERMINABLE.contains(run.getRunStatus())) {
      throw new BizException(
          ResultCode.STATE_CONFLICT, "cannot terminate from " + run.getRunStatus());
    }
    workflowRunMapper.updateStatus(
        tenantId, id, STATUS_TERMINATED, run.getCurrentNodeCode(), Instant.now());
    return Map.of("id", id, "status", STATUS_TERMINATED);
  }

  public Map<String, Object> skipNode(String tenantId, Long id, String nodeCode) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!"RUNNING".equals(run.getRunStatus()) && !"FAILED".equals(run.getRunStatus())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.skip_node_state_invalid");
    }
    WorkflowNodeRunEntity nodeRun =
        Guard.requireFound(
            workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(id, nodeCode),
            "node run not found: " + nodeCode);
    if (!"FAILED".equals(nodeRun.getNodeStatus())) {
      throw new BizException(
          ResultCode.STATE_CONFLICT,
          "can only skip FAILED nodes, current: " + nodeRun.getNodeStatus());
    }
    workflowNodeRunMapper.updateStatus(
        UpdateNodeRunStatusParam.builder()
            .id(nodeRun.getId())
            .nodeStatus("SKIPPED")
            .errorCode(null)
            .errorMessage(null)
            .durationMs(nodeRun.getDurationMs())
            .finishedAt(Instant.now())
            .build());
    return Map.of("id", id, "nodeCode", nodeCode, "nodeStatus", "SKIPPED");
  }

  private WorkflowRunEntity findRun(String tenantId, Long id) {
    return Guard.requireFound(workflowRunMapper.selectById(tenantId, id), "workflow run not found");
  }
}
