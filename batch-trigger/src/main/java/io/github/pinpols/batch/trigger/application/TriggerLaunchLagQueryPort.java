package io.github.pinpols.batch.trigger.application;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * trigger launch consumer group 积压采样端口。
 *
 * <p>应用层只依赖本端口；Kafka Admin 客户端获取与 offset 计算由基础设施适配器实现，避免 application 层直接依赖
 * Kafka SDK（见 {@code check-infrastructure-abstraction-boundaries.py}）。
 */
public interface TriggerLaunchLagQueryPort {

  /** topic 暂不可见时的未知样本哨兵值；发布治理器据此收缩到最小速率。 */
  long UNKNOWN_LAG = -1L;

  /**
   * 采样一次 consumer group 相对 topic 末端 offset 的总积压。
   *
   * <p>只读 offset，不消费消息、不提交 offset。
   *
   * @return 总 lag；topic 暂不可见时返回 {@link #UNKNOWN_LAG}
   */
  long sampleLag() throws InterruptedException, ExecutionException, TimeoutException;
}
