package com.example.batch.console.domain.rbac.support;

import com.example.batch.console.config.ConsoleMenuProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 控制台侧边栏菜单注册表（与前端 navigation.ts / pageMeta 对齐）。
 *
 * <p>菜单内容已外置到 {@code menu.yml}（见 {@link ConsoleMenuProperties}），本类只负责:启动期把配置转成 对外 {@link MenuGroup}
 * record + 按角色过滤。改菜单 = 改 yml + 重启,不再动本类 Java 代码。
 *
 * <p>菜单可见性由 {@code minRole} 控制,4 角色 → 菜单等级映射:
 *
 * <ul>
 *   <li>{@code ROLE_ADMIN} → ADMIN(看全部)
 *   <li>{@code ROLE_TENANT_ADMIN} → TENANT_ADMIN(本租户配置 + 业务)
 *   <li>{@code ROLE_AUDITOR} / {@code ROLE_TENANT_USER} / {@code ROLE_USER} → VIEWER(只读)
 * </ul>
 *
 * <p>它同时是路由 allowlist 源(FE 路由守卫 hasBackendMenuAccess 依赖);未知/缺失 minRole 按 fail-secure 当 ADMIN 处理(只对
 * admin 可见),避免 yml 笔误误放开低权角色。
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

  public ConsoleMenuRegistry(ConsoleMenuProperties props) {
    List<MenuGroup> groups = new ArrayList<>();
    for (ConsoleMenuProperties.GroupDef g : props.getGroups()) {
      List<MenuItem> items = new ArrayList<>();
      for (ConsoleMenuProperties.ItemDef i : g.getChildren()) {
        items.add(
            new MenuItem(i.getTitle(), i.getPath(), i.getIcon(), normalizeRole(i.getMinRole())));
      }
      groups.add(
          new MenuGroup(
              g.getKey(),
              g.getTitle(),
              g.getIcon(),
              normalizeRole(g.getMinRole()),
              List.copyOf(items)));
    }
    this.allGroups = List.copyOf(groups);
  }

  /** 根据用户 authorities 过滤可见菜单。 */
  public List<MenuGroup> filterByAuthorities(Set<String> authorities) {
    int roleLevel = roleLevel(resolveRole(authorities));
    List<MenuGroup> result = new ArrayList<>();
    for (MenuGroup group : allGroups) {
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

  private static String normalizeRole(String role) {
    return role == null ? ROLE_ADMIN : role.trim().toUpperCase();
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
