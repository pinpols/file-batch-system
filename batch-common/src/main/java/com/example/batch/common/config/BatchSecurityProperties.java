package com.example.batch.common.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.Environment;

@Data
@Slf4j
@ConfigurationProperties(prefix = "batch.security")
public class BatchSecurityProperties {

  /** 早期测试模式：放宽认证、脱敏和解密等限制。 */
  private boolean testingOpen = false;

  /**
   * orchestrator 内部接口（/internal/**）的共享密钥。 客户端通过 X-Internal-Secret header 携带；testingOpen=true 时跳过校验。
   * 生产环境必须通过 BATCH_INTERNAL_SECRET 环境变量注入强密钥。
   */
  private String internalSecret = "internal-secret";

  // 注入 Environment 用于启动时 profile 检查；@Autowired(required=false) 保证测试兼容性
  @Autowired(required = false)
  private transient Environment environment;

  // #5-1: 生产 profile 下强制禁止 testingOpen，防止误配导致认证被绕过
  // #9-1: 生产 profile 下校验密码占位符已被替换
  @PostConstruct
  void validateSecuritySettings() {
    if (environment == null) {
      return; // 纯单元测试场景，无 Spring 容器
    }
    boolean prod = isProductionProfile();
    if (testingOpen && prod) {
      throw new IllegalStateException(
          "FATAL: batch.security.testing-open=true 在生产 profile 下被禁止。" + " 请移除该配置或使用非生产 profile。");
    }
    if (testingOpen) {
      log.warn("batch.security.testing-open=true — 内部接口认证已关闭，仅限开发/测试环境使用");
    }
    if (prod) {
      validateNotPlaceholder("batch.security.internal-secret", internalSecret);
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
