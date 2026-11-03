package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.console.config.ConsoleMenuProperties;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

@DisplayName("控制台菜单注册:按角色过滤可见菜单, 未配置授权时回退最小角色, 历史角色配置直接拒绝")
class ConsoleMenuRegistryTest {

  @Test
  @DisplayName("显式授权:角色层级无法表达时按授权集合过滤, 无授权角色看不到条目")
  void shouldUseExplicitAuthorities_whenRoleHierarchyCannotExpressAccess() {
    ConsoleMenuRegistry registry = registryWithItem("ADMIN", List.of("ROLE_TENANT_USER"), "VIEWER");

    assertThat(registry.filterByAuthorities(Set.of("ROLE_TENANT_USER")))
        .flatExtracting(ConsoleMenuRegistry.MenuGroup::children)
        .extracting(ConsoleMenuRegistry.MenuItem::path)
        .containsExactly("/self-service");
    assertThat(registry.filterByAuthorities(Set.of("ROLE_AUDITOR"))).isEmpty();
  }

  @Test
  @DisplayName("查看者可见:分组对查看者开放时子条目保留")
  void shouldKeepViewerChildren_whenGroupIsVisibleToViewer() {
    ConsoleMenuRegistry registry = registryWithItem("VIEWER", List.of(), "VIEWER");

    assertThat(registry.filterByAuthorities(Set.of("ROLE_AUDITOR")))
        .singleElement()
        .satisfies(group -> assertThat(group.children())
            .extracting(ConsoleMenuRegistry.MenuItem::path)
            .containsExactly("/self-service"));
  }

  @Test
  @DisplayName("回退最小角色:未配置授权时按最小角色判定可见性")
  void shouldFallBackToMinRole_whenAuthoritiesAreNotConfigured() {
    ConsoleMenuRegistry registry = registryWithItem("VIEWER", List.of(), "TENANT_ADMIN");

    assertThat(registry.filterByAuthorities(Set.of("ROLE_TENANT_USER"))).isEmpty();
    assertThat(registry.filterByAuthorities(Set.of("ROLE_TENANT_ADMIN")))
        .flatExtracting(ConsoleMenuRegistry.MenuGroup::children)
        .extracting(ConsoleMenuRegistry.MenuItem::path)
        .containsExactly("/self-service");
  }

