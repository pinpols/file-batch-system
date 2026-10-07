package io.github.pinpols.batch.trigger.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 单作业或租户级触发器操作结果；未指定的作用域字段不输出。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TriggerActionResult(String tenantId, String jobCode, String status) {}
