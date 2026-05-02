package com.example.batch.console.infrastructure.excel;

import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowDefinitionRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowEdgeRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowNodeRow;
import com.example.batch.console.web.response.workflow.ConsoleWorkflowExcelRowIssueResponse;
import java.util.List;
import lombok.Builder;

/**
 * P2-3 god-class-decomposition extract: validator 输出的统计 + 数据 + 行级问题三联体。
 *
 * <p>原 service 内 ValidationResult / Counts / Data 三个 record + 8 个投影方法集中到本文件,可对外发布(record 默认 public
 * 透出 5 类访问)。
 */
@Builder
public record WorkflowExcelValidationResult(ValidationCounts counts, ValidationData data) {

  public int definitionRows() {
    return counts.definitionRows();
  }

  public int nodeRows() {
    return counts.nodeRows();
  }

  public int edgeRows() {
    return counts.edgeRows();
  }

  public int totalRows() {
    return counts.totalRows();
  }

  public int validRows() {
    return counts.validRows();
  }

  public int invalidRows() {
    return counts.invalidRows();
  }

  public List<WorkflowDefinitionRow> definitions() {
    return data.definitions();
  }

  public List<WorkflowNodeRow> nodes() {
    return data.nodes();
  }

  public List<WorkflowEdgeRow> edges() {
    return data.edges();
  }

  public List<ConsoleWorkflowExcelRowIssueResponse> issues() {
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
