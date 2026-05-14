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

  @Test
  void shouldRefuseIllegalTransitionFromTerminalState() {
    // TERMINATED 不应被 SUCCEED 复活
    StateTransition succeedFromTerminated = machine.transition("TERMINATED", "SUCCEED");
    assertThat(succeedFromTerminated.toState()).isEqualTo("TERMINATED");

    // SUCCESS 已终态，RUN 不应再驱动回 RUNNING
    StateTransition runFromSuccess = machine.transition("SUCCESS", "RUN");
    assertThat(runFromSuccess.toState()).isEqualTo("SUCCESS");

    // CANCELLED 不应被 FAIL 切换终态
    StateTransition failFromCancelled = machine.transition("CANCELLED", "FAIL");
    assertThat(failFromCancelled.toState()).isEqualTo("CANCELLED");
  }

  @Test
  void shouldAllowTerminalSelfLoopAsIdempotent() {
    // 同终态重复上报 → 幂等保持
    StateTransition t = machine.transition("SUCCESS", "SUCCEED");
    assertThat(t.toState()).isEqualTo("SUCCESS");
  }
}
