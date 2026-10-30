package io.github.pinpols.batch.console.application.ops.response;

import java.util.List;

/** 批量重试实例全部 FAILED 分区的结果。 */
public record ConsoleRetryFailedPartitionsResponse(
    Long id,
    String instanceNo,
    Integer requested,
    Integer retried,
    Integer conflicts,
    List<Long> partitionIds) {}
