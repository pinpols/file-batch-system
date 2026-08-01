package io.github.pinpols.batch.common.config;

import jakarta.annotation.PostConstruct;
import java.util.Set;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

@Data
@Slf4j
@ConfigurationProperties(prefix = "batch.security")
public class BatchSecurityProperties implements EnvironmentAware {

  /**
   * 全局安全旁路总开关（{@code batch.security.bypass-mode}）。开启后放宽认证、脱敏、加解密、审批、 渠道校验等所有安全约束，仅供本地 / 联调 / E2E
   * 使用。
   *
   * <p>S-1.11：Java 字段默认 {@code false}（安全默认），实际默认值由部署渠道覆盖：
   *
   * <ul>
   *   <li>IDE 本地 / {@code application-local.yml}：<b>显式 {@code true}</b>（调试方便，与 CLAUDE.md
   *       §配置开关规范一致；旧注释写 {@code false} 是错的）
   *   <li>docker-compose：{@code ${BATCH_SECURITY_BYPASS_MODE:-false}}（贴近生产；之前注释 误写 {@code -true}）
   *   <li>prod profile：在 {@link #validateSecuritySettings()} 的 @PostConstruct 强制拒绝 {@code true}
   * </ul>
   */
  private boolean bypassMode = false;

  /**
   * orchestrator 内部接口（/internal/**）的共享密钥。 客户端通过 X-Internal-Secret header 携带；bypassMode=true 时跳过校验。
   * 生产环境必须通过 BATCH_INTERNAL_SECRET 环境变量注入强密钥。
   */
  private String internalSecret = "";

  // 注入 Environment 用于启动时 profile 检查;经 EnvironmentAware 框架回调注入(非 @Autowired field),
  // 容器内由 Spring 调 @Data 生成的 setEnvironment 注入;单测 new 该类时不调用 → environment 为 null,守护逻辑已 null-safe。
  private transient Environment environment;

  // #5-1: 生产 profile 下强制禁止 bypassMode，防止误配导致认证被绕过
  // #9-1: 生产 profile 下校验密码占位符已被替换
  @PostConstruct
  void validateSecuritySettings() {
    if (environment == null) {
      return; // 纯单元测试场景，无 Spring 容器
    }
    boolean prod = isProductionProfile();
    if (bypassMode && prod) {
      throw new IllegalStateException(
          "FATAL: batch.security.bypass-mode=true is forbidden in a production profile."
              + " Remove this setting or use a non-production profile.");
    }
    if (bypassMode) {
      log.warn(
          "batch.security.bypass-mode=true - full-chain security bypass is enabled; local/integration/E2E use only");
    }
    if (prod) {
      validateNotPlaceholder("batch.security.internal-secret", internalSecret);
      if ("internal-secret".equals(internalSecret)) {
        throw new IllegalStateException(
            "FATAL: production batch.security.internal-secret still uses the default value 'internal-secret';"
                + " inject a strong secret through a secret manager or environment variable");
      }
      validateNotPlaceholder(
          "POSTGRES_PASSWORD", environment.getProperty("spring.datasource.password"));
      // #I-1: console-api 的主/从库密码走独立 key(batch.console.read-replica.*),不经
      // spring.datasource.password,故上面的校验覆盖不到。这些 key 默认值是弱口令 batch_pass_123,
      // prod 下若未注入 env 会静默用默认密码连生产库。非 console 模块该 property 不存在(null)→ 跳过。
      validateNotKnownWeakDbPassword(
          "batch.console.read-replica.primary.password",
          environment.getProperty("batch.console.read-replica.primary.password"));
      validateNotKnownWeakDbPassword(
          "batch.console.read-replica.replica.password",
          environment.getProperty("batch.console.read-replica.replica.password"));
    } else {
      // 非 prod:不 fail-fast(本地/联调要能起),但把"默认/弱凭据仍在用"显式 WARN 出来——
      // 兜 prod fail-fast 的第二层,防"漏开 prod profile 就静默用默认密钥连真库"(审计 #4)。
      warnIfKnownInsecureDefault(
          "batch.security.internal-secret", internalSecret, "internal-secret");
      warnIfKnownWeakDbPassword(
          "batch.console.read-replica.primary.password",
          environment.getProperty("batch.console.read-replica.primary.password"));
      warnIfKnownWeakDbPassword(
          "batch.console.read-replica.replica.password",
          environment.getProperty("batch.console.read-replica.replica.password"));
    }
  }

  /** 非 prod:已知默认占位符仍在用 → WARN(不阻断,只提醒,prod profile 会 fail-fast 拒绝)。 */
  private void warnIfKnownInsecureDefault(String key, String value, String shippedDefault) {
    if (shippedDefault.equals(value)) {
      log.warn(
          "Non-production profile: {} still uses the shipped placeholder; inject a real high-entropy secret through env / secret manager"
              + " before production (production-like profiles fail fast)",
          key);
    }
  }

  /** 非 prod:DB 密码仍为已知弱默认口令 → WARN(不阻断,property 不存在的模块跳过)。 */
  private void warnIfKnownWeakDbPassword(String key, String value) {
    if (value != null && KNOWN_WEAK_DB_PASSWORDS.contains(value.trim())) {
      log.warn(
          "Non-production profile: {} still uses the shipped weak password; inject real credentials before production"
              + " (production-like profiles fail fast)",
          key);
    }
  }

  /** prod 库连接默认弱口令清单——出现在 application.yml 默认值里,绝不能进生产。 */
  private static final Set<String> KNOWN_WEAK_DB_PASSWORDS = Set.of("batch_pass_123");

  /** 仅当 property 实际存在(非 null)且命中已知弱默认口令时 fail-fast;property 不存在的模块跳过。 */
  private void validateNotKnownWeakDbPassword(String key, String value) {
    if (value == null) {
      return;
    }
    if (KNOWN_WEAK_DB_PASSWORDS.contains(value.trim())) {
      throw new IllegalStateException(
          "FATAL: production database password " + key
              + " still uses a known weak password; inject real credentials through a secret manager or environment variable");
    }
  }

  /** 已知占位符前缀（大小写不敏感、忽略下划线/连字符）。 */
  private static final Set<String> PLACEHOLDER_PREFIXES =
      Set.of("changeme", "change", "placeholder", "todo", "secret", "yoursecret");

  /** 内部 / JWT 密钥的最小长度——短于此值即使非占位符也拒绝。 */
  private static final int MIN_SECRET_LENGTH = 16;

  private void validateNotPlaceholder(String key, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("FATAL: production secret is not configured: " + key
          + " is empty; inject a real credential through a secret manager or environment variable");
    }
    // 归一化：trim + lowercase + 去掉 _ / - 后比对占位符前缀，覆盖 CHANGE_ME / change-me / changeme 等变体
    String normalized = value.trim().toLowerCase().replaceAll("[_\\-]", "");
    for (String prefix : PLACEHOLDER_PREFIXES) {
      if (normalized.startsWith(prefix)) {
        throw new IllegalStateException("FATAL: production secret is not configured: " + key
            + " is still a placeholder ('" + value
            + "'); inject a real credential through a secret manager or environment variable");
      }
    }
    if (value.trim().length() < MIN_SECRET_LENGTH) {
      throw new IllegalStateException("FATAL: production secret is too weak: "
          + key
          + " length="
          + value.trim().length()
          + " < minimum "
          + MIN_SECRET_LENGTH
          + "; inject a high-entropy secret through a secret manager");
    }
  }

  private boolean isProductionProfile() {
    return BatchProfileSupport.isProductionProfile(environment);
  }
}
