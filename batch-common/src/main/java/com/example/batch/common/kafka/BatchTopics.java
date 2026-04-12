package com.example.batch.common.kafka;

public final class BatchTopics {

  public static final String TASK_DISPATCH_IMPORT = "batch.task.dispatch.import";
  public static final String TASK_DISPATCH_EXPORT = "batch.task.dispatch.export";
  public static final String TASK_DISPATCH_DISPATCH = "batch.task.dispatch.dispatch";
  public static final String TASK_RESULT = "batch.task.result";
  public static final String TASK_RETRY = "batch.task.retry";
  public static final String TASK_DEAD_LETTER = "batch.task.dead-letter";
  public static final String OUTBOX_EVENT = "batch.outbox.event";
  public static final String WORKER_HEARTBEAT = "batch.worker.heartbeat";

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
