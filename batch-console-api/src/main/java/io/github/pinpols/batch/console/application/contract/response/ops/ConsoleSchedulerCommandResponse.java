package io.github.pinpols.batch.console.application.contract.response.ops;

/** 调度器状态 / 全局暂停恢复动作结果（仅 {@code status} 一个字段）。 */
public record ConsoleSchedulerCommandResponse(String status) {}
