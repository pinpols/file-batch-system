package com.example.batch.orchestrator.controller;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.mapper.UpdateNodeRunStatusParam;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/workflow-runs")
@RequiredArgsConstructor
public class WorkflowRunManagementController {

    private static final Set<String> CANCELLABLE = Set.of("CREATED", "RUNNING");
    private static final Set<String> TERMINABLE = Set.of("RUNNING");

    private final WorkflowRunMapper workflowRunMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id,
                                       @RequestParam("tenantId") String tenantId) {
        WorkflowRunEntity run = findRun(tenantId, id);
        if (!CANCELLABLE.contains(run.getRunStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT, "cannot cancel from " + run.getRunStatus());
        }
        workflowRunMapper.updateStatus(tenantId, id, "TERMINATED", run.getCurrentNodeCode(), Instant.now());
        return Map.of("id", id, "status", "TERMINATED");
    }

    @PostMapping("/{id}/terminate")
    public Map<String, Object> terminate(@PathVariable Long id,
                                          @RequestParam("tenantId") String tenantId) {
        WorkflowRunEntity run = findRun(tenantId, id);
        if (!TERMINABLE.contains(run.getRunStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT, "cannot terminate from " + run.getRunStatus());
        }
        workflowRunMapper.updateStatus(tenantId, id, "TERMINATED", run.getCurrentNodeCode(), Instant.now());
        return Map.of("id", id, "status", "TERMINATED");
    }

    @PostMapping("/{id}/skip-node")
    public Map<String, Object> skipNode(@PathVariable Long id,
                                         @RequestParam("tenantId") String tenantId,
                                         @RequestParam("nodeCode") String nodeCode) {
        WorkflowRunEntity run = findRun(tenantId, id);
        if (!"RUNNING".equals(run.getRunStatus()) && !"FAILED".equals(run.getRunStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "workflow run must be RUNNING or FAILED to skip a node");
        }
        WorkflowNodeRunEntity nodeRun =
                workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(id, nodeCode);
        if (nodeRun == null) {
            throw new BizException(ResultCode.NOT_FOUND, "node run not found: " + nodeCode);
        }
        if (!"FAILED".equals(nodeRun.getNodeStatus())) {
            throw new BizException(ResultCode.STATE_CONFLICT,
                    "can only skip FAILED nodes, current: " + nodeRun.getNodeStatus());
        }
        workflowNodeRunMapper.updateStatus(UpdateNodeRunStatusParam.builder()
                .id(nodeRun.getId()).nodeStatus("SKIPPED")
                .errorCode(null).errorMessage(null)
                .durationMs(nodeRun.getDurationMs()).finishedAt(Instant.now()).build());
        return Map.of("id", id, "nodeCode", nodeCode, "nodeStatus", "SKIPPED");
    }

    private WorkflowRunEntity findRun(String tenantId, Long id) {
        WorkflowRunEntity run = workflowRunMapper.selectById(tenantId, id);
        if (run == null) {
            throw new BizException(ResultCode.NOT_FOUND, "workflow run not found");
        }
        return run;
    }
}
