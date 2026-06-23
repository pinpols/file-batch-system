package io.github.pinpols.batch.orchestrator.domain.scheduling;

/** {@code waitForCapacity} 表示容量不足可重试；{@code reject} 触发 failFast 不再等待。 */
public record ResourceCheck(
    boolean allowed, boolean failFast, String reasonCode, String reasonMessage) {
  public static ResourceCheck allow() {
    return new ResourceCheck(true, false, null, null);
  }

  public static ResourceCheck waitForCapacity(String reasonCode, String reasonMessage) {
    return new ResourceCheck(false, false, reasonCode, reasonMessage);
  }

  public static ResourceCheck reject(String reasonCode, String reasonMessage) {
    return new ResourceCheck(false, true, reasonCode, reasonMessage);
  }
}
