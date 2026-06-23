package io.github.pinpols.batch.console.config;

/**
 * 读写分离 ThreadLocal 路由提示。两类用途：
 *
 * <ul>
 *   <li><b>force-primary 旁路</b>：read-after-write 等强一致场景用 {@link #forcePrimary(Runnable)} 或 {@link
 *       io.github.pinpols.batch.console.config.RouteToPrimary @RouteToPrimary} 注解，强制本调用走主库 而绕过
 *       readOnly 默认路由
 *   <li><b>fail-open 已降级</b>：{@link ReadReplicaRoutingDataSource} 把从库 quarantine 状态写入此
 *       ThreadLocal，让外层 metric/日志能识别"本次实际走了主库降级"
 * </ul>
 *
 * <p>设计为简单 ThreadLocal（非 InheritableThreadLocal）：跨线程异步任务通常自带独立事务边界， 继承父线程 hint 反而引入误路由风险。
 */
public final class RoutingHints {

  private static final ThreadLocal<Boolean> FORCE_PRIMARY = new ThreadLocal<>();

  private RoutingHints() {}

  /** 当前线程是否声明强制走主库（{@code true}）。 */
  public static boolean isForcePrimary() {
    return Boolean.TRUE.equals(FORCE_PRIMARY.get());
  }

  /** 在指定 Runnable 内强制走主库；finally 清理 ThreadLocal。 */
  public static void forcePrimary(Runnable runnable) {
    Boolean prev = FORCE_PRIMARY.get();
    FORCE_PRIMARY.set(Boolean.TRUE);
    try {
      runnable.run();
    } finally {
      if (prev == null) {
        FORCE_PRIMARY.remove();
      } else {
        FORCE_PRIMARY.set(prev);
      }
    }
  }

  /** AOP 切面用：set + remember 上一次值，配合 {@link #restore(Boolean)} 在 finally 还原。 */
  static Boolean enterForcePrimary() {
    Boolean prev = FORCE_PRIMARY.get();
    FORCE_PRIMARY.set(Boolean.TRUE);
    return prev;
  }

  static void restore(Boolean prev) {
    if (prev == null) {
      FORCE_PRIMARY.remove();
    } else {
      FORCE_PRIMARY.set(prev);
    }
  }
}
