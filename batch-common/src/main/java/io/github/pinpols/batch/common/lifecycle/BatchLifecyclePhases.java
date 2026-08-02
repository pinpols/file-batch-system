package io.github.pinpols.batch.common.lifecycle;

/**
 * BFS 进程内 SmartLifecycle phase 台账。
 *
 * <p>Spring 关闭时按 phase 从高到低停止，启动时从低到高启动。这里把关键 phase 收敛成常量，避免各模块散落魔法数字后把
 * scheduler / relay 停到 Redis、Kafka、DB 之后。
 */
public final class BatchLifecyclePhases {

  /**
   * 需要最早停止的入口/relay 类组件。
   *
   * <p>典型场景：自管理轮询线程，停机时必须先取消，避免后续 Redis/ShedLock 已进入 STOPPING 后还继续拿锁。
   */
  public static final int FIRST_TO_STOP_RELAY = Integer.MAX_VALUE;

  /**
   * Spring 托管调度线程池。
   *
   * <p>保持历史默认值 {@code 1_073_741_823}，高于 Redis LettuceConnectionFactory 默认 phase=0，确保定时任务先
   * stop/drain，再关闭基础设施连接。
   */
  public static final int MANAGED_SCHEDULER = Integer.MAX_VALUE / 2;

  /**
   * 外部 worker SDK 客户端生命周期。
   *
   * <p>保留比 FIRST_TO_STOP_RELAY 略低的历史语义：关闭时先停止主动 intake/relay，再停止 worker SDK client。
   */
  public static final int WORKER_SDK_CLIENT = Integer.MAX_VALUE - 100;

  /**
   * Redis/Kafka/DB 等基础设施客户端的常见默认 phase 参考值。
   *
   * <p>本常量只用于测试和文档化顺序，不强行覆盖 Spring/第三方基础设施 bean 的 phase。
   */
  public static final int INFRASTRUCTURE_CLIENT_DEFAULT = 0;

  private BatchLifecyclePhases() {}
}
