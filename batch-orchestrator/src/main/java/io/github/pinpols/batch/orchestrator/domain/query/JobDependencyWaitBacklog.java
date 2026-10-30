package io.github.pinpols.batch.orchestrator.domain.query;

/** 仅供低基数运维指标使用的跨租户汇总。 */
public record JobDependencyWaitBacklog(long waitingCount, long oldestAgeSeconds) {}
