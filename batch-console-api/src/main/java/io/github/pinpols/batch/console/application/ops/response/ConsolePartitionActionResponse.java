package io.github.pinpols.batch.console.application.ops.response;

/** 分区级动作（cancel / retry）结果。 */
public record ConsolePartitionActionResponse(Long id, String status) {}
