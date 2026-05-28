package com.example.batch.common.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Profile 判断工具:统一"prod-like profile"识别口径,避免多处复制 {@code PROD_LIKE_PROFILES} 常量与判断逻辑漂移。
 *
 * <p>历史背景:{@link BatchSecurityProperties} / {@code ConsoleSecurityProperties} / {@code
 * ConsoleJwtService} 三处独立维护同一组 profile 名集合,人工同步成本高,易漂移。集中到本类后,任何 prod-like profile 的扩展(例:新增
 * "prerelease")只改一处。
 *
 * <p>语义:把 prod / production / staging / uat / preprod / pre-prod / pre-production 统一视为"贴近生产"
 * profile,在这些 profile 下强制启用各类安全守卫(bypass-mode 拒绝、密钥占位符校验、auth filter enabled 校验等)。
 */
public final class BatchProfileSupport {

  /** 贴近生产的 profile 名集合。staging / uat / preprod 与 prod 在安全要求上同等严格,避免预生产环境 role-escalation。 */
  public static final Set<String> PROD_LIKE_PROFILES =
      Set.of("prod", "production", "staging", "uat", "preprod", "pre-prod", "pre-production");

  /**
   * 明确的"非生产"profile 名集合。只有激活集里出现这些之一(且不含任何 prod-like)时,才把环境判为非生产并放宽安全守卫 (bypass-mode、弱密钥、auth
   * filter 关闭等)。本地脚本 / strict-verify 用 {@code local},集成测用 {@code test}/{@code e2e},Docker 本地部署需显式设
   * {@code SPRING_PROFILES_ACTIVE=local}。
   */
  public static final Set<String> NON_PROD_PROFILES =
      Set.of("dev", "development", "local", "test", "e2e", "ci", "integration", "it", "sit");

  private BatchProfileSupport() {}

  /**
   * 当前环境是否应按"生产"对待(fail-secure)。
   *
   * <p><strong>语义(audit fix:fail-open → fail-secure)</strong>:历史实现只在激活集里显式出现 prod-like profile 时才返回
   * true,导致"部署到生产却忘配 {@code SPRING_PROFILES_ACTIVE}"时 fail-open——空 / 未知 profile
   * 被判为非生产,bypass-mode、弱密钥校验等安全守卫全被绕过。现改为:
   *
   * <ul>
   *   <li>激活集含任一 prod-like → 生产(true);
   *   <li>激活集含任一已识别的非生产 profile 且不含 prod-like → 非生产(false);
   *   <li>其余(空激活集 / 只有未知 profile)→ <strong>按生产对待(true)</strong>,fail-secure。
   * </ul>
   *
   * @param environment Spring Environment,null 时返回 false(无容器纯单测场景,由测试自身控制)
   */
  public static boolean isProductionProfile(Environment environment) {
    if (environment == null) {
      return false;
    }
    boolean anyExplicitNonProd = false;
    for (String profile : environment.getActiveProfiles()) {
      if (profile == null) {
        continue;
      }
      String normalized = profile.toLowerCase(Locale.ROOT);
      if (PROD_LIKE_PROFILES.contains(normalized)) {
        return true;
      }
      if (NON_PROD_PROFILES.contains(normalized)) {
        anyExplicitNonProd = true;
      }
    }
    // fail-secure:既无 prod-like,也无任何已识别的非生产 profile(空激活集 / 全是未知 profile)→ 当生产处理。
    return !anyExplicitNonProd;
  }
}
