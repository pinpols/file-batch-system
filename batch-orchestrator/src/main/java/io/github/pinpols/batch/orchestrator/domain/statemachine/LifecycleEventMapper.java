package io.github.pinpols.batch.orchestrator.domain.statemachine;

/** 把领域生命周期事件映射为候选目标状态；最终并发合法性仍由持久层 CAS 约束。 */
public interface LifecycleEventMapper<T> {

  StateTransition map(T target, String event);
}
