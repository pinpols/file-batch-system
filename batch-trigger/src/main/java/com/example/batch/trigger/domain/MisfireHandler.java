package com.example.batch.trigger.domain;

/**
 * 调度错过触发（Misfire）处理接口，定义当触发器因系统宕机或延迟而未能按时触发时的补偿契约。 实现类须根据任务的补单策略（{@code
 * catchUpPolicy}）决定是自动补单、创建待审批记录还是忽略； {@code triggerName} 为调度器内部的唯一标识，实现类应自行解析出租户和任务信息。
 */
public interface MisfireHandler {

  void handle(String triggerName);
}
