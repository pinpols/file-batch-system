package com.example.batch.console.infrastructure.excel;

import static com.example.batch.console.support.excel.ConsoleExcelStyles.optionalColumn;
import static com.example.batch.console.support.excel.ConsoleExcelStyles.requiredColumn;

import com.example.batch.console.support.excel.ConsoleExcelStyles;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P2-3 god-class-decomposition extract: 从 {@link DefaultConsoleWorkflowExcelApplicationService}
 * 抽出的列定义/sheet 名/列说明常量。
 *
 * <p>原 service 内静态常量散布 ~150 行,既影响可读性又让 writer/parser 无法独立 reuse。 集中到 metadata 类后,后续 {@code
 * WorkflowExcelWorkbookWriter} / {@code WorkflowExcelSheetParser} 都从这里取列定义,主 service 只持业务编排责任。
 *
 * <p>所有字段 public static final，供 infrastructure 及 infrastructure.excel 子包共用。
 */
public final class WorkflowExcelColumnMetadata {

  private WorkflowExcelColumnMetadata() {}

  // ── sheet 名 ──────────────────────────────────────────────────────────────
  public static final String DEF_SHEET = "workflow_definition";
  public static final String NODE_SHEET = "workflow_node";
  public static final String EDGE_SHEET = "workflow_edge";

  // ── 字典字段名 ────────────────────────────────────────────────────────────
  public static final String COL_DESCRIPTION = "description";
  public static final String COL_ENABLED = "enabled";
  public static final String COL_TENANT_ID = "tenant_id";
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
  public static final Map<String, ConsoleExcelStyles.ColumnGuide> DEF_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID, optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn("工作流唯一编码，三个工作流 sheet 都依赖这个键。", GUIDE_STR, "WF_SETTLEMENT")),
          Map.entry("workflow_name", requiredColumn("控制台展示的工作流名称。", GUIDE_STR, "清算工作流")),
          Map.entry(
              COL_WORKFLOW_TYPE,
              requiredColumn("工作流拓扑类型。", "枚举", "DAG", "DAG", "PIPELINE", "MIXED")),
          Map.entry("version", requiredColumn("工作流版本号，节点和边必须使用同一版本。", GUIDE_INT, "1")),
          Map.entry(
              COL_ENABLED,
              optionalColumn("工作流定义是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)),
          Map.entry(COL_DESCRIPTION, optionalColumn("面向运维人员的说明信息。", GUIDE_STR, "夜间清算编排流程")));
  public static final Map<String, ConsoleExcelStyles.ColumnGuide> NODE_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID, optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn(
                  "工作流编码，必须与 workflow_definition.workflow_code 一致。", GUIDE_STR, "WF_SETTLEMENT")),
          Map.entry(
              COL_WORKFLOW_VERSION,
              requiredColumn("工作流版本，必须与 workflow_definition.version 一致。", GUIDE_INT, "1")),
          Map.entry("node_code", requiredColumn("工作流内唯一节点编码。", GUIDE_STR, "LOAD_SOURCE")),
          Map.entry("node_name", requiredColumn("面向运维人员的节点名称。", GUIDE_STR, "加载源文件")),
          Map.entry(
              COL_NODE_TYPE,
              requiredColumn(
                  "编排器识别的节点类型。",
                  "枚举",
                  "TASK",
                  "TASK",
                  "GATEWAY",
                  "FILE_STEP",
                  "START",
                  "END",
                  "JOB")),
          Map.entry(
              "related_job_code", optionalColumn("当该节点触发作业定义时填写。", GUIDE_STR, "JOB_IMPORT_001")),
          Map.entry(
              "related_pipeline_code",
              optionalColumn("当该节点引用 pipeline 定义时填写。", GUIDE_STR, "PIPE_IMPORT_001")),
          Map.entry("worker_group", optionalColumn("运行时使用的执行器分组。", GUIDE_STR, "worker-general")),
          Map.entry("window_code", optionalColumn("系统中已准备好的批量窗口编码。", GUIDE_STR, "WINDOW_NIGHT")),
          Map.entry("node_order", optionalColumn("同层节点的建议执行顺序。", GUIDE_INT, "10")),
          Map.entry(
              COL_RETRY_POLICY,
              optionalColumn("节点失败后的重试策略。", "枚举", "FIXED", "NONE", "FIXED", "EXPONENTIAL")),
          Map.entry("retry_max_count", optionalColumn("最大重试次数，必须大于等于 0。", GUIDE_INT, "3")),
          Map.entry("timeout_seconds", optionalColumn("超时时间（秒），必须大于等于 0。", GUIDE_INT, "1800")),
          Map.entry(
              "node_params", optionalColumn("节点运行参数，请保持为合法 JSON。", "JSON", "{\"mode\":\"full\"}")),
          Map.entry(
              COL_ENABLED, optionalColumn("节点是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));
  public static final Map<String, ConsoleExcelStyles.ColumnGuide> EDGE_COLUMN_GUIDES =
      Map.ofEntries(
          Map.entry(
              COL_TENANT_ID, optionalColumn("当前行所属租户。留空时，上传时自动使用当前租户。", GUIDE_STR, "tenant-a")),
          Map.entry(
              COL_WORKFLOW_CODE,
              requiredColumn(
                  "工作流编码，必须与 workflow_definition.workflow_code 一致。", GUIDE_STR, "WF_SETTLEMENT")),
          Map.entry(
              COL_WORKFLOW_VERSION,
              requiredColumn("工作流版本，必须与 workflow_definition.version 一致。", GUIDE_INT, "1")),
          Map.entry("from_node_code", requiredColumn("依赖关系中的上游节点编码。", GUIDE_STR, "LOAD_SOURCE")),
          Map.entry("to_node_code", requiredColumn("依赖关系中的下游节点编码。", GUIDE_STR, "VALIDATE_FILE")),
          Map.entry(
              COL_EDGE_TYPE,
              requiredColumn(
                  "两个节点之间的流转类型。",
                  "枚举",
                  EDGE_SUCCESS,
                  EDGE_SUCCESS,
                  "FAILURE",
                  "CONDITION",
                  "ALWAYS")),
          Map.entry(
              "condition_expr",
              optionalColumn("当 edge_type 为 CONDITION 时填写条件表达式。", "表达式", "${fileReady == true}")),
          Map.entry(
              COL_ENABLED,
              optionalColumn("该依赖边是否启用。", "布尔值", GUIDE_TRUE, GUIDE_TRUE, GUIDE_FALSE)));

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
