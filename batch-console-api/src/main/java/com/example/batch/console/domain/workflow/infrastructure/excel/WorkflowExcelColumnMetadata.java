package com.example.batch.console.domain.workflow.infrastructure.excel;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;

import com.example.batch.common.persistence.BatchColumnNames;
import com.example.batch.console.support.excel.ConsoleExcelStyles;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P2-3 god-class-decomposition extract: 从 {@link DefaultConsoleWorkflowExcelApplicationService}
 * 抽出的列定义/sheet 名/列说明常量。
 *
 * <p>原 service 内静态常量散布 ~150 行,既影响可读性又让 writer/parser 无法独立 reuse。 集中到 metadata 类后,后续 {@code
 * WorkflowExcelWorkbookWriter} 从这里取列定义,主 service 只持业务编排责任。
 *
 * <p>所有字段 public static final，供 infrastructure 及 infrastructure.excel 子包共用。
 */
public final class WorkflowExcelColumnMetadata {

  private WorkflowExcelColumnMetadata() {}

  private static final String FMT_STRING_KEY = "excel.guide.format.string";

  // ── sheet 名 ──────────────────────────────────────────────────────────────
  public static final String DEF_SHEET = "workflow_definition";
  public static final String NODE_SHEET = "workflow_node";
  public static final String EDGE_SHEET = "workflow_edge";

  // ── 字典字段名 ────────────────────────────────────────────────────────────
  public static final String COL_DESCRIPTION = "description";
  public static final String COL_ENABLED = "enabled";
  public static final String COL_TENANT_ID = BatchColumnNames.TENANT_ID;
  public static final String COL_WORKFLOW_CODE = "workflow_code";
  public static final String COL_NODE_TYPE = "node_type";
  public static final String COL_EDGE_TYPE = "edge_type";
  public static final String COL_WORKFLOW_TYPE = "workflow_type";
  public static final String COL_WORKFLOW_VERSION = "workflow_version";
  public static final String COL_RETRY_POLICY = "retry_policy";
  public static final String EDGE_SUCCESS = "SUCCESS";
  public static final String GUIDE_STR = "字符串";
  public static final String GUIDE_TRUE = "TRUE";
  public static final String GUIDE_INT = "整数";
  public static final String GUIDE_FALSE = "FALSE";

