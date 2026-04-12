package com.example.batch.orchestrator.domain.statemachine;

/**
 * 携带显式状态字段的实体标记接口，替代 {@link
 * com.example.batch.orchestrator.infrastructure.statemachine.DefaultStateMachine}
 * 中基于反射的状态解析。实现此接口可保证状态查找在编译期安全，字段重命名将触发编译错误而非静默回退。
 */
public interface Stateful {

  /** 返回当前实体的状态字符串。 */
  String getStatus();
}
