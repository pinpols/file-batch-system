package io.github.pinpols.batch.common.mq;

/** MQ broker 已确认后的最小结果集；不同 MQ 实现可按能力填充。 */
public record MqPublishResult(Integer partition, Long offset) {

  public static MqPublishResult acknowledged() {
    return new MqPublishResult(null, null);
  }
}
