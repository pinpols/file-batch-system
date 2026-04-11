package com.example.batch.console.support;

import java.time.Instant;
import java.util.List;

public interface WorkflowExcelImportStore {

    String save(
            String fileName,
            String tenantId,
            List<WorkflowDefinitionRow> definitions,
            List<WorkflowNodeRow> nodes,
            List<WorkflowEdgeRow> edges);

    WorkflowExcelSession get(String uploadToken);

    void remove(String uploadToken);

    record WorkflowExcelSession(
            String fileName,
            String tenantId,
            Instant uploadedAt,
            List<WorkflowDefinitionRow> definitions,
            List<WorkflowNodeRow> nodes,
            List<WorkflowEdgeRow> edges) {}

    record WorkflowDefinitionRow(WorkflowIdentity identity, WorkflowDefinitionPayload payload) {
        public int rowNo() {
            return identity.rowNo();
        }

        public String tenantId() {
            return identity.tenantId();
        }

        public String workflowCode() {
            return identity.workflowCode();
        }

        public String workflowName() {
            return payload.workflowName();
        }

        public String workflowType() {
            return payload.workflowType();
        }

        public Integer version() {
            return payload.version();
        }

        public Boolean enabled() {
            return payload.enabled();
        }

        public String description() {
            return payload.description();
        }
    }

    record WorkflowIdentity(int rowNo, String tenantId, String workflowCode) {}

    record WorkflowDefinitionPayload(
            String workflowName,
            String workflowType,
            Integer version,
            Boolean enabled,
            String description) {}

    record WorkflowNodeRow(
            WorkflowNodeIdentity identity,
            WorkflowNodeRelation relation,
            WorkflowNodeExecution execution,
            WorkflowNodeRuntime runtime) {
        public int rowNo() {
            return identity.rowNo();
        }

        public String tenantId() {
            return identity.tenantId();
        }

        public String workflowCode() {
            return identity.workflowCode();
        }

        public Integer workflowVersion() {
            return identity.workflowVersion();
        }

        public String nodeCode() {
            return identity.nodeCode();
        }

        public String nodeName() {
            return relation.nodeName();
        }

        public String nodeType() {
            return relation.nodeType();
        }

        public String relatedJobCode() {
            return relation.relatedJobCode();
        }

        public String relatedPipelineCode() {
            return relation.relatedPipelineCode();
        }

        public String workerGroup() {
            return execution.workerGroup();
        }

        public String windowCode() {
            return execution.windowCode();
        }

        public Integer nodeOrder() {
            return execution.nodeOrder();
        }

        public String retryPolicy() {
            return runtime.retryPolicy();
        }

        public Integer retryMaxCount() {
            return runtime.retryMaxCount();
        }

        public Integer timeoutSeconds() {
            return runtime.timeoutSeconds();
        }

        public String nodeParams() {
            return runtime.nodeParams();
        }

        public Boolean enabled() {
            return runtime.enabled();
        }
    }

    record WorkflowNodeIdentity(
            int rowNo,
            String tenantId,
            String workflowCode,
            Integer workflowVersion,
            String nodeCode) {}

    record WorkflowNodeRelation(
            String nodeName, String nodeType, String relatedJobCode, String relatedPipelineCode) {}

    record WorkflowNodeExecution(String workerGroup, String windowCode, Integer nodeOrder) {}

    record WorkflowNodeRuntime(
            String retryPolicy,
            Integer retryMaxCount,
            Integer timeoutSeconds,
            String nodeParams,
            Boolean enabled) {}

    record WorkflowEdgeRow(WorkflowEdgeIdentity identity, WorkflowEdgePayload payload) {
        public int rowNo() {
            return identity.rowNo();
        }

        public String tenantId() {
            return identity.tenantId();
        }

        public String workflowCode() {
            return identity.workflowCode();
        }

        public Integer workflowVersion() {
            return identity.workflowVersion();
        }

        public String fromNodeCode() {
            return identity.fromNodeCode();
        }

        public String toNodeCode() {
            return identity.toNodeCode();
        }

        public String edgeType() {
            return payload.edgeType();
        }

        public String conditionExpr() {
            return payload.conditionExpr();
        }

        public Boolean enabled() {
            return payload.enabled();
        }
    }

    record WorkflowEdgeIdentity(
            int rowNo,
            String tenantId,
            String workflowCode,
            Integer workflowVersion,
            String fromNodeCode,
            String toNodeCode) {}

    record WorkflowEdgePayload(String edgeType, String conditionExpr, Boolean enabled) {}
}
