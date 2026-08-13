package io.github.pinpols.batch.sdk.internal;

import java.util.Map;

/** SDK 保持 Spring-free 时使用的最小空值判断，避免引入平台服务端公共依赖。 */
public final class EmptyChecks {

  private EmptyChecks() {}

  public static boolean isEmpty(Map<?, ?> value) {
    return value == null || value.isEmpty();
  }

  public static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public static boolean isEmpty(byte[] value) {
    return value == null || value.length == 0;
  }
}
