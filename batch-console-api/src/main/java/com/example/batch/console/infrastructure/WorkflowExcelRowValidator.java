package com.example.batch.console.infrastructure;

import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.DEF_SHEET;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.EDGE_SHEET;
import static com.example.batch.console.infrastructure.WorkflowExcelColumnMetadata.NODE_SHEET;
import static com.example.batch.console.infrastructure.WorkflowExcelTextUtils.hasText;

import com.example.batch.common.enums.DictEnum;
import com.example.batch.common.enums.RetryPolicyType;
import com.example.batch.common.enums.WorkflowEdgeType;
import com.example.batch.common.enums.WorkflowNodeType;
import com.example.batch.common.enums.WorkflowType;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.infrastructure.WorkflowExcelKeys.EdgeKey;
import com.example.batch.console.infrastructure.WorkflowExcelKeys.NodeKey;
import com.example.batch.console.infrastructure.WorkflowExcelKeys.WorkflowKey;
import com.example.batch.console.infrastructure.WorkflowExcelValidationResult.ValidationCounts;
import com.example.batch.console.infrastructure.WorkflowExcelValidationResult.ValidationData;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowDefinitionRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowEdgeRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowNodeRow;
import com.example.batch.console.web.response.ConsoleWorkflowExcelRowIssueResponse;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * P2-3 god-class-decomposition extract: 把 parser 输出的 row 集合做"完整性 + 字典 + 重复键"行级校验。
 *
 * <p>覆盖原 service ~250 行 validate / validateWorkflowStructure / validateNodes / validateEdges。
 * validator 不抛异常 — 把每行的失败原因塞 {@link ConsoleWorkflowExcelRowIssueResponse},汇总到 {@link
 * WorkflowExcelValidationResult},供 service 决定后续 apply 范围 / preview 标注。
 */
@Component
class WorkflowExcelRowValidator {

  private static final Set<String> WORKFLOW_TYPES = DictEnum.codes(WorkflowType.class);
  private static final Set<String> NODE_TYPES = DictEnum.codes(WorkflowNodeType.class);
  private static final Set<String> RETRY_POLICIES = DictEnum.codes(RetryPolicyType.class);
  private static final Set<String> EDGE_TYPES = DictEnum.codes(WorkflowEdgeType.class);

  WorkflowExcelValidationResult validate(WorkflowExcelParsedSession session) {
    List<ConsoleWorkflowExcelRowIssueResponse> issues = new ArrayList<>();
    Set<WorkflowKey> definitionKeys = new LinkedHashSet<>();
    List<WorkflowDefinitionRow> validDefinitions =
        validateWorkflowStructure(session.definitions(), definitionKeys, issues);
    List<WorkflowNodeRow> validNodes = validateNodes(session.nodes(), definitionKeys, issues);
    List<WorkflowEdgeRow> validEdges = validateEdges(session.edges(), definitionKeys, issues);

    int totalRows = session.definitions().size() + session.nodes().size() + session.edges().size();
    int validRows = validDefinitions.size() + validNodes.size() + validEdges.size();
    return WorkflowExcelValidationResult.builder()
        .counts(
            ValidationCounts.builder()
                .definitionRows(session.definitions().size())
                .nodeRows(session.nodes().size())
                .edgeRows(session.edges().size())
                .totalRows(totalRows)
                .validRows(validRows)
                .invalidRows(totalRows - validRows)
                .build())
        .data(
            ValidationData.builder()
                .definitions(validDefinitions)
                .nodes(validNodes)
                .edges(validEdges)
                .issues(issues)
                .build())
        .build();
  }

  private List<WorkflowDefinitionRow> validateWorkflowStructure(
      List<WorkflowDefinitionRow> definitions,
      Set<WorkflowKey> definitionKeys,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    List<WorkflowDefinitionRow> valid = new ArrayList<>();
    for (WorkflowDefinitionRow row : definitions) {
      List<String> rowIssues = new ArrayList<>();
      WorkflowKey key = WorkflowKey.of(row.tenantId(), row.workflowCode(), row.version());
      if (!hasText(row.tenantId())
          || !hasText(row.workflowCode())
          || !hasText(row.workflowName())
          || !hasText(row.workflowType())
          || row.version() == null) {
        rowIssues.add("workflow definition fields are incomplete");
      }
      if (hasText(row.workflowType()) && !WORKFLOW_TYPES.contains(row.workflowType())) {
        rowIssues.add("workflow_type must be one of " + WORKFLOW_TYPES);
      }
      if (!definitionKeys.add(key)) {
        rowIssues.add("duplicate workflow definition in excel: " + key.display());
      }
      if (rowIssues.isEmpty()) {
        valid.add(row);
      } else {
        issues.add(
            new ConsoleWorkflowExcelRowIssueResponse(
                DEF_SHEET,
                row.rowNo(),
                key.display(),
                row.workflowCode(),
                row.version(),
                List.copyOf(rowIssues)));
      }
    }
    return valid;
  }

