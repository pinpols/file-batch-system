package io.github.pinpols.batch.orchestrator.application.scheduler;

/**
 * 已提交的 task 终态可能释放租户、队列或公平组容量。
 *
 * <p>事件只做本地快速唤醒；周期性 WAITING 扫描仍是跨实例与事件遗漏时的最终兜底。
 */
public record WaitingCapacityReleasedEvent(String tenantId) {}
