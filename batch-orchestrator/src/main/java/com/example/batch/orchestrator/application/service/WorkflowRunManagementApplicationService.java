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

@Service
@RequiredArgsConstructor
public class WorkflowRunManagementApplicationService {

  private static final Set<String> CANCELLABLE = Set.of("CREATED", "RUNNING");
  private static final Set<String> TERMINABLE = Set.of("RUNNING");

  private final WorkflowRunMapper workflowRunMapper;
  private final WorkflowNodeRunMapper workflowNodeRunMapper;

  public Map<String, Object> cancel(String tenantId, Long id) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!CANCELLABLE.contains(run.getRunStatus())) {
      throw new BizException(ResultCode.STATE_CONFLICT, "cannot cancel from " + run.getRunStatus());
    }
    workflowRunMapper.updateStatus(
        tenantId, id, "TERMINATED", run.getCurrentNodeCode(), Instant.now());
    return Map.of("id", id, "status", "TERMINATED");
  }

  public Map<String, Object> terminate(String tenantId, Long id) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!TERMINABLE.contains(run.getRunStatus())) {
      throw new BizException(
          ResultCode.STATE_CONFLICT, "cannot terminate from " + run.getRunStatus());
    }
    workflowRunMapper.updateStatus(
        tenantId, id, "TERMINATED", run.getCurrentNodeCode(), Instant.now());
    return Map.of("id", id, "status", "TERMINATED");
  }

  public Map<String, Object> skipNode(String tenantId, Long id, String nodeCode) {
    WorkflowRunEntity run = findRun(tenantId, id);
    if (!"RUNNING".equals(run.getRunStatus()) && !"FAILED".equals(run.getRunStatus())) {
      throw new BizException(
          ResultCode.STATE_CONFLICT, "workflow run must be RUNNING or FAILED to skip a node");
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
