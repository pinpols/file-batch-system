package io.github.pinpols.batch.console.domain.job.view;

import com.fasterxml.jackson.annotation.JsonValue;

/** 执行触发的实例号；JSON 保持字符串，使现有运维脚本可继续消费。 */
public record TriggeredJobResult(@JsonValue String instanceNo) implements JobTriggerResult {}
