package com.example.batch.console.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制台侧边栏菜单配置（{@code batch.console.menu}）——外置于 {@code menu.yml}。
 *
 * <p>设计:菜单结构/文案/图标/顺序/分组/minRole 从此处加载,不再写死在 {@link
 * com.example.batch.console.domain.rbac.support.ConsoleMenuRegistry} 的 Java 常量里。改菜单 = 改 yml +
 * 重启(不重编译、不走 DB)。加载优先级(后者覆盖前者,见 application.yml 的 spring.config.import):
 *
 * <ol>
 *   <li>{@code classpath:menu.yml} —— 版本控制的默认菜单(随 jar 发布)
 *   <li>{@code optional:file:${BATCH_CONSOLE_MENU_FILE:config/menu.yml}} —— 部署侧外置覆盖(运维可改,免重编译)
 * </ol>
 *
 * <p>注意:菜单只对「已存在的前端路由」挂入口 + 做访问 allowlist;**配置化不能新增页面**(页面/路由仍在 FE 编译期)。minRole 仍是安全收口源(路由守卫
 * hasBackendMenuAccess 依赖之),改 yml 时勿越权放开。
 *
 * <p>用可变 JavaBean(@Data)承接绑定(比 record 绑定更稳),由 ConsoleMenuRegistry 转换为对外 MenuGroup record。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.menu")
public class ConsoleMenuProperties {

  /** 菜单组列表。 */
  private List<GroupDef> groups = new ArrayList<>();

  @Data
  public static class GroupDef {
    private String key;
    private String title;
    private String icon;
    private String minRole;
    private List<ItemDef> children = new ArrayList<>();
  }

  @Data
  public static class ItemDef {
    private String title;
    private String path;
    private String icon;
    private String minRole;
  }
}
