package io.github.pinpols.batch.orchestrator.infrastructure.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.orchestrator.domain.statemachine.StateTransition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("默认生命周期事件映射:起始状态与事件解析,事件文本归一化,终态保护与非法入参拒绝")
class DefaultLifecycleEventMapperTest {

  private DefaultLifecycleEventMapper<Object> mapper;

  @BeforeEach
  void setUp() {
    mapper = new DefaultLifecycleEventMapper<>();
  }

  @Test
  @DisplayName("以字符串给出目标对象时解析出起始状态与归一化事件,并按内置流转得到运行中状态")
  void shouldResolveStringTargetAsState() {
    StateTransition transition = mapper.map("WAITING", "START");
    assertThat(transition.fromState()).isEqualTo("WAITING");
    assertThat(transition.event()).isEqualTo("START");
    assertThat(transition.toState()).isEqualTo("RUNNING");
  }

  @Test
  @DisplayName("以状态枚举给出目标对象时,成功事件映射为成功终态")
  void shouldResolveEnumTargetAsState() {
    assertThat(mapper.map(TaskStatus.READY, "SUCCESS").toState()).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("事件文本去除首尾空白并转为大写,仅含空白的事件则保持原状态不变")
  void shouldNormalizeEvent() {
    assertThat(mapper.map("READY", "  start  ").event()).isEqualTo("START");
    assertThat(mapper.map("READY", " ").toState()).isEqualTo("READY");
  }

  @Test
  @DisplayName("失败事件映射为失败终态,试运行成功与试运行失败事件各自映射为同名终态")
  void shouldMapSupportedTerminalEvents() {
    assertThat(mapper.map("RUNNING", "FAIL").toState()).isEqualTo("FAILED");
    assertThat(mapper.map("RUNNING", "SUCCESS_DRY_RUN").toState()).isEqualTo("SUCCESS_DRY_RUN");
    assertThat(mapper.map("RUNNING", "FAILED_DRY_RUN").toState()).isEqualTo("FAILED_DRY_RUN");
  }

  @Test
  @DisplayName("拼写错误的事件名抛出参数异常并提示不支持,而不是静默忽略")
  void shouldRejectUnknownEventsInsteadOfSilentlyIgnoringTypo() {
    assertThatThrownBy(() -> mapper.map("WAITING", "SUCESS"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unsupported lifecycle event");
  }

  @Test
  @DisplayName("已处于终态时忽略后续事件,起始状态保持不变")
  void shouldRefuseTransitionFromTerminalState() {
    assertThat(mapper.map("TERMINATED", "SUCCEED").toState()).isEqualTo("TERMINATED");
    assertThat(mapper.map("SUCCESS", "RUN").toState()).isEqualTo("SUCCESS");
    assertThat(mapper.map("CANCELLED", "FAIL").toState()).isEqualTo("CANCELLED");
  }

  @Test
  @DisplayName("终态上的终止事件允许自环,状态仍停留在成功终态")
  void shouldAllowTerminalSelfLoop() {
    assertThat(mapper.map("SUCCESS", "SUCCEED").toState()).isEqualTo("SUCCESS");
  }

  @Test
  @DisplayName("目标对象未实现显式状态契约时抛出参数异常并说明可接受的类型")
  void shouldRejectTargetWithoutExplicitStatusContract() {
    assertThatThrownBy(() -> mapper.map(new Object(), "START"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("String, Enum or Stateful");
  }
}
