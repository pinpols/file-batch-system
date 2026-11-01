package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.console.config.ConsoleMenuProperties;
import java.util.List;
import java.util.Set;
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
