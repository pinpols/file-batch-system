package com.example.batch.console.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 控制台侧边栏菜单注册表(静态配置,与前端 navigation.ts 对齐)。
 *
 * <p>菜单可见性由 {@code minRole} 控制,4 角色 → 菜单等级映射:
 *
 * <ul>
 *   <li>{@code ROLE_ADMIN} → ADMIN(看全部)
 *   <li>{@code ROLE_TENANT_ADMIN} → TENANT_ADMIN(本租户配置 + 业务)
 *   <li>{@code ROLE_AUDITOR} / {@code ROLE_TENANT_USER} / {@code ROLE_USER} → VIEWER(只读)
 * </ul>
 *
 * <p>历史 {@code ROLE_CONFIG_ADMIN} 已升级为 ADMIN(V149)。
 */
public final class ConsoleMenuRegistry {

  // 菜单项 minRole 字段值;层级:VIEWER < TENANT_ADMIN < ADMIN。
  private static final String ROLE_VIEWER = "VIEWER";
  private static final String ROLE_OPERATOR = "TENANT_ADMIN";
  private static final String ROLE_ADMIN = "ADMIN";

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
              ROLE_VIEWER,
              List.of(
                  new MenuItem("控制面板快照", "/ops/summary", "TrendCharts", ROLE_VIEWER),
                  new MenuItem("审批中心", "/approvals", "Stamp", ROLE_OPERATOR),
                  new MenuItem("导出中心", "/reports", "Download", ROLE_VIEWER),
                  new MenuItem("自助服务", "/self-service", "Tickets", ROLE_OPERATOR),
                  new MenuItem("运维诊断", "/ops/diagnostic", "Tools", ROLE_ADMIN))),
          new MenuGroup(
              "config",
              "配置与发布",
              "Operation",
              ROLE_OPERATOR,
              List.of(
                  new MenuItem("配置发布", "/config/releases", "DocumentChecked", ROLE_OPERATOR),
                  new MenuItem("Excel 维护", "/config/excel", "List", ROLE_OPERATOR),
                  new MenuItem("合并导入", "/config/tenant-package", "Box", ROLE_OPERATOR),
                  new MenuItem("配置管理", "/config/management", "Memo", ROLE_OPERATOR),
                  new MenuItem("标签管理", "/system/tags", "PriceTag", ROLE_OPERATOR))),
          new MenuGroup(
              "files",
              "文件中心",
              "FolderOpened",
              ROLE_VIEWER,
              List.of(
                  new MenuItem("文件列表", "/files/list", "Files", ROLE_VIEWER),
                  new MenuItem("文件模板", "/files/templates", "Document", ROLE_VIEWER),
                  new MenuItem("文件组到达治理", "/files/arrival-groups", "CollectionTag", ROLE_VIEWER),
                  new MenuItem("流水线观测", "/files/pipeline-obs", "DataAnalysis", ROLE_VIEWER))),
          new MenuGroup(
              "jobs",
              "任务管理",
              "Management",
              ROLE_VIEWER,
              List.of(
                  new MenuItem("Job 定义", "/jobs/definitions", "List", ROLE_VIEWER),
                  new MenuItem("Workflow 定义", "/workflow/definitions", "Collection", ROLE_VIEWER),
                  new MenuItem("Pipeline 定义", "/jobs/pipelines", "Share", ROLE_VIEWER),
                  new MenuItem("Workflow 编排", "/workflow/designer", "Guide", ROLE_OPERATOR))),
          new MenuGroup(
              "monitor",
              "执行与观测",
              "Monitor",
              ROLE_VIEWER,
              List.of(
                  new MenuItem("Job Instance", "/monitor/job-instances", "Monitor", ROLE_VIEWER),
                  new MenuItem("Job Step Instance", "/monitor/job-steps", "Timer", ROLE_VIEWER),
                  new MenuItem("Workflow Run", "/monitor/workflow-runs", "Promotion", ROLE_VIEWER),
                  new MenuItem("日志", "/logs", "Reading", ROLE_VIEWER),
                  new MenuItem("告警", "/observability/alerts", "WarningFilled", ROLE_VIEWER),
                  new MenuItem("审计", "/observability/audits", "Memo", ROLE_VIEWER),
                  new MenuItem("Outbox", "/observability/outbox", "Box", ROLE_OPERATOR),
                  new MenuItem("可观测性查询", "/observability/queries", "Search", ROLE_VIEWER),
                  new MenuItem("事件目录", "/system/event-catalog", "Collection", ROLE_VIEWER))),
          new MenuGroup(
              "scheduler",
              "调度与治理",
              "DataAnalysis",
              ROLE_VIEWER,
              List.of(
                  new MenuItem("调度快照", "/scheduler/snapshot", "TrendCharts", ROLE_VIEWER),
                  new MenuItem("批次日与窗口", "/scheduler/batch-days", "Calendar", ROLE_VIEWER),
                  new MenuItem("Catch-up 审批", "/scheduler/catch-up-approvals", "Memo", ROLE_VIEWER),
                  new MenuItem("租户配额", "/governance/quota", "Briefcase", ROLE_OPERATOR),
                  // 队列/窗口/日历是租户级配置 CRUD,与「配置发布」「Excel 维护」同档,
                  // 让 TENANT_ADMIN 可见;真正影响平台的"运维操作"在 /ops/diagnostic(保 ADMIN)。
                  new MenuItem("队列 & 窗口", "/governance/queues", "Tools", ROLE_OPERATOR),
                  new MenuItem("Worker 管理", "/workers/management", "Cpu", ROLE_OPERATOR),
                  new MenuItem("Trigger 管理", "/system/triggers", "Timer", ROLE_OPERATOR))),
          new MenuGroup(
              "system",
              "系统",
              "Setting",
              ROLE_OPERATOR,
              List.of(
                  new MenuItem("租户管理", "/system/tenants", "Briefcase", ROLE_OPERATOR),
                  new MenuItem("用户账户", "/system/user-accounts", "Tickets", ROLE_ADMIN),
                  new MenuItem("当前登录态", "/system/users", "Tickets", ROLE_ADMIN),
                  new MenuItem("AI 助手", "/system/ai-chat", "Document", ROLE_ADMIN),
                  new MenuItem("API Key", "/system/api-keys", "Key", ROLE_ADMIN),
                  new MenuItem("系统参数", "/system/parameters", "Setting", ROLE_ADMIN),
                  new MenuItem("通知与投递", "/system/notifications", "Bell", ROLE_OPERATOR))));

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
      return ROLE_ADMIN;
    }
    if (authorities.contains("ROLE_TENANT_ADMIN")) {
      return ROLE_OPERATOR;
    }
    return ROLE_VIEWER;
  }

  private static int roleLevel(String role) {
    return switch (role) {
      case ROLE_ADMIN -> 2;
      case ROLE_OPERATOR -> 1;
      default -> 0;
    };
  }
}
