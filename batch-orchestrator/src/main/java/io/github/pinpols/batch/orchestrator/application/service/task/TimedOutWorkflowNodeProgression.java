package io.github.pinpols.batch.orchestrator.application.service.task;

import java.time.Instant;

/** JobInstance 超时并结束其子项后，推进所属 Workflow DAG。 */
interface TimedOutWorkflowNodeProgression {

  void advanceTimedOutWorkflowNode(String tenantId, Long jobInstanceId, Instant finishedAt);
}
