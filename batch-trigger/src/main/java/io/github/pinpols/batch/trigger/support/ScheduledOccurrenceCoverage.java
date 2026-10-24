package io.github.pinpols.batch.trigger.support;

import java.time.Instant;

/** 无根 Workflow 节点作为独立 Job Instance 执行。保留所属节点 */
public record ScheduledOccurrenceCoverage(Instant coverageFrom, Instant coverageThrough) {}
