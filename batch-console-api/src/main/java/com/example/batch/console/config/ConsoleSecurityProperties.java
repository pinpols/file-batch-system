package com.example.batch.console.config;

import com.example.batch.common.config.BatchProfileSupport;
import com.example.batch.console.support.auth.ConsoleRoles;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

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

  /**
   * R7-A1-P2：HttpOnly cookie {@code batch_console_token} 的 Secure 标志。生产默认 true（强制 HTTPS）， 本地 /
   * docker-compose 调试时用 application-local.yml 覆盖为 false。原本硬编码 false 依赖反代改写， 反代 misconfig 即明文 HTTP
   * 暴露 token，没有强制约束。
   */
  private boolean cookieSecure = true;

  /**
   * 是否信任反代下发的 {@code X-Forwarded-For} / {@code X-Real-IP}。默认 false（生产应让反代/Ingress 通过 PROXY protocol
   * 或固定下发 trusted header 后再开启）。 应用直接挂公网时一旦开启，攻击者可 {@code curl -H "X-Forwarded-For: 1.2.3.4"} 轮换源 IP
   * 绕过限流。
   */
  private boolean trustForwardedHeaders = false;

  /**
   * CORS 允许的 origin 白名单（{@code https://console.example.com}）。空 = 不发 CORS 头（同源场景）。 若需要跨域部署前后端,
   * 必须显式列出来 —— 不允许通配符 {@code *} + {@code allowCredentials=true} 的组合（浏览器规范拒绝）。
   */
  private List<String> corsAllowedOrigins = new ArrayList<>();

  /**
   * 登录请求体加密（RSA-2048 OAEP-SHA256 包装 AES-256-GCM key）。FE 加密 {@code {username,password}}, BE 解密后走原
   * {@link com.example.batch.console.support.auth.ConsoleLoginService}。
   *
   * <p>双模式 + prod 守护:
   *
   * <ul>
   *   <li>{@code enabled=true / required=false}（dev / local / CI 默认）—— BE 同时接受 加密 / 明文两种 body
   *   <li>{@code enabled=true / required=true}（prod-like profile 强制）—— 仅接受加密;明文 401
   * </ul>
   */
  private LoginEncryption loginEncryption = new LoginEncryption();

  @Data
  public static class LoginEncryption {
    /** 总开关。false = /auth/public-key endpoint 不暴露,仅明文路径。 */
    private boolean enabled = true;

    /** 严格模式:true 时 BE 仅接受加密 body,明文 401;false 时两种都接受(e2e 友好)。 */
    private boolean required = false;

    /** PEM 编码 RSA 私钥;空时启动期生成内存密钥对(单实例 OK,helm 多副本需 set)。 */
    private String privateKeyPem = "";

    /** PEM 编码 RSA 公钥;若 privateKeyPem 提供则必填。 */
    private String publicKeyPem = "";
  }

  @Autowired(required = false)
  private transient Environment environment;

  /**
   * P0-1 (pre-launch audit 2026-05-18)：prod-like profile 下禁止 {@code enabled=false}。
   *
   * <p>未加守护前 Helm values 误改 {@code batch.console.security.enabled=false} 即可裸奔 —— filter 短路放行所有
   * /api/console/**，JWT / bypass / SSE 全跳过。
   */
  @PostConstruct
  void validateEnabledInProdProfile() {
    if (enabled || !BatchProfileSupport.isProductionProfile(environment)) {
      return;
    }
    throw new IllegalStateException(
        "FATAL: batch.console.security.enabled=false 在 prod-like profile 下被禁止。"
            + "如需联调请用 batch.security.bypass-mode 单一开关。");
  }

  /**
   * Prod-like profile 强制 {@code loginEncryption.enabled=true} 且 {@code required=true}。 dev / local
   * / test 下用户自由配,以保证 e2e / API direct 测试不破。
   */
  @PostConstruct
  void validateLoginEncryptionInProdProfile() {
    if (!BatchProfileSupport.isProductionProfile(environment)) {
      return;
    }
    if (!loginEncryption.isEnabled()) {
      throw new IllegalStateException(
          "FATAL: batch.console.security.login-encryption.enabled=false 在 prod-like profile 下被禁止");
    }
    if (!loginEncryption.isRequired()) {
      throw new IllegalStateException(
          "FATAL: batch.console.security.login-encryption.required=false 在 prod-like profile 下被禁止"
              + "（仅 dev / test profile 可关以兼容 API direct e2e）");
    }
  }
}
