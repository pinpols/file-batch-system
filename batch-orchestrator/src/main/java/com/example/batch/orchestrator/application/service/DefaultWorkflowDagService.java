package com.example.batch.orchestrator.application.service;

import com.example.batch.common.enums.WorkflowJoinMode;
import com.example.batch.common.enums.WorkflowNodeCode;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;
import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import com.example.batch.orchestrator.mapper.WorkflowEdgeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeMapper;
import com.example.batch.orchestrator.mapper.WorkflowNodeRunMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultWorkflowDagService implements WorkflowDagService {

    private final WorkflowEdgeMapper workflowEdgeMapper;
    private final WorkflowNodeMapper workflowNodeMapper;
    private final WorkflowNodeRunMapper workflowNodeRunMapper;
    private final WorkflowConditionEvaluator workflowConditionEvaluator;

    @Override
    public List<DagNodeResolution> resolveInitialNodes(
            Long workflowDefinitionId, String payloadJson) {
        return resolveNextNodes(
                workflowDefinitionId, WorkflowNodeCode.START.code(), true, payloadJson);
    }

    @Override
    public List<DagNodeResolution> resolveNextNodes(
            Long workflowDefinitionId,
            String currentNodeCode,
            boolean success,
            String payloadJson) {
        if (workflowDefinitionId == null || currentNodeCode == null || currentNodeCode.isBlank()) {
            return List.of();
        }
        List<WorkflowEdgeEntity> outgoingEdges =
                workflowEdgeMapper.selectOutgoingEdges(workflowDefinitionId, currentNodeCode);
        if (outgoingEdges == null || outgoingEdges.isEmpty()) {
            return List.of();
        }
        List<DagNodeResolution> resolutions = new ArrayList<>();
        for (WorkflowEdgeEntity edge : outgoingEdges) {
            if (!matchesOutgoingEdge(edge, success, payloadJson)) {
                continue;
            }
            WorkflowNodeEntity nextNode =
                    workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
                            workflowDefinitionId, edge.getToNodeCode());
            if (nextNode == null) {
                resolutions.add(
                        new DagNodeResolution(edge.getToNodeCode(), WorkflowNodeType.END.code()));
                continue;
            }
            resolutions.add(new DagNodeResolution(nextNode.getNodeCode(), nextNode.getNodeType()));
        }
        return resolutions;
    }

    @Override
    public boolean isNodeReadyForDispatch(
            Long workflowRunId, Long workflowDefinitionId, String nodeCode, String payloadJson) {
        if (workflowRunId == null
                || workflowDefinitionId == null
                || nodeCode == null
                || nodeCode.isBlank()) {
            return false;
        }
        WorkflowNodeEntity currentNode =
                workflowNodeMapper.selectByWorkflowDefinitionIdAndNodeCode(
                        workflowDefinitionId, nodeCode);
        List<WorkflowEdgeEntity> incomingEdges =
                workflowEdgeMapper.selectIncomingEdges(workflowDefinitionId, nodeCode);
        if (incomingEdges == null || incomingEdges.isEmpty()) {
            return true;
        }
        int matchedCount = 0;
        int terminalCount = 0;
        for (WorkflowEdgeEntity edge : incomingEdges) {
            var predecessorRun =
                    workflowNodeRunMapper.selectLatestByWorkflowRunIdAndNodeCode(
                            workflowRunId, edge.getFromNodeCode());
            if (predecessorRun == null || !isTerminal(predecessorRun.getNodeStatus())) {
                continue;
            }
            terminalCount++;
            if (matchesIncomingEdge(edge, predecessorRun.getNodeStatus(), payloadJson)) {
                matchedCount++;
            }
        }
        JoinRule joinRule = resolveJoinRule(currentNode, incomingEdges.size());
        return switch (joinRule.joinMode()) {
            case ALL ->
                    terminalCount == incomingEdges.size() && matchedCount == incomingEdges.size();
            case ANY -> matchedCount >= 1;
            case N_OF -> matchedCount >= joinRule.joinThreshold();
        };
    }

    private boolean matchesOutgoingEdge(
            WorkflowEdgeEntity edge, boolean success, String payloadJson) {
        String edgeType = edge.getEdgeType();
        if ("ALWAYS".equalsIgnoreCase(edgeType)) {
            return true;
        }
        if ("SUCCESS".equalsIgnoreCase(edgeType)) {
            return success;
        }
        if ("FAILURE".equalsIgnoreCase(edgeType)) {
            return !success;
        }
        if ("CONDITION".equalsIgnoreCase(edgeType)) {
            return success
                    && workflowConditionEvaluator.matches(edge.getConditionExpr(), payloadJson);
        }
        return false;
    }

    private boolean matchesIncomingEdge(
            WorkflowEdgeEntity edge, String predecessorStatus, String payloadJson) {
        String edgeType = edge.getEdgeType();
        if ("ALWAYS".equalsIgnoreCase(edgeType)) {
            return isTerminal(predecessorStatus);
        }
        if ("SUCCESS".equalsIgnoreCase(edgeType)) {
            return "SUCCESS".equalsIgnoreCase(predecessorStatus);
        }
        if ("FAILURE".equalsIgnoreCase(edgeType)) {
            return "FAILED".equalsIgnoreCase(predecessorStatus);
        }
        if ("CONDITION".equalsIgnoreCase(edgeType)) {
            return "SUCCESS".equalsIgnoreCase(predecessorStatus)
                    && workflowConditionEvaluator.matches(edge.getConditionExpr(), payloadJson);
        }
        return false;
    }

    private boolean isTerminal(String nodeStatus) {
        return "SUCCESS".equalsIgnoreCase(nodeStatus)
                || "FAILED".equalsIgnoreCase(nodeStatus)
                || "SKIPPED".equalsIgnoreCase(nodeStatus);
    }

    /** joinMode 写入 workflow_node.node_params，避免为 join 规则单独扩表。 */
    @SuppressWarnings("unchecked")
    private JoinRule resolveJoinRule(WorkflowNodeEntity node, int incomingEdgeCount) {
        if (node == null || node.getNodeParams() == null || node.getNodeParams().isBlank()) {
            return new JoinRule(WorkflowJoinMode.ALL, Math.max(1, incomingEdgeCount));
        }
        try {
            Object nodeParamsObject = JsonUtils.fromJson(node.getNodeParams(), Object.class);
            if (!(nodeParamsObject instanceof Map<?, ?> nodeParamsMap)) {
                return new JoinRule(WorkflowJoinMode.ALL, Math.max(1, incomingEdgeCount));
            }
            Map<String, Object> params = (Map<String, Object>) nodeParamsMap;
            WorkflowJoinMode joinMode =
                    WorkflowJoinMode.fromCode(stringValue(params.get("joinMode")));
            if (joinMode == WorkflowJoinMode.ALL) {
                return new JoinRule(joinMode, Math.max(1, incomingEdgeCount));
            }
            if (joinMode == WorkflowJoinMode.ANY) {
                return new JoinRule(joinMode, 1);
            }
            int threshold = integerValue(params.get("joinThreshold"));
            if (threshold <= 0) {
                threshold = Math.max(1, incomingEdgeCount);
            }
            threshold = Math.min(threshold, Math.max(1, incomingEdgeCount));
            return new JoinRule(joinMode, threshold);
        } catch (IllegalArgumentException exception) {
            return new JoinRule(WorkflowJoinMode.ALL, Math.max(1, incomingEdgeCount));
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int integerValue(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private record JoinRule(WorkflowJoinMode joinMode, int joinThreshold) {}
}