  private List<WorkflowNodeRow> validateNodes(
      List<WorkflowNodeRow> nodes,
      Set<WorkflowKey> definitionKeys,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    List<WorkflowNodeRow> valid = new ArrayList<>();
    Set<NodeKey> nodeKeys = new LinkedHashSet<>();
    for (WorkflowNodeRow row : nodes) {
      List<String> rowIssues = new ArrayList<>();
      WorkflowKey workflowKey =
          WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion());
      if (!definitionKeys.contains(workflowKey)) {
        rowIssues.add("workflow node references missing definition: " + workflowKey.display());
      }
      if (!hasText(row.tenantId())
          || !hasText(row.workflowCode())
          || row.workflowVersion() == null
          || !hasText(row.nodeCode())
          || !hasText(row.nodeName())
          || !hasText(row.nodeType())) {
        rowIssues.add("workflow node fields are incomplete");
      }
      if (hasText(row.nodeType()) && !NODE_TYPES.contains(row.nodeType())) {
        rowIssues.add("node_type must be one of " + NODE_TYPES);
      }
      if (hasText(row.retryPolicy()) && !RETRY_POLICIES.contains(row.retryPolicy())) {
        rowIssues.add("retry_policy must be one of " + RETRY_POLICIES);
      }
      if (row.nodeParams() != null) {
        try {
          JsonUtils.fromJson(row.nodeParams(), Object.class);
        } catch (IllegalArgumentException exception) {
          rowIssues.add("node_params must be valid JSON");
        }
      }
      NodeKey nodeKey = NodeKey.of(workflowKey, row.nodeCode());
      if (!nodeKeys.add(nodeKey)) {
        rowIssues.add("duplicate workflow node in excel: " + nodeKey.display());
      }
      if (rowIssues.isEmpty()) {
        valid.add(row);
      } else {
        issues.add(
            new ConsoleWorkflowExcelRowIssueResponse(
                NODE_SHEET,
                row.rowNo(),
                nodeKey.display(),
                row.workflowCode(),
                row.workflowVersion(),
                List.copyOf(rowIssues)));
      }
    }
    return valid;
  }

  private List<WorkflowEdgeRow> validateEdges(
      List<WorkflowEdgeRow> edges,
      Set<WorkflowKey> definitionKeys,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {
    List<WorkflowEdgeRow> valid = new ArrayList<>();
    Set<EdgeKey> edgeKeys = new LinkedHashSet<>();
    for (WorkflowEdgeRow row : edges) {
      List<String> rowIssues = new ArrayList<>();
      WorkflowKey workflowKey =
          WorkflowKey.of(row.tenantId(), row.workflowCode(), row.workflowVersion());
      if (!definitionKeys.contains(workflowKey)) {
        rowIssues.add("workflow edge references missing definition: " + workflowKey.display());
      }
      if (!hasText(row.tenantId())
          || !hasText(row.workflowCode())
          || row.workflowVersion() == null
          || !hasText(row.fromNodeCode())
          || !hasText(row.toNodeCode())
          || !hasText(row.edgeType())) {
        rowIssues.add("workflow edge fields are incomplete");
      }
      if (hasText(row.edgeType()) && !EDGE_TYPES.contains(row.edgeType())) {
        rowIssues.add("edge_type must be one of " + EDGE_TYPES);
      }
      EdgeKey edgeKey =
          EdgeKey.of(workflowKey, row.fromNodeCode(), row.toNodeCode(), row.edgeType());
      if (!edgeKeys.add(edgeKey)) {
        rowIssues.add("duplicate workflow edge in excel: " + edgeKey.display());
      }
      if (rowIssues.isEmpty()) {
        valid.add(row);
      } else {
        issues.add(
            new ConsoleWorkflowExcelRowIssueResponse(
                EDGE_SHEET,
                row.rowNo(),
                edgeKey.display(),
                row.workflowCode(),
                row.workflowVersion(),
                List.copyOf(rowIssues)));
      }
    }
    return valid;
  }
}
