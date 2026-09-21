package io.github.pinpols.batch.console.domain.rbac.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.console.config.ConsoleMenuProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConsoleMenuRegistryTest {

  @Test
  void shouldUseExplicitAuthorities_whenRoleHierarchyCannotExpressAccess() {
    ConsoleMenuRegistry registry = registryWithItem("ADMIN", List.of("ROLE_TENANT_USER"), "VIEWER");

    assertThat(registry.filterByAuthorities(Set.of("ROLE_TENANT_USER")))
        .flatExtracting(ConsoleMenuRegistry.MenuGroup::children)
        .extracting(ConsoleMenuRegistry.MenuItem::path)
        .containsExactly("/self-service");
    assertThat(registry.filterByAuthorities(Set.of("ROLE_AUDITOR"))).isEmpty();
  }

  @Test
  void shouldKeepViewerChildren_whenGroupIsVisibleToViewer() {
    ConsoleMenuRegistry registry = registryWithItem("VIEWER", List.of(), "VIEWER");

    assertThat(registry.filterByAuthorities(Set.of("ROLE_AUDITOR")))
        .singleElement()
        .satisfies(group -> assertThat(group.children())
            .extracting(ConsoleMenuRegistry.MenuItem::path)
            .containsExactly("/self-service"));
  }

  @Test
  void shouldFallBackToMinRole_whenAuthoritiesAreNotConfigured() {
    ConsoleMenuRegistry registry = registryWithItem("VIEWER", List.of(), "TENANT_ADMIN");

    assertThat(registry.filterByAuthorities(Set.of("ROLE_TENANT_USER"))).isEmpty();
    assertThat(registry.filterByAuthorities(Set.of("ROLE_TENANT_ADMIN")))
        .flatExtracting(ConsoleMenuRegistry.MenuGroup::children)
        .extracting(ConsoleMenuRegistry.MenuItem::path)
        .containsExactly("/self-service");
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
