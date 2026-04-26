package com.example.batch.console.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 控制台侧边栏菜单注册表（静态配置，与前端 navigation.ts 对齐）。
 *
 * <p>菜单可见性由 {@code minRole} 控制，角色等级：VIEWER &lt; OPERATOR &lt; ADMIN。 后端 authorities 映射规则：
 *
 * <ul>
 *   <li>ROLE_ADMIN → ADMIN
 *   <li>ROLE_CONFIG_ADMIN → OPERATOR
 *   <li>ROLE_AUDITOR / ROLE_TENANT_USER / ROLE_USER → VIEWER
 * </ul>
 */
public final class ConsoleMenuRegistry {

  private ConsoleMenuRegistry() {}

  public record MenuItem(String title, String path, String icon, String minRole) {}

  public record MenuGroup(
      String key, String title, String icon, String minRole, List<MenuItem> children) {}

  private static final List<MenuGroup> ALL_GROUPS =
      List.of(
          new MenuGroup(
              "ops",
              "运营概览",
              "Histogram",
              "VIEWER",
              List.of(
                  new MenuItem("控制面板快照", "/ops/summary", "TrendCharts", "VIEWER"),
                  new MenuItem("审批中心", "/approvals", "Stamp", "OPERATOR"),
                  new MenuItem("导出中心", "/reports", "Download", "VIEWER"),
                  new MenuItem("自助服务", "/self-service", "Tickets", "OPERATOR"),
                  new MenuItem("运维诊断", "/ops/diagnostic", "Tools", "ADMIN"))),
          new MenuGroup(
              "config",
              "配置与发布",
              "Operation",
              "OPERATOR",
              List.of(
                  new MenuItem("配置发布", "/config/releases", "DocumentChecked", "OPERATOR"),
                  new MenuItem("Excel 维护", "/config/excel", "List", "OPERATOR"),
                  new MenuItem("合并导入", "/config/tenant-package", "Box", "OPERATOR"),
                  new MenuItem("配置管理", "/config/management", "Memo", "OPERATOR"),
                  new MenuItem("标签管理", "/system/tags", "PriceTag", "OPERATOR"))),
          new MenuGroup(
              "files",
              "文件中心",
              "FolderOpened",
              "VIEWER",
              List.of(
                  new MenuItem("文件列表", "/files/list", "Files", "VIEWER"),
                  new MenuItem("文件模板", "/files/templates", "Document", "VIEWER"),
                  new MenuItem("文件组到达治理", "/files/arrival-groups", "CollectionTag", "VIEWER"),
                  new MenuItem("流水线观测", "/files/pipeline-obs", "DataAnalysis", "VIEWER"))),
          new MenuGroup(
              "jobs",
              "任务管理",
              "Management",
              "VIEWER",
              List.of(
                  new MenuItem("Job 定义", "/jobs/definitions", "List", "VIEWER"),
                  new MenuItem("Workflow 定义", "/workflow/definitions", "Collection", "VIEWER"),
                  new MenuItem("Pipeline 定义", "/jobs/pipelines", "Share", "VIEWER"),
                  new MenuItem("Workflow 编排", "/workflow/designer", "Guide", "OPERATOR"))),
          new MenuGroup(
              "monitor",
              "执行与观测",
              "Monitor",
              "VIEWER",
              List.of(
                  new MenuItem("Job Instance", "/monitor/job-instances", "Monitor", "VIEWER"),
                  new MenuItem("Job Step Instance", "/monitor/job-steps", "Timer", "VIEWER"),
                  new MenuItem("Workflow Run", "/monitor/workflow-runs", "Promotion", "VIEWER"),
                  new MenuItem("日志", "/logs", "Reading", "VIEWER"),
                  new MenuItem("告警", "/observability/alerts", "WarningFilled", "VIEWER"),
                  new MenuItem("审计", "/observability/audits", "Memo", "VIEWER"),
                  new MenuItem("Outbox", "/observability/outbox", "Box", "OPERATOR"),
                  new MenuItem("可观测性查询", "/observability/queries", "Search", "VIEWER"),
                  new MenuItem("事件目录", "/system/event-catalog", "Collection", "VIEWER"))),
          new MenuGroup(
              "scheduler",
              "调度与治理",
              "DataAnalysis",
              "VIEWER",
              List.of(
                  new MenuItem("调度快照", "/scheduler/snapshot", "TrendCharts", "VIEWER"),
                  new MenuItem("批次日与窗口", "/scheduler/batch-days", "Calendar", "VIEWER"),
                  new MenuItem("Catch-up 审批", "/scheduler/catch-up-approvals", "Memo", "VIEWER"),
                  new MenuItem("租户配额", "/governance/quota", "Briefcase", "OPERATOR"),
                  new MenuItem("队列 & 窗口", "/governance/queues", "Tools", "ADMIN"),
                  new MenuItem("Worker 管理", "/workers/management", "Cpu", "OPERATOR"),
                  new MenuItem("Trigger 管理", "/system/triggers", "Timer", "OPERATOR"))),
          new MenuGroup(
              "system",
              "系统",
              "Setting",
              "OPERATOR",
              List.of(
                  new MenuItem("租户管理", "/system/tenants", "Briefcase", "OPERATOR"),
                  new MenuItem("用户账户", "/system/user-accounts", "Tickets", "ADMIN"),
                  new MenuItem("当前登录态", "/system/users", "Tickets", "ADMIN"),
                  new MenuItem("AI 助手", "/system/ai-chat", "Document", "ADMIN"),
                  new MenuItem("API Key", "/system/api-keys", "Key", "ADMIN"),
                  new MenuItem("系统参数", "/system/parameters", "Setting", "ADMIN"),
                  new MenuItem("通知与投递", "/system/notifications", "Bell", "OPERATOR"))));

  /** 根据用户 authorities 过滤可见菜单。 */
  public static List<MenuGroup> filterByAuthorities(Set<String> authorities) {
    String effectiveRole = resolveRole(authorities);
    int roleLevel = roleLevel(effectiveRole);
    List<MenuGroup> result = new ArrayList<>();
    for (MenuGroup group : ALL_GROUPS) {
      if (roleLevel < roleLevel(group.minRole())) {
        continue;
      }
      List<MenuItem> visibleItems =
          group.children().stream().filter(item -> roleLevel >= roleLevel(item.minRole())).toList();
      if (!visibleItems.isEmpty()) {
        result.add(
            new MenuGroup(group.key(), group.title(), group.icon(), group.minRole(), visibleItems));
      }
    }
    return result;
  }

  private static String resolveRole(Set<String> authorities) {
    if (authorities.contains("ROLE_ADMIN")) {
      return "ADMIN";
    }
    if (authorities.contains("ROLE_CONFIG_ADMIN")) {
      return "OPERATOR";
    }
    return "VIEWER";
  }

  private static int roleLevel(String role) {
    return switch (role) {
      case "ADMIN" -> 2;
      case "OPERATOR" -> 1;
      default -> 0;
    };
  }
}
