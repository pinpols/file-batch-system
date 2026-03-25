package com.example.batch.orchestrator.domain.statemachine;

/**
 * Marker interface for entities that carry an explicit status field.
 *
 * <p>Implementing this interface replaces the reflection-based status resolution in
 * {@link com.example.batch.orchestrator.infrastructure.statemachine.DefaultStateMachine}.
 * Any entity that is passed to {@code StateMachine.transition()} should implement this
 * interface so status lookup is compile-time safe — renaming the field will cause a
 * compile error rather than a silent runtime fallback.
 */
public interface Stateful {

    /** Returns the current status string of this entity. */
    String getStatus();
}
