package com.example.batch.console.config;

import com.example.batch.console.support.ConsoleRoles;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console-API 安全配置（{@code batch.console.security}）。
 *
 * <p>覆盖：JWT 颁发 + 多租户路由 + session 单点 + bypass-mode 测试钩子。
 *
 * <p>主认证方式 = JWT（Authorization Bearer）；旧 X-Console-Token 共享密钥兼容路径已于 2026-04-30 物理删除（详见
 * docs/analysis/project-assessment-2026-04-29.md §8 S5-d）。
 *
 * <p>详见 design/multi-tenant-and-security.md §3。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.security")
public class ConsoleSecurityProperties {

  /** 安全总开关。关闭 → 所有 console API 不做认证（仅本地调试）。 */
  private boolean enabled = true;

  /** 租户标识 header 名。前端 / 网关在请求里带 → 后端按此值路由。 */
  private String tenantHeader = "X-Tenant-Id";

  /** 用户标识 header 名（bypass-mode 测试钩子，便于按 header 注入测试用户身份）。 */
  private String userHeader = "X-Console-User";

  /** 角色 header 名（bypass-mode 测试钩子，逗号分隔多角色）。 */
  private String roleHeader = "X-Console-Roles";

  /** 默认租户 ID。当请求未带 tenant header 时使用；空 = 强制要求 header。 */
  private String defaultTenantId = "";

  /** 允许的租户白名单。空 list = 不限制。 */
  private List<String> allowedTenants = new ArrayList<>();

  /** 默认授权角色（SSE ticket / bypass-mode 未带 role header 时的兜底）。 */
  private List<String> defaultAuthorities =
      new ArrayList<>(List.of(ConsoleRoles.ADMIN, ConsoleRoles.AUDITOR, ConsoleRoles.CONFIG_ADMIN));

  /** 单点登录（同一用户后登录踢前一个 session）。 */
  private boolean singleSessionEnabled = true;

  /** JWT iss claim 值。 */
  private String jwtIssuer = "batch-console-api";

  /** JWT 签名密钥。<b>生产必须改</b>。占位符以 {@code change-me} 结尾，PostConstruct 检测拒绝默认值。 */
  private String jwtSecret = "console-jwt-secret-change-me";

  /** JWT 有效期。过短用户被频繁踢出；过长被盗后影响面大。 */
  private Duration jwtTtl = Duration.ofHours(8);

  /** JWT 时钟偏差容忍。多机时钟不齐时调大（NTP 同步好的话 1 分钟够）。 */
  private Duration jwtClockSkew = Duration.ofMinutes(1);

  /** Redis 中 session state（单点登录、踢人）TTL。 */
  private Duration sessionStateTtl = Duration.ofDays(30);
}
