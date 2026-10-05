package io.github.pinpols.batch.console.shared.client;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import org.springframework.core.env.Environment;

/** 解析 Console 内部 HTTP 客户端地址，并支持 RANDOM_PORT 测试在服务启动后回调本应用。 */
public final class ConsoleInternalBaseUrlResolver {

  private ConsoleInternalBaseUrlResolver() {}

  public static String resolve(Environment environment, String raw, String propertyName) {
    String resolved =
        EmptyChecks.isNull(raw) ? null : environment.resolvePlaceholders(raw).trim();
    if (Texts.hasText(resolved) && !resolved.contains("${")) {
      return resolved;
    }
    if (EmptyChecks.isNotNull(raw) && raw.contains("${local.server.port}")) {
      String localPort = environment.getProperty("local.server.port");
      if (Texts.hasText(localPort)) {
        return "http://127.0.0.1:" + localPort.trim();
      }
    }
    throw new IllegalStateException(propertyName + " is required but not configured");
  }
}
