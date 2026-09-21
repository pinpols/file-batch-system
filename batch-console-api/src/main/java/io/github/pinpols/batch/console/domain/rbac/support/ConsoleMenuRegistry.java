package io.github.pinpols.batch.console.domain.rbac.support;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleMenuProperties;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 控制台侧边栏菜单注册表（与前端 navigation.ts / pageMeta 对齐）。
 *
 * <p>菜单内容已外置到 {@code menu.yml}（见 {@link ConsoleMenuProperties}），本类只负责:启动期把配置转成 对外 {@link MenuGroup}
 * record + 按角色过滤。改菜单 = 改 yml + 重启,不再动本类 Java 代码。
 *
 * <p>菜单项优先使用 {@code authorities} 做精确权限匹配；未配置时才回退到 {@code minRole} 的 3 档等级:
 *
 * <ul>
 *   <li>{@code ROLE_ADMIN} → ADMIN(看全部)
 *   <li>{@code ROLE_TENANT_ADMIN} → TENANT_ADMIN(本租户配置 + 业务)
 *   <li>{@code ROLE_AUDITOR} / {@code ROLE_TENANT_USER} / {@code ROLE_USER} → VIEWER(只读)
 * </ul>
 *
 * <p>分组默认由过滤后仍有可见子项决定是否展示，避免分组等级误伤非单调角色（例如 AUDITOR）。只有分组显式配置
 * {@code authorities} 时才整体限制。它同时是路由 allowlist 源(FE 路由守卫 hasBackendMenuAccess 依赖);未知/缺失 minRole 按
 * fail-secure 当 ADMIN 处理(只对 admin 可见),避免 yml 笔误误放开低权角色。
 */
@Component
public class ConsoleMenuRegistry {

  // 菜单项 minRole 字段值;层级:VIEWER < TENANT_ADMIN < ADMIN。
  private static final String ROLE_VIEWER = "VIEWER";
  private static final String ROLE_OPERATOR = "TENANT_ADMIN";
  private static final String ROLE_ADMIN = "ADMIN";

  public record MenuItem(String title, String path, String icon, String minRole) {}

  public record MenuGroup(
      String key, String title, String icon, String minRole, List<MenuItem> children) {}

  private final List<MenuGroup> allGroups;
  private final Map<String, Set<String>> groupAuthorities;
  private final Map<String, Set<String>> itemAuthorities;

  public ConsoleMenuRegistry(ConsoleMenuProperties props) {
    List<MenuGroup> groups = new ArrayList<>();
    Map<String, Set<String>> configuredGroupAuthorities = new HashMap<>();
    Map<String, Set<String>> configuredItemAuthorities = new HashMap<>();
    for (ConsoleMenuProperties.GroupDef g : props.getGroups()) {
      configuredGroupAuthorities.put(g.getKey(), normalizeAuthorities(g.getAuthorities()));
      List<MenuItem> items = new ArrayList<>();
      for (ConsoleMenuProperties.ItemDef i : g.getChildren()) {
        configuredItemAuthorities.put(i.getPath(), normalizeAuthorities(i.getAuthorities()));
        items.add(
            new MenuItem(i.getTitle(), i.getPath(), i.getIcon(), normalizeRole(i.getMinRole())));
      }
      groups.add(new MenuGroup(
          g.getKey(),
          g.getTitle(),
          g.getIcon(),
          normalizeRole(g.getMinRole()),
          List.copyOf(items)));
    }
    this.allGroups = List.copyOf(groups);
    this.groupAuthorities = Map.copyOf(configuredGroupAuthorities);
    this.itemAuthorities = Map.copyOf(configuredItemAuthorities);
  }

  /** 根据用户 authorities 过滤可见菜单。 */
  public List<MenuGroup> filterByAuthorities(Set<String> authorities) {
    int roleLevel = roleLevel(resolveRole(authorities));
    List<MenuGroup> result = new ArrayList<>();
    for (MenuGroup group : allGroups) {
      Set<String> requiredGroupAuthorities = groupAuthorities.get(group.key());
      if (EmptyChecks.isNotEmpty(requiredGroupAuthorities)
          && !hasAnyAuthority(authorities, requiredGroupAuthorities)) {
        continue;
      }
      List<MenuItem> visibleItems = group.children().stream()
          .filter(item ->
              isAllowed(authorities, roleLevel, item.minRole(), itemAuthorities.get(item.path())))
          .toList();
      if (EmptyChecks.isNotEmpty(visibleItems)) {
        result.add(
            new MenuGroup(group.key(), group.title(), group.icon(), group.minRole(), visibleItems));
      }
    }
    return result;
  }

  private static String normalizeRole(String role) {
    return EmptyChecks.isNull(role) ? ROLE_ADMIN : role.trim().toUpperCase(Locale.ROOT);
  }

  private static Set<String> normalizeAuthorities(List<String> authorities) {
    if (EmptyChecks.isEmpty(authorities)) {
      return Set.of();
    }
    return authorities.stream()
        .filter(EmptyChecks::isNotBlank)
        .map(authority -> authority.trim().toUpperCase(Locale.ROOT))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static boolean isAllowed(
      Set<String> userAuthorities, int roleLevel, String minRole, Set<String> requiredAuthorities) {
    if (EmptyChecks.isNotEmpty(requiredAuthorities)) {
      return hasAnyAuthority(userAuthorities, requiredAuthorities);
    }
    return roleLevel >= roleLevel(minRole);
  }

  private static boolean hasAnyAuthority(
      Set<String> userAuthorities, Set<String> requiredAuthorities) {
    return userAuthorities.stream()
        .map(authority -> authority.toUpperCase(Locale.ROOT))
        .anyMatch(requiredAuthorities::contains);
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
      case ROLE_VIEWER -> 0;
      // 未知 minRole(yml 笔误)fail-secure:按 ADMIN 处理,只对 admin 可见,不误放开低权角色。
      default -> 2;
    };
  }
}
