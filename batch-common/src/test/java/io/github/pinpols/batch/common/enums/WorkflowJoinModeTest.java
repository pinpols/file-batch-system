package io.github.pinpols.batch.common.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("WorkflowJoinMode 工作流汇聚模式: 编码解析的大小写容忍 / 缺省回退与非法编码报错")
class WorkflowJoinModeTest {

  @Test
  @DisplayName("已知汇聚模式编码解析为全部满足 / 任一满足 / 指定数量")
  void fromCode_knownCodes_returnsCorrectValue() {
    assertThat(WorkflowJoinMode.fromCode("ALL")).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCode("ANY")).isEqualTo(WorkflowJoinMode.ANY);
    assertThat(WorkflowJoinMode.fromCode("N_OF")).isEqualTo(WorkflowJoinMode.N_OF);
  }

  @Test
  @DisplayName("编码解析忽略大小写,全小写与首字母大写同样命中")
  void fromCode_caseInsensitive() {
    assertThat(WorkflowJoinMode.fromCode("all")).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCode("Any")).isEqualTo(WorkflowJoinMode.ANY);
    assertThat(WorkflowJoinMode.fromCode("n_of")).isEqualTo(WorkflowJoinMode.N_OF);
  }

  @Test
  @DisplayName("空值 / 空串 / 纯空白 解析回退为全部满足")
  void fromCode_nullOrBlank_returnsAll() {
    assertThat(WorkflowJoinMode.fromCode(null)).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCode("")).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCode("   ")).isEqualTo(WorkflowJoinMode.ALL);
  }

  @Test
  @DisplayName("无法识别的汇聚模式编码抛出业务异常而非静默回退")
  void fromCode_unknownCode_throwsBizException() {
    assertThatThrownBy(() -> WorkflowJoinMode.fromCode("NONE"))
        .isInstanceOf(BizException.class)
        .hasMessageContaining("unknown_workflow_join_mode_code");
  }

  @Test
  @DisplayName("宽松解析遇到非法编码时回退为全部满足")
  void fromCodeOrDefault_unknownCode_returnsAll() {
    assertThat(WorkflowJoinMode.fromCodeOrDefault("BOGUS")).isEqualTo(WorkflowJoinMode.ALL);
  }

  @Test
  @DisplayName("宽松解析遇到空值或空串时回退为全部满足")
  void fromCodeOrDefault_nullOrBlank_returnsAll() {
    assertThat(WorkflowJoinMode.fromCodeOrDefault(null)).isEqualTo(WorkflowJoinMode.ALL);
    assertThat(WorkflowJoinMode.fromCodeOrDefault("")).isEqualTo(WorkflowJoinMode.ALL);
  }

  @Test
  @DisplayName("每个汇聚模式的编码与展示名称都非空")
  void codeAndLabel_notBlank() {
    for (WorkflowJoinMode mode : WorkflowJoinMode.values()) {
      assertThat(mode.code()).isNotBlank();
      assertThat(mode.label()).isNotBlank();
    }
  }
}
