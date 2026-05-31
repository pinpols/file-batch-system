package com.example.batch.common.kafka;

public final class BatchTopics {

  public static final String TASK_DISPATCH_IMPORT = "batch.task.dispatch.import";
  public static final String TASK_DISPATCH_EXPORT = "batch.task.dispatch.export";
  public static final String TASK_DISPATCH_PROCESS = "batch.task.dispatch.process";
  public static final String TASK_DISPATCH_DISPATCH = "batch.task.dispatch.dispatch";
  // ADR-029:专用 SPI worker(batch-worker-atomic)的派发 topic,不跟 pipeline worker 混用。
  public static final String TASK_DISPATCH_SPI = "batch.task.dispatch.spi";
  public static final String TASK_RESULT = "batch.task.result";
  public static final String TASK_RETRY = "batch.task.retry";
  public static final String TASK_DEAD_LETTER = "batch.task.dead-letter";
  public static final String OUTBOX_EVENT = "batch.outbox.event";
  public static final String WORKER_HEARTBEAT = "batch.worker.heartbeat";

  /** ADR-010: trigger → orchestrator 异步 launch 事件 topic(版本化,协议演进时升 v2)。 */
  public static final String TRIGGER_LAUNCH_V1 = "batch.trigger.launch.v1";

  /**
   * ADR-030 §F: ContentVerifier 失败事件专用 topic。worker 上报 verifierFailures → orchestrator 同事务写
   * outbox_event (event_type=verifier.failure.v1) → relay 到此专用 topic，让运维订阅做告警面板 / SLO，避免和通用 outbox
   * fallback topic 混在一起。
   */
  public static final String VERIFIER_FAILURE_V1 = "batch.verifier.failure.v1";

  private BatchTopics() {}

  public static String directDispatchTopic(String baseTopic, String workerId) {
    if (baseTopic == null || workerId == null || workerId.isBlank()) {
      return baseTopic;
    }
    return baseTopic + ".node." + sanitizeTopicSegment(workerId);
  }

  private static String sanitizeTopicSegment(String segment) {
    return segment.replaceAll("[^a-zA-Z0-9._-]", "_");
  }
}
