package com.example.batch.console.infrastructure;

import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowDefinitionRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowEdgeRow;
import com.example.batch.console.support.WorkflowExcelImportStore.WorkflowNodeRow;
import com.example.batch.console.web.response.ConsoleWorkflowExcelRowIssueResponse;
import java.util.List;
import lombok.Builder;

/**
 * P2-3 god-class-decomposition extract: validator 输出的统计 + 数据 + 行级问题三联体。
 *
 * <p>原 service 内 ValidationResult / Counts / Data 三个 record + 8 个投影方法集中到本文件,可对外发布(record 默认 public
 * 透出 5 类访问)。
 */
@Builder
record WorkflowExcelValidationResult(ValidationCounts counts, ValidationData data) {

  int definitionRows() {
    return counts.definitionRows();
  }

  int nodeRows() {
    return counts.nodeRows();
  }

  int edgeRows() {
    return counts.edgeRows();
  }

  int totalRows() {
    return counts.totalRows();
  }

  int validRows() {
    return counts.validRows();
  }

  int invalidRows() {
    return counts.invalidRows();
  }

  List<WorkflowDefinitionRow> definitions() {
    return data.definitions();
  }

  List<WorkflowNodeRow> nodes() {
    return data.nodes();
  }

  List<WorkflowEdgeRow> edges() {
    return data.edges();
  }

  List<ConsoleWorkflowExcelRowIssueResponse> issues() {
    return data.issues();
  }

  @Builder
  record ValidationCounts(
      int definitionRows,
      int nodeRows,
      int edgeRows,
      int totalRows,
      int validRows,
      int invalidRows) {}

  @Builder
  record ValidationData(
      List<WorkflowDefinitionRow> definitions,
      List<WorkflowNodeRow> nodes,
      List<WorkflowEdgeRow> edges,
      List<ConsoleWorkflowExcelRowIssueResponse> issues) {}
}
