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
 * <p>覆盖：JWT 颁发 + Header 兼容认证 + 多租户路由 + session 单点。
 *
 * <p>JWT 是主认证方式，Header 认证为旧前端兼容期保留（{@link #legacyHeaderAuthEnabled}）。 详见
 * design/multi-tenant-and-security.md §3。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.security")
public class ConsoleSecurityProperties {

  /** 安全总开关。关闭 → 所有 console API 不做认证（仅本地调试）。 */
  private boolean enabled = true;

  /** 共享密钥（旧 Header 认证用）。生产必须通过 secret manager 注入强密钥。 */
  private String sharedSecret = "console-secret";

  /** 租户标识 header 名。前端 / 网关在请求里带 → 后端按此值路由。 */
  private String tenantHeader = "X-Tenant-Id";

  /** 用户标识 header 名（旧前端兼容）。 */
  private String userHeader = "X-Console-User";

  /** 角色 header 名（旧前端兼容，逗号分隔多角色）。 */
  private String roleHeader = "X-Console-Roles";

  /** JWT token header 名。前端 login 后续请求带这个 header。 */
  private String tokenHeader = "X-Console-Token";

  /** 默认租户 ID。当请求未带 tenant header 时使用；空 = 强制要求 header。 */
  private String defaultTenantId = "";

  /** 允许的租户白名单。空 list = 不限制。 */
  private List<String> allowedTenants = new ArrayList<>();

  /** 默认授权角色（旧 Header 认证模式下，请求未带 role header 时的兜底角色）。 */
  private List<String> defaultAuthorities =
      new ArrayList<>(List.of(ConsoleRoles.ADMIN, ConsoleRoles.AUDITOR, ConsoleRoles.CONFIG_ADMIN));

  /** 旧 Header 认证开关。生产应关闭，仅 JWT。 */
  private boolean legacyHeaderAuthEnabled = true;

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
