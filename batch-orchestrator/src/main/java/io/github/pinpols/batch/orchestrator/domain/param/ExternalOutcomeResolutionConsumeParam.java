package io.github.pinpols.batch.orchestrator.domain.param;

/** 外部动作开始前按原任务版本和本次执行任务消费绑定根请求的授权。 */
public record ExternalOutcomeResolutionConsumeParam(
    String tenantId, Long sourceTaskId, Long sourceVersion, Long retryTaskId) {}
