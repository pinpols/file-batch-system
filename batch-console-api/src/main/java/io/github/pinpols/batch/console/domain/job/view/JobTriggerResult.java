package io.github.pinpols.batch.console.domain.job.view;

/** 触发结果的封闭联合：执行返回实例号，试运行返回校验结果，对应 OpenAPI oneOf。 */
public sealed interface JobTriggerResult permits TriggeredJobResult, DryRunTriggerResult {}
