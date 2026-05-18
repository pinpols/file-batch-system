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

  private BatchProfileSupport() {}

  /**
   * 当前激活 profile 是否属于 prod-like。
   *
   * @param environment Spring Environment,null 时返回 false(无容器单测场景)
   */
  public static boolean isProductionProfile(Environment environment) {
    if (environment == null) {
      return false;
    }
    for (String profile : environment.getActiveProfiles()) {
      if (profile != null && PROD_LIKE_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }
}
