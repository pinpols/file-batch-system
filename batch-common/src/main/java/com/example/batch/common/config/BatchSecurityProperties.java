package com.example.batch.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;
import org.springframework.core.env.Environment;

@Data
@Slf4j
@ConfigurationProperties(prefix = "batch.security")
public class BatchSecurityProperties {

  /**
   * 全局安全旁路总开关（{@code batch.security.bypass-mode}）。开启后放宽认证、脱敏、加解密、审批、
   * 渠道校验等所有安全约束，仅供本地 / 联调 / E2E 使用。
   *
   * <p>S-1.11：Java 字段默认 {@code false}（安全默认），实际默认值由部署渠道覆盖：
   *
   * <ul>
   *   <li>IDE 本地 / {@code application-local.yml}：<b>显式 {@code true}</b>（调试方便，与
   *       CLAUDE.md §配置开关规范一致；旧注释写 {@code false} 是错的）
   *   <li>docker-compose：{@code ${BATCH_SECURITY_BYPASS_MODE:-false}}（贴近生产；之前注释
   *       误写 {@code -true}）
   *   <li>prod profile：在 {@link #validateSecuritySettings()} 的 @PostConstruct 强制拒绝
   *       {@code true}
   * </ul>
   */
  private boolean bypassMode = false;

  /**
   * orchestrator 内部接口（/internal/**）的共享密钥。 客户端通过 X-Internal-Secret header 携带；bypassMode=true 时跳过校验。
   * 生产环境必须通过 BATCH_INTERNAL_SECRET 环境变量注入强密钥。
   */
  private String internalSecret = "internal-secret";

  // 注入 Environment 用于启动时 profile 检查；@Autowired(required=false) 保证测试兼容性
  @Autowired(required = false)
  private transient Environment environment;

  /**
   * 兼容旧键 `batch.security.testing-open`（2026-04-19 重命名为 `bypass-mode`）。保留 setter 让
   * Spring relaxed binding 仍能把旧键读入同一字段；启动时 @PostConstruct 打 WARN 提示迁移。
   * 下一个 minor 版本移除。
   */
  @Deprecated(since = "2026-04-19", forRemoval = true)
  public void setTestingOpen(boolean testingOpen) {
    this.bypassMode = testingOpen;
    this.testingOpenLegacyKeyUsed = true;
  }

  @Deprecated(since = "2026-04-19", forRemoval = true)
  @DeprecatedConfigurationProperty(
      replacement = "batch.security.bypass-mode",
      reason = "语义重命名：testing-open → bypass-mode，更准确反映放宽整条安全链的副作用")
  public boolean isTestingOpen() {
    return bypassMode;
  }

  private transient boolean testingOpenLegacyKeyUsed = false;

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
          "FATAL: batch.security.bypass-mode=true 在生产 profile 下被禁止。" + " 请移除该配置或使用非生产 profile。");
    }
    if (bypassMode) {
      log.warn("batch.security.bypass-mode=true — 全链路安全旁路已启用，仅限本地/联调/E2E 使用");
    }
    if (testingOpenLegacyKeyUsed) {
      log.warn(
          "batch.security.testing-open 已重命名为 batch.security.bypass-mode，"
              + "旧键仍可用但会在下一版本移除，请尽快迁移配置");
    }
    if (prod) {
      validateNotPlaceholder("batch.security.internal-secret", internalSecret);
      if ("internal-secret".equals(internalSecret)) {
        throw new IllegalStateException(
            "FATAL: 生产环境 batch.security.internal-secret 仍为默认值 'internal-secret'，"
                + "请通过 secret manager 或环境变量注入强密钥");
      }
      validateNotPlaceholder(
          "POSTGRES_PASSWORD", environment.getProperty("spring.datasource.password"));
    }
  }

  private void validateNotPlaceholder(String key, String value) {
    if (value != null && value.startsWith("CHANGE_ME")) {
      throw new IllegalStateException(
          "FATAL: 生产环境密钥未配置: " + key + " 仍为占位符，请通过 secret manager 或环境变量注入真实凭据");
    }
  }

  private boolean isProductionProfile() {
    if (environment == null) {
      return false;
    }
    for (String profile : environment.getActiveProfiles()) {
      if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
        return true;
      }
    }
    return false;
  }
}
