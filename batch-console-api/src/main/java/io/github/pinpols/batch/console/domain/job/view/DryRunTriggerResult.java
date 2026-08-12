package io.github.pinpols.batch.console.domain.job.view;

import java.util.List;

/** 作业 dry-run 的固定校验结果；动态业务参数不属于该响应。 */
public record DryRunTriggerResult(
    Boolean dryRun,
    String tenantId,
    String jobCode,
    String bizDate,
    Boolean valid,
    List<String> errors) {}
