package io.github.pinpols.batch.trigger.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Workflow、兼容 Job 或租户级触发器操作结果；未指定的作用域字段不输出。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TriggerActionResult(
    String tenantId, String jobCode, String workflowCode, String status) {

  public TriggerActionResult(String tenantId, String jobCode, String status) {
    this(tenantId, jobCode, null, status);
  }
}
