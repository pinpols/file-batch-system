package io.github.pinpols.batch.common.diagnostics;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * 把 BFS 生产 fail-close 异常翻译成 Spring Boot 风格的可执行启动诊断。
 *
 * <p>原异常继续负责 fail-fast，本 analyzer 只改善启动日志里的 description/action，不改变安全策略。
 */
public class BatchStartupFailureAnalyzer extends AbstractFailureAnalyzer<IllegalStateException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, IllegalStateException cause) {
    String message = cause.getMessage();
    if (message == null || message.isBlank()) {
      return null;
    }
    if (message.contains("batch.security.bypass-mode=true")) {
      return analysis(
          cause,
          "生产或生产相似 profile 下启用了 batch.security.bypass-mode=true，BFS 按 fail-close 拒绝启动。",
          "将 BATCH_SECURITY_BYPASS_MODE 设为 false，并确认 SPRING_PROFILES_ACTIVE 使用 prod/staging/uat/preprod "
              + "等受控 profile；仅 local/dev/test/e2e 允许安全旁路。");
    }
    if (message.contains("batch.security.internal-secret")
        || message.contains("production secret is not configured")) {
      return analysis(
          cause,
          "生产内部 API 共享密钥缺失、仍是占位符，或强度不足。",
          "通过 Secret Manager、Kubernetes Secret 或环境变量 BATCH_INTERNAL_SECRET 注入高熵密钥；不要使用 "
              + "CHANGE_ME、internal-secret、todo 等占位符。");
    }
    if (message.contains("jwt-secret")) {
      return analysis(
          cause,
          "Console JWT 签名密钥缺失、仍是占位符，或长度不足。",
          "通过 Secret Manager、Kubernetes Secret 或环境变量 BATCH_CONSOLE_JWT_SECRET 注入至少 32 字符的高熵密钥，"
              + "并确认 Helm/Compose/prod profile 没有回落到默认值。");
    }
    if (message.contains("object-storage credentials are not configured")) {
      return analysis(
          cause,
          "生产对象存储凭据未配置，S3/兼容 S3 后端不能安全启动。",
          "注入 BATCH_S3_ACCESS_KEY 与 BATCH_S3_SECRET_KEY，并确认 batch.storage.backend=s3 时 endpoint、region、bucket "
              + "均指向目标环境。");
    }
    if (message.contains("known MinIO default credentials")) {
      return analysis(
          cause,
          "生产对象存储仍在使用 MinIO 默认凭据，存在直接接管风险。",
          "更换为真实访问密钥；生产环境不得使用 minioadmin/minioadmin123 等默认值。");
    }
    if (message.contains("production database password")) {
      return analysis(
          cause,
          "生产数据库连接仍在使用已知弱默认密码。",
          "为主库和读副本分别注入真实数据库密码，重点检查 spring.datasource.password、"
              + "batch.console.read-replica.primary.password 与 batch.console.read-replica.replica.password。");
    }
    return null;
  }

  private static FailureAnalysis analysis(
      IllegalStateException cause, String description, String action) {
    return new FailureAnalysis(description, action, cause);
  }
}
