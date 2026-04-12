package com.example.batch.orchestrator.infrastructure.statemachine;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.enums.TaskStatus;
import com.example.batch.orchestrator.domain.statemachine.StateTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultStateMachineTest {

  private DefaultStateMachine<Object> machine;

  @BeforeEach
  void setUp() {
    machine = new DefaultStateMachine<>();
  }

  @Test
  void shouldResolveStringTargetAsState() {
    StateTransition t = machine.transition("WAITING", "START");
    assertThat(t.fromState()).isEqualTo("WAITING");
    assertThat(t.event()).isEqualTo("START");
    assertThat(t.toState()).isEqualTo("RUNNING");
  }

  @Test
  void shouldResolveEnumTargetAsState() {
    StateTransition t = machine.transition(TaskStatus.READY, "SUCCESS");
    assertThat(t.fromState()).isEqualTo("READY");
    assertThat(t.toState()).isEqualTo("SUCCESS");
  }

  @Test
  void shouldNormalizeEventToUpperCaseAndTrim() {
    StateTransition t = machine.transition("READY", "  start  ");
    assertThat(t.event()).isEqualTo("START");
    assertThat(t.toState()).isEqualTo("RUNNING");
  }

  @Test
  void shouldUseNoopWhenEventBlank() {
    StateTransition t = machine.transition("READY", " ");
    assertThat(t.event()).isEqualTo("NOOP");
    assertThat(t.toState()).isEqualTo("READY");
  }

  @Test
  void shouldMapFailEventsToFailed() {
    StateTransition t = machine.transition("RUNNING", "FAIL");
    assertThat(t.toState()).isEqualTo("FAILED");
  }

  @Test
  void shouldMapReadyEventToReadyState() {
    StateTransition t = machine.transition("CREATED", "READY");
    assertThat(t.toState()).isEqualTo("READY");
  }

  @Test
  void shouldLeaveUnknownEventsUnchanged() {
    StateTransition t = machine.transition("WAITING", "CUSTOM");
    assertThat(t.toState()).isEqualTo("WAITING");
  }
}