  // ── 列顺序 ────────────────────────────────────────────────────────────────
  public static final List<String> DEF_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          "workflow_name",
          COL_WORKFLOW_TYPE,
          "version",
          COL_ENABLED,
          COL_DESCRIPTION);
  public static final List<String> NODE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_VERSION,
          "node_code",
          "node_name",
          COL_NODE_TYPE,
          "related_job_code",
          "related_pipeline_code",
          "worker_group",
          "window_code",
          "node_order",
          COL_RETRY_POLICY,
          "retry_max_count",
          "timeout_seconds",
          "node_params",
          COL_ENABLED);
  public static final List<String> EDGE_COLUMNS =
      List.of(
          COL_TENANT_ID,
          COL_WORKFLOW_CODE,
          COL_WORKFLOW_VERSION,
          "from_node_code",
          "to_node_code",
          COL_EDGE_TYPE,
          "condition_expr",
          COL_ENABLED);

  public static final Set<String> DEF_HEADERS = Set.copyOf(DEF_COLUMNS);
  public static final Set<String> NODE_HEADERS = Set.copyOf(NODE_COLUMNS);
  public static final Set<String> EDGE_HEADERS = Set.copyOf(EDGE_COLUMNS);

  // ── 列说明(导出 Excel 时写入说明 sheet/批注) ──────────────────────────────
  // description / formatHint 现在用 excel.* i18n key,writer 传 MessageSource+Locale 时按
  // 当前请求 Locale 解析(messages.properties / messages_zh_CN.properties);example 与
  // allowedValues 保持字面量(语种无关 ID/枚举码,不需要翻译)。
  public static final Map<String, ConsoleExcelStyles.ColumnGuide> DEF_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID,
              optionalColumn("excel.workflow.def.tenant_id.desc", FMT_STRING_KEY, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn(
                  "excel.workflow.def.workflow_code.desc", FMT_STRING_KEY, "WF_SETTLEMENT")),
          Map.entry(
              "workflow_name",
              requiredColumn("excel.workflow.def.workflow_name.desc", FMT_STRING_KEY, "清算工作流")),
          Map.entry(
              COL_WORKFLOW_TYPE,
              requiredColumn(
                  "excel.workflow.def.workflow_type.desc",
                  "excel.guide.format.enum",
                  "DAG",
                  "DAG",
                  "PIPELINE",
                  "MIXED")),
          Map.entry(
              "version",
              requiredColumn("excel.workflow.def.version.desc", "excel.guide.format.integer", "1")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.workflow.def.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)),
          Map.entry(
              COL_DESCRIPTION,
              optionalColumn("excel.workflow.def.description.desc", FMT_STRING_KEY, "夜间清算编排流程")));
  public static final Map<String, ConsoleExcelStyles.ColumnGuide> NODE_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID,
              optionalColumn("excel.workflow.node.tenant_id.desc", FMT_STRING_KEY, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn(
                  "excel.workflow.node.workflow_code.desc", FMT_STRING_KEY, "WF_SETTLEMENT")),
          Map.entry(
              COL_WORKFLOW_VERSION,
              requiredColumn(
                  "excel.workflow.node.workflow_version.desc", "excel.guide.format.integer", "1")),
          Map.entry(
              "node_code",
              requiredColumn("excel.workflow.node.node_code.desc", FMT_STRING_KEY, "LOAD_SOURCE")),
          Map.entry(
              "node_name",
              requiredColumn("excel.workflow.node.node_name.desc", FMT_STRING_KEY, "加载源文件")),
          Map.entry(
              COL_NODE_TYPE,
              requiredColumn(
                  "excel.workflow.node.node_type.desc",
                  "excel.guide.format.enum",
                  "TASK",
                  "TASK",
                  "GATEWAY",
                  "FILE_STEP",
                  "START",
                  "END",
                  "JOB")),
          Map.entry(
              "related_job_code",
              optionalColumn(
                  "excel.workflow.node.related_job_code.desc", FMT_STRING_KEY, "JOB_IMPORT_001")),
          Map.entry(
              "related_pipeline_code",
              optionalColumn(
                  "excel.workflow.node.related_pipeline_code.desc",
                  FMT_STRING_KEY,
                  "PIPE_IMPORT_001")),
          Map.entry(
              "worker_group",
              optionalColumn(
                  "excel.workflow.node.worker_group.desc", FMT_STRING_KEY, "worker-general")),
          Map.entry(
              "window_code",
              optionalColumn(
                  "excel.workflow.node.window_code.desc", FMT_STRING_KEY, "WINDOW_NIGHT")),
          Map.entry(
              "node_order",
              optionalColumn(
                  "excel.workflow.node.node_order.desc", "excel.guide.format.integer", "10")),
          Map.entry(
              COL_RETRY_POLICY,
              optionalColumn(
                  "excel.workflow.node.retry_policy.desc",
                  "excel.guide.format.enum",
                  "FIXED",
                  "NONE",
                  "FIXED",
                  "EXPONENTIAL")),
          Map.entry(
              "retry_max_count",
              optionalColumn(
                  "excel.workflow.node.retry_max_count.desc", "excel.guide.format.integer", "3")),
          Map.entry(
              "timeout_seconds",
              optionalColumn(
                  "excel.workflow.node.timeout_seconds.desc",
                  "excel.guide.format.integer",
                  "1800")),
          Map.entry(
              "node_params",
              optionalColumn(
                  "excel.workflow.node.node_params.desc",
                  "excel.guide.format.json",
                  "{\"mode\":\"full\"}")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.workflow.node.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)));
  public static final Map<String, ConsoleExcelStyles.ColumnGuide> EDGE_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID,
              optionalColumn("excel.workflow.edge.tenant_id.desc", FMT_STRING_KEY, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn(
                  "excel.workflow.edge.workflow_code.desc", FMT_STRING_KEY, "WF_SETTLEMENT")),
          Map.entry(
              COL_WORKFLOW_VERSION,
              requiredColumn(
                  "excel.workflow.edge.workflow_version.desc", "excel.guide.format.integer", "1")),
          Map.entry(
              "from_node_code",
              requiredColumn(
                  "excel.workflow.edge.from_node_code.desc", FMT_STRING_KEY, "LOAD_SOURCE")),
          Map.entry(
              "to_node_code",
              requiredColumn(
                  "excel.workflow.edge.to_node_code.desc", FMT_STRING_KEY, "VALIDATE_FILE")),
          Map.entry(
              COL_EDGE_TYPE,
              requiredColumn(
                  "excel.workflow.edge.edge_type.desc",
                  "excel.guide.format.enum",
                  EDGE_SUCCESS,
                  EDGE_SUCCESS,
                  "FAILURE",
                  "CONDITION",
                  "ALWAYS")),
          Map.entry(
              "condition_expr",
              optionalColumn(
                  "excel.workflow.edge.condition_expr.desc",
                  "excel.guide.format.expression",
                  "${fileReady == true}")),
          Map.entry(
              COL_ENABLED,
              optionalColumn(
                  "excel.workflow.edge.enabled.desc",
                  "excel.guide.format.boolean",
                  GUIDE_TRUE,
                  GUIDE_TRUE,
                  GUIDE_FALSE)));

  static List<String> columnsForSheet(String sheetName) {
    if (NODE_SHEET.equals(sheetName)) {
      return NODE_COLUMNS;
    }
    if (EDGE_SHEET.equals(sheetName)) {
      return EDGE_COLUMNS;
    }
    return DEF_COLUMNS;
  }
}
