package com.example.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.worker.core.domain.PipelineStepDefinition;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PipelineStepFlowSupportTest {

    @Test
    void firstStepShouldReturnNullForEmpty() {
        assertThat(PipelineStepFlowSupport.firstStep(null)).isNull();
        assertThat(PipelineStepFlowSupport.firstStep(List.of())).isNull();
    }

    @Test
    void maxTransitionGuardShouldScaleWithStepCount() {
        assertThat(PipelineStepFlowSupport.maxTransitionGuard(List.of())).isEqualTo(16);
        assertThat(PipelineStepFlowSupport.maxTransitionGuard(List.of(step("a"), step("b")))).isEqualTo(16);
        assertThat(PipelineStepFlowSupport.maxTransitionGuard(List.of(step("a"), step("b"), step("c"), step("d"), step("e"))))
                .isEqualTo(20);
    }

    @Test
    void shouldFollowLinearOrderWhenSuccessful() {
        PipelineStepDefinition s1 = step("S1");
        PipelineStepDefinition s2 = step("S2");
        List<PipelineStepDefinition> steps = List.of(s1, s2);
        assertThat(PipelineStepFlowSupport.resolveNextStep(s1, true, steps, new HashMap<>())).isEqualTo(s2);
        assertThat(PipelineStepFlowSupport.resolveNextStep(s2, true, steps, new HashMap<>())).isNull();
    }

    @Test
    void shouldStopOnTerminalSuccessFlag() {
        PipelineStepDefinition terminal = new PipelineStepDefinition(
                1L, 1L, "T", "t", "ST", 1, "noop", Map.of("terminalOnSuccess", true), 60, "FIXED", 0, true
        );
        List<PipelineStepDefinition> steps = List.of(terminal, step("NEXT"));
        assertThat(PipelineStepFlowSupport.resolveNextStep(terminal, true, steps, new HashMap<>())).isNull();
    }

    @Test
    void shouldUseExplicitNextFromAttributes() {
        PipelineStepDefinition s1 = step("S1");
        PipelineStepDefinition s2 = step("S2");
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(PipelineRuntimeKeys.PIPELINE_NEXT_STEP_CODE, "S2");
        assertThat(PipelineStepFlowSupport.resolveNextStep(s1, true, List.of(s1, s2), attrs)).isEqualTo(s2);
        assertThat(attrs).doesNotContainKey(PipelineRuntimeKeys.PIPELINE_NEXT_STEP_CODE);
    }

    private static PipelineStepDefinition step(String code) {
        return new PipelineStepDefinition(
                1L, 1L, code, code, code, 1, "noop", Map.of(), 60, "FIXED", 0, true
        );
    }
}
