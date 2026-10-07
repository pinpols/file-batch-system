package io.github.pinpols.batch.worker.core.support;

/** 接收平台心跳指令，控制 Worker 是否继续从 Kafka 拉取新任务。 */
public interface WorkerConsumptionControl {

  /**
   * 切换平台排空状态。
   *
   * @param draining true 时停止拉取新任务；false 时在本地仍有执行容量时恢复拉取
   */
  void setPlatformDraining(boolean draining);
}
