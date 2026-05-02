package com.example.batch.console.infrastructure.excel;

import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowDefinitionRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowEdgeRow;
import com.example.batch.console.support.excel.WorkflowExcelImportStore.WorkflowNodeRow;
import java.time.Instant;
import java.util.List;
import com.example.batch.console.infrastructure.workflow.DefaultConsoleWorkflowExcelApplicationService;

/**
 * P2-3 god-class-decomposition extract: 内存中已加载的 Excel 上传会话(从 importStore 物化的快照)。
 *
 * <p>service 的 loadSession() 把 {@code WorkflowExcelImportStore.WorkflowExcelSession} 投影成本类型, 供
 * {@link WorkflowExcelRowValidator#validate(WorkflowExcelParsedSession) validator} 与 {@link
 * DefaultConsoleWorkflowExcelApplicationService apply} 阶段共用。
 */
public record WorkflowExcelParsedSession(
    String fileName,
    String tenantId,
    Instant uploadedAt,
    List<WorkflowDefinitionRow> definitions,
    List<WorkflowNodeRow> nodes,
    List<WorkflowEdgeRow> edges) {}
