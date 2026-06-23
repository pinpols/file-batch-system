package io.github.pinpols.batch.orchestrator.domain.statemachine;

public interface StateMachine<T> {

  StateTransition transition(T target, String event);
}
