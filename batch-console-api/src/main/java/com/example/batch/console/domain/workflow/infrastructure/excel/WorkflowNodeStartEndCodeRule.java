package com.example.batch.console.domain.workflow.infrastructure.excel;

import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import com.example.batch.console.support.excel.ConsoleExcelPreviewWorkbookSupport.WorkbookIssue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A4 fixture 工程化校验规则:每个 (tenant_id, workflow_code, workflow_version) 在 workflow_node sheet 内必须含且仅含
 * 1 个 node_type=START 的节点 node_code='START',END 同理。
 *
 * <p>背景:sim-e2e 第 1 波发现部分 fixture 用 NODE_START / NODE_END 命名,与 worker 侧 DAG runner 边界节点常量不匹配,导致
 * workflow_run 启动后找不到入口。新规则放在 fixture 入口拦截,避免 orchestrator 启动期 fail。
 *
 * <p>纯函数,无 Spring 依赖,便于在 ConfigPackageExcelValidator 调用点或独立 lint 命令复用。
 */
public final class WorkflowNodeStartEndCodeRule {

  public static final String SHEET = ConfigPackageExcelValidator.WF_NODE_SHEET;
  public static final String NODE_TYPE_START = "START";
  public static final String NODE_TYPE_END = "END";

  private WorkflowNodeStartEndCodeRule() {}

  /**
   * 按 (tenant_id, workflow_code, workflow_version) 分组核对 START / END 边界节点。
   *
   * @param rows 已 normalize 的 workflow_node 行(含 __excel_row_no)
   * @return 不通过场景的 issue 列表(空列表表示通过)
   */
  public static List<WorkbookIssue> validate(List<Map<String, Object>> rows) {
    List<WorkbookIssue> issues = new ArrayList<>();
    if (rows == null || rows.isEmpty()) {
      return issues;
    }

    Map<String, GroupStat> grouped = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      String tenant = asString(row.get(ConfigPackageExcelValidator.COL_TENANT_ID));
      String wfCode = asString(row.get(ConfigPackageExcelValidator.COL_WORKFLOW_CODE));
      String wfVer = asString(row.get(ConfigPackageExcelValidator.COL_WORKFLOW_VERSION));
      if (isBlank(tenant) || isBlank(wfCode)) {
        continue;
      }
      String key = tenant + "::" + wfCode + "::" + (wfVer == null ? "" : wfVer);
      GroupStat stat = grouped.computeIfAbsent(key, k -> new GroupStat(tenant, wfCode, wfVer));

      String nodeCode = asString(row.get(ConfigPackageExcelValidator.COL_NODE_CODE));
      String nodeType = upper(asString(row.get(ConfigPackageExcelValidator.COL_NODE_TYPE)));
      int rowNo = parseRowNo(row.get("__excel_row_no"));

      if (NODE_TYPE_START.equals(nodeType)) {
        stat.startCount++;
        if (!NODE_TYPE_START.equals(nodeCode)) {
          issues.add(
              new WorkbookIssue(
                  SHEET,
                  rowNo,
                  ConfigPackageExcelValidator.COL_NODE_CODE,
                  "workflow_node node_type=START must use node_code='START' (found '"
                      + nodeCode
                      + "') for workflow "
                      + wfCode));
        }
      } else if (NODE_TYPE_END.equals(nodeType)) {
        stat.endCount++;
        if (!NODE_TYPE_END.equals(nodeCode)) {
          issues.add(
              new WorkbookIssue(
                  SHEET,
                  rowNo,
                  ConfigPackageExcelValidator.COL_NODE_CODE,
                  "workflow_node node_type=END must use node_code='END' (found '"
                      + nodeCode
                      + "') for workflow "
                      + wfCode));
        }
      } else if (NODE_TYPE_START.equals(nodeCode) || NODE_TYPE_END.equals(nodeCode)) {
        // node_code 写了 START/END,但 node_type 没对齐
        issues.add(
            new WorkbookIssue(
                SHEET,
                rowNo,
                ConfigPackageExcelValidator.COL_NODE_TYPE,
                "workflow_node node_code='"
                    + nodeCode
                    + "' must have node_type='"
                    + nodeCode
                    + "' (found '"
                    + nodeType
                    + "')"));
      }
    }

    for (GroupStat stat : grouped.values()) {
      if (stat.startCount != 1) {
        issues.add(
            new WorkbookIssue(
                SHEET,
                0,
                ConfigPackageExcelValidator.COL_NODE_TYPE,
                "workflow "
                    + stat.workflowCode
                    + " (tenant="
                    + stat.tenantId
                    + ", version="
                    + stat.workflowVersion
                    + ") must have exactly 1 START node, found "
                    + stat.startCount));
      }
      if (stat.endCount != 1) {
        issues.add(
            new WorkbookIssue(
                SHEET,
                0,
                ConfigPackageExcelValidator.COL_NODE_TYPE,
                "workflow "
                    + stat.workflowCode
                    + " (tenant="
                    + stat.tenantId
                    + ", version="
                    + stat.workflowVersion
                    + ") must have exactly 1 END node, found "
                    + stat.endCount));
      }
    }
    return issues;
  }

  private static String asString(Object o) {
    return o == null ? null : String.valueOf(o);
  }

  private static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static int parseRowNo(Object o) {
    if (o == null) {
      return 0;
    }
    if (o instanceof Number n) {
      return n.intValue();
    }
    try {
      return Integer.parseInt(o.toString());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static final class GroupStat {
    final String tenantId;
    final String workflowCode;
    final String workflowVersion;
    int startCount;
    int endCount;

    GroupStat(String tenantId, String workflowCode, String workflowVersion) {
      this.tenantId = Objects.requireNonNullElse(tenantId, "");
      this.workflowCode = Objects.requireNonNullElse(workflowCode, "");
      this.workflowVersion = Objects.requireNonNullElse(workflowVersion, "");
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof GroupStat other)) return false;
      return tenantId.equals(other.tenantId)
          && workflowCode.equals(other.workflowCode)
          && workflowVersion.equals(other.workflowVersion);
    }

    @Override
    public int hashCode() {
      return tenantId.hashCode() * 31 + workflowCode.hashCode();
    }

    Map<String, Object> debug() {
      Map<String, Object> m = new HashMap<>();
      m.put("tenantId", tenantId);
      m.put("workflowCode", workflowCode);
      m.put("workflowVersion", workflowVersion);
      m.put("startCount", startCount);
      m.put("endCount", endCount);
      return m;
    }
  }
}
