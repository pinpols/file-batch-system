package io.github.pinpols.batch.orchestrator.domain.statemachine;

public record StateTransition(String fromState, String event, String toState) {}
