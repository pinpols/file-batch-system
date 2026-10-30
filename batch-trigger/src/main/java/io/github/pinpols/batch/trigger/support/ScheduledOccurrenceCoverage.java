package io.github.pinpols.batch.trigger.support;

import java.time.Instant;

/** 已计划调度发生项的时间覆盖边界。 */
public record ScheduledOccurrenceCoverage(Instant coverageFrom, Instant coverageThrough) {}
