package com.example.batch.orchestrator.domain.statemachine;

public interface StateMachine<T> {

    StateTransition transition(T target, String event);
}
