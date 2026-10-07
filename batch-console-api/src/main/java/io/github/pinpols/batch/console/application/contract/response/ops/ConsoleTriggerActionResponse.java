package io.github.pinpols.batch.console.application.contract.response.ops;

/** 触发器运维动作（register / unregister / pause / resume）结果。 */
public record ConsoleTriggerActionResponse(String tenantId, String jobCode, String status) {}
