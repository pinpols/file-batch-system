package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.Test;

class WorkflowJoinModeTest {

  @Test
  void fromCode_knownCodes_returnsCorrectValue() {
    assertThat(WorkflowJoinMode.fromCode("ALL")).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCode("ANY")).isEqualTo(WorkflowJoinMode.ANY);
    assertThat(WorkflowJoinMode.fromCode("N_OF")).isEqualTo(WorkflowJoinMode.N_OF);
  }

  @Test
  void fromCode_caseInsensitive() {
    assertThat(WorkflowJoinMode.fromCode("all")).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCode("Any")).isEqualTo(WorkflowJoinMode.ANY);
    assertThat(WorkflowJoinMode.fromCode("n_of")).isEqualTo(WorkflowJoinMode.N_OF);
  }

  @Test
  void fromCode_nullOrBlank_returnsAll() {
    assertThat(WorkflowJoinMode.fromCode(null)).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCode("")).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCode("   ")).isEqualTo(WorkflowJoinMode.ALL);
  }

  @Test
  void fromCode_unknownCode_throwsBizException() {
    assertThatThrownBy(() -> WorkflowJoinMode.fromCode("NONE"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("unknown_workflow_join_mode_code");
  }

  @Test
  void fromCodeOrDefault_unknownCode_returnsAll() {
    assertThat(WorkflowJoinMode.fromCodeOrDefault("BOGUS")).isEqualTo(WorkflowJoinMode.ALL);
  }

  @Test
  void fromCodeOrDefault_nullOrBlank_returnsAll() {
    assertThat(WorkflowJoinMode.fromCodeOrDefault(null)).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCodeOrDefault("")).isEqualTo(WorkflowJoinMode.ALL);
  }

  @Test
  void codeAndLabel_notBlank() {
    for (WorkflowJoinMode mode : WorkflowJoinMode.values()) {
      assertThat(mode.code()).isNotBlank();
      assertThat(mode.label()).isNotBlank();
    }
  }
}
