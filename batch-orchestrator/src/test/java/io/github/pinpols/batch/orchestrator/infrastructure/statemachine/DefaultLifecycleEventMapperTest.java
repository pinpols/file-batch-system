package io.github.pinpols.batch.orchestrator.infrastructure.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultLifecycleEventMapperTest {

  private DefaultLifecycleEventMapper<Object> mapper;

  @BeforeEach
  void setUp() {
    mapper = new DefaultLifecycleEventMapper<>();
  }

  @Test
  void shouldResolveStringTargetAsState() {
    StateTransition transition = mapper.map("WAITING", "START");
    assertThat(transition.fromState()).isEqualTo("WAITING");
    assertThat(transition.event()).isEqualTo("START");
    assertThat(transition.toState()).isEqualTo("RUNNING");
  }

  @Test
  void shouldResolveEnumTargetAsState() {
    assertThat(mapper.map(TaskStatus.READY, "SUCCESS").toState()).isEqualTo("SUCCESS");
  }

  @Test
  void shouldNormalizeEvent() {
    assertThat(mapper.map("READY", "  start  ").event()).isEqualTo("START");
    assertThat(mapper.map("READY", " ").toState()).isEqualTo("READY");
  }

  @Test
  void shouldMapSupportedTerminalEvents() {
    assertThat(mapper.map("RUNNING", "FAIL").toState()).isEqualTo("FAILED");
    assertThat(mapper.map("RUNNING", "SUCCESS_DRY_RUN").toState()).isEqualTo("SUCCESS_DRY_RUN");
    assertThat(mapper.map("RUNNING", "FAILED_DRY_RUN").toState()).isEqualTo("FAILED_DRY_RUN");
  }

  @Test
  void shouldRejectUnknownEventsInsteadOfSilentlyIgnoringTypo() {
    assertThatThrownBy(() -> mapper.map("WAITING", "SUCESS"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported lifecycle event");
  }

  @Test
  void shouldRefuseTransitionFromTerminalState() {
    assertThat(mapper.map("TERMINATED", "SUCCEED").toState()).isEqualTo("TERMINATED");
    assertThat(mapper.map("SUCCESS", "RUN").toState()).isEqualTo("SUCCESS");
    assertThat(mapper.map("CANCELLED", "FAIL").toState()).isEqualTo("CANCELLED");
  }

  @Test
  void shouldAllowTerminalSelfLoop() {
    assertThat(mapper.map("SUCCESS", "SUCCEED").toState()).isEqualTo("SUCCESS");
  }

  @Test
  void shouldRejectTargetWithoutExplicitStatusContract() {
    assertThatThrownBy(() -> mapper.map(new Object(), "START"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("String, Enum or Stateful");
  }
}
