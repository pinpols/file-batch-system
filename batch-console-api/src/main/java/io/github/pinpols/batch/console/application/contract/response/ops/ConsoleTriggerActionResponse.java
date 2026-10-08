package io.github.pinpols.batch.console.application.contract.response.ops;

/** 触发器运维动作（register / unregister / pause / resume）结果。 */
public record ConsoleTriggerActionResponse(String tenantId, String jobCode, String status) {

  /** 下游未返回动作结果时的空响应。 */
  public static ConsoleTriggerActionResponse empty() {
    return new ConsoleTriggerActionResponse(null, null, null);
  }
}
