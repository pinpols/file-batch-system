package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("流水线步骤流转支持: 下一步解析与流转次数上限")
class PipelineStepFlowSupportTest {

  @Test
  @DisplayName("步骤列表为空或未提供时, 取首个步骤返回空")
  void shouldReturnNullFirstStep_whenStepsMissingOrEmpty() {
    assertThat(PipelineStepFlowSupport.firstStep(null)).isNull();
    assertThat(PipelineStepFlowSupport.firstStep(List.of())).isNull();
  }

  @Test
  @DisplayName("流转次数上限随步骤数量增长, 步骤较少时取固定下限")
  void shouldScaleTransitionGuardWithStepCount() {
    assertThat(PipelineStepFlowSupport.maxTransitionGuard(List.of())).isEqualTo(16);
    assertThat(PipelineStepFlowSupport.maxTransitionGuard(List.of(step("a"), step("b"))))
        .isEqualTo(16);
    assertThat(PipelineStepFlowSupport.maxTransitionGuard(
            List.of(step("a"), step("b"), step("c"), step("d"), step("e"))))
        .isEqualTo(20);
  }

  @Test
  @DisplayName("步骤全部成功时按线性顺序推进, 到达最后一步后没有下一步")
  void shouldFollowLinearOrderWhenSuccessful() {
    PipelineStepDefinition s1 = step("S1");
    PipelineStepDefinition s2 = step("S2");
    List<PipelineStepDefinition> steps = List.of(s1, s2);
    assertThat(PipelineStepFlowSupport.resolveNextStep(s1, true, steps, new HashMap<>()))
        .isEqualTo(s2);
    assertThat(PipelineStepFlowSupport.resolveNextStep(s2, true, steps, new HashMap<>()))
        .isNull();
  }

  @Test
  @DisplayName("步骤标记为成功即终止时, 即使后面还有步骤也结束流转")
  void shouldStopOnTerminalSuccessFlag() {
    PipelineStepDefinition terminal = new PipelineStepDefinition(
        1L, 1L, "T", "t", "ST", 1, "noop", Map.of("terminalOnSuccess", true), 60, "FIXED", 0, true);
    List<PipelineStepDefinition> steps = List.of(terminal, step("NEXT"));
    assertThat(PipelineStepFlowSupport.resolveNextStep(terminal, true, steps, new HashMap<>()))
        .isNull();
  }

  @Test
  @DisplayName("运行时属性中显式指定下一步编码时优先跳转, 并在读取后移除该属性")
  void shouldUseExplicitNextFromAttributes() {
    PipelineStepDefinition s1 = step("S1");
    PipelineStepDefinition s2 = step("S2");
    Map<String, Object> attrs = new HashMap<>();
    attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STEP_CODE, "S2");
    assertThat(PipelineStepFlowSupport.resolveNextStep(s1, true, List.of(s1, s2), attrs))
        .isEqualTo(s2);
    assertThat(attrs).doesNotContainKey(PipelineRuntimeKeys.PIPELINE_NEXT_STEP_CODE);
  }

  @Test
  @DisplayName("跳过空步骤与缺少编码的步骤, 定位到下一个有效步骤")
  void shouldIgnoreNullStepsAndStepsWithoutCode() {
    PipelineStepDefinition current = step("S1");
    PipelineStepDefinition withoutCode = step(null);
    assertThat(PipelineStepFlowSupport.resolveNextStep(
            current, true, Arrays.asList(withoutCode, current, step("S2")), new HashMap<>()))
        .extracting(PipelineStepDefinition::stepCode)
        .isEqualTo("S2");
    assertThat(PipelineStepFlowSupport.resolveNextStep(
            current, true, Arrays.asList(null, current, step("S2")), new HashMap<>()))
        .extracting(PipelineStepDefinition::stepCode)
        .isEqualTo("S2");
  }

  private static PipelineStepDefinition step(String code) {
    return new PipelineStepDefinition(
        1L, 1L, code, code, code, 1, "noop", Map.of(), 60, "FIXED", 0, true);
  }
}