  @Test
  @DisplayName("历史角色:菜单授权含旧角色时构造即抛出参数异常")
  void shouldRejectLegacyRoleInMenuAuthorities() {
    assertThatThrownBy(() -> registryWithItem("VIEWER", List.of("ROLE_USER"), "VIEWER"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsupported console roles");
  }

  @Test
  @DisplayName("默认菜单与接口权限对齐：队列和窗口仅管理员可见，移除不存在的流水线定义入口")
  void shouldAlignDefaultMenuQueueAndWindowWithControllerRoles() throws Exception {
    var environment = new StandardEnvironment();
    var sources = new YamlPropertySourceLoader().load("menu", new ClassPathResource("menu.yml"));
    sources.forEach(environment.getPropertySources()::addLast);
    ConsoleMenuProperties properties = Binder.get(environment)
        .bind("batch.console.menu", Bindable.of(ConsoleMenuProperties.class))
        .get();
    ConsoleMenuRegistry registry = new ConsoleMenuRegistry(properties);

    for (String role : List.of("ROLE_TENANT_ADMIN", "ROLE_TENANT_USER", "ROLE_AUDITOR")) {
      assertThat(registry.filterByAuthorities(Set.of(role)))
          .flatExtracting(ConsoleMenuRegistry.MenuGroup::children)
          .extracting(ConsoleMenuRegistry.MenuItem::path)
          .doesNotContain("/governance/queues", "/governance/windows", "/jobs/pipelines");
    }
    assertThat(registry.filterByAuthorities(Set.of("ROLE_ADMIN")))
        .flatExtracting(ConsoleMenuRegistry.MenuGroup::children)
        .extracting(ConsoleMenuRegistry.MenuItem::path)
        .contains("/governance/queues", "/governance/windows")
        .doesNotContain("/jobs/pipelines");
  }

  @Test
  @DisplayName("默认菜单名称与前端展示对齐，角色过滤仍基于现有授权")
  void shouldExposeAlignedMenuTitles_whenAdminLoadsDefaultMenu() throws Exception {
    var environment = new StandardEnvironment();
    var sources = new YamlPropertySourceLoader().load("menu", new ClassPathResource("menu.yml"));
    sources.forEach(environment.getPropertySources()::addLast);
    ConsoleMenuProperties properties = Binder.get(environment)
        .bind("batch.console.menu", Bindable.of(ConsoleMenuProperties.class))
        .get();
    var groups = new ConsoleMenuRegistry(properties).filterByAuthorities(Set.of("ROLE_ADMIN"));
    Map<String, String> groupTitles = groups.stream()
        .collect(Collectors.toMap(
            ConsoleMenuRegistry.MenuGroup::key, ConsoleMenuRegistry.MenuGroup::title));
    Map<String, String> itemTitles = groups.stream()
        .flatMap(group -> group.children().stream())
        .collect(Collectors.toMap(
            ConsoleMenuRegistry.MenuItem::path, ConsoleMenuRegistry.MenuItem::title));

    Map<String, String> expectedGroupTitles = Map.of(
        "alerting",
        "告警投递",
        "definitions",
        "准备作业",
        "files",
        "文件管理",
        "scheduling",
        "调度资源",
        "system",
        "平台管理");
    Map<String, String> expectedItemTitles = Map.ofEntries(
        Map.entry("/ops/summary", "控制面板"),
        Map.entry("/reports", "报表中心"),
        Map.entry("/ops/asset-freshness", "新鲜度策略"),
        Map.entry("/monitor/job-instances", "作业运行"),
        Map.entry("/monitor/job-steps", "作业步骤"),
        Map.entry("/monitor/workflow-runs", "工作流运行"),
        Map.entry("/observability/trace", "链路诊断"),
        Map.entry("/observability/alerts", "告警事件"),
        Map.entry("/observability/job-monitoring-policies", "监控策略"),
        Map.entry("/observability/alert-routings", "告警路由（预留）"),
        Map.entry("/system/notifications", "通知投递"),
        Map.entry("/jobs/definitions", "作业定义"),
        Map.entry("/workflow/definitions", "工作流定义"),
        Map.entry("/workflow/designer", "编排设计器"),
        Map.entry("/config/tenant-package", "配置导入"),
        Map.entry("/files/arrival-groups", "到达组治理"),
        Map.entry("/system/triggers", "触发器"),
        Map.entry("/governance/queues", "资源队列"),
        Map.entry("/system/tenants", "租户实例"),
        Map.entry("/system/user-accounts", "登录账户"),
        Map.entry("/config/releases", "发布管理"),
        Map.entry("/config/management", "变更与同步"),
        Map.entry("/observability/audits", "审计日志"));
    assertThat(groupTitles).containsAllEntriesOf(expectedGroupTitles);
    assertThat(itemTitles).containsAllEntriesOf(expectedItemTitles);
  }

  private static ConsoleMenuRegistry registryWithItem(
      String groupMinRole, List<String> authorities, String itemMinRole) {
    ConsoleMenuProperties.ItemDef item = new ConsoleMenuProperties.ItemDef();
    item.setTitle("Self service");
    item.setPath("/self-service");
    item.setIcon("Tickets");
    item.setMinRole(itemMinRole);
    item.setAuthorities(authorities);

    ConsoleMenuProperties.GroupDef group = new ConsoleMenuProperties.GroupDef();
    group.setKey("workspace");
    group.setTitle("Workspace");
    group.setIcon("Histogram");
    group.setMinRole(groupMinRole);
    group.setChildren(List.of(item));

    ConsoleMenuProperties properties = new ConsoleMenuProperties();
    properties.setGroups(List.of(group));
    return new ConsoleMenuRegistry(properties);
  }
}
