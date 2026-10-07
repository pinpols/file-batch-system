package io.github.pinpols.batch.console.domain.job.application.contract.response;

/** 触发器运维动作（register / unregister / pause / resume）结果。 */
public record ConsoleTriggerActionResponse(String tenantId, String jobCode, String status) {}
