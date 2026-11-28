package io.github.pinpols.batch.worker.core.support;

import java.util.List;
import java.util.Map;

import io.github.pinpols.batch.common.utils.EmptyValues;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;

/**
 * Pipeline 步骤流转工具类：封装"确定下一步"的路由逻辑。
 *
 * <p><b>路由优先级</b>（从高到低）：
 *
 * <ol>
 *   <li>运行时属性 {@code PIPELINE_NEXT_STEP_CODE} / {@code PIPELINE_NEXT_STAGE_CODE}（由步骤主动写入，读取后立即删除）
 *   <li>当前步骤参数 {@code onSuccessNextStepCode} / {@code onFailureNextStepCode} 等显式路由配置
 *   <li>成功时按步骤列表顺序顺延到下一步
 * </ol>
 *
 * <p>{@link #popSelector} 在读取属性的同时执行 remove——具有副作用，只可调用一次。
 *
 * <p>{@link #maxTransitionGuard} 返回 {@code steps.size() * 4}（最小 16）作为循环转换上限， 防止 pipeline
 * 因循环跳转导致无限循环。
 */
public final class PipelineStepFlowSupport {

    private PipelineStepFlowSupport() {}

    public static PipelineStepDefinition firstStep(List<PipelineStepDefinition> steps) {
        return EmptyValues.isEmpty(steps) ? null : steps.get(0);
    }

    public static int maxTransitionGuard(List<PipelineStepDefinition> steps) {
        return Math.max((steps == null ? 0 : steps.size()) * 4, 16);
    }

    public static PipelineStepDefinition resolveNextStep(
            PipelineStepDefinition current,
            boolean success,
            List<PipelineStepDefinition> steps,
            Map<String, Object> attributes) {
        boolean terminalOnSuccess =
                current != null && success && current.booleanParam("terminalOnSuccess", "stopOnSuccess");
        if (terminalOnSuccess) {
            return null;
        }
        boolean terminalOnFailure =
                current != null && !success && current.booleanParam("terminalOnFailure", "stopOnFailure");
        if (terminalOnFailure) {
            return null;
        }
        String selector = popSelector(attributes);
        if (!EmptyValues.hasText(selector) && current != null) {
            selector = success
                    ? current.textParam(
                            "onSuccessNextStepCode", "onSuccessNextStageCode", "nextStepCode", "nextStageCode")
                    : current.textParam("onFailureNextStepCode", "onFailureNextStageCode");
        }
        if (EmptyValues.hasText(selector)) {
            PipelineStepDefinition explicitNext = findByStepCodeOrStageCode(steps, selector);
            if (explicitNext != null) {
                return explicitNext;
            }
        }
        boolean stepFlowCannotAdvance = !success || current == null || EmptyValues.isEmpty(steps);
        if (stepFlowCannotAdvance) {
            return null;
        }
        for (int index = 0; index < steps.size(); index++) {
            PipelineStepDefinition step = steps.get(index);
            if (step != null && stepCodeMatches(step, current)) {
                return index + 1 < steps.size() ? steps.get(index + 1) : null;
            }
        }
        return null;
    }

    private static String popSelector(Map<String, Object> attributes) {
        String stepCode = popText(attributes, PipelineRuntimeKeys.PIPELINE_NEXT_STEP_CODE);
        return EmptyValues.hasText(stepCode)
                ? stepCode
                : popText(attributes, PipelineRuntimeKeys.PIPELINE_NEXT_STAGE_CODE);
    }

    private static String popText(Map<String, Object> attributes, String key) {
        if (attributes == null || !attributes.containsKey(key)) {
            return null;
        }
        Object value = attributes.remove(key);
        if (value instanceof String text && EmptyValues.hasText(text)) {
            return text;
        }
        if (value != null) {
            String text = String.valueOf(value);
            if (EmptyValues.hasText(text) && !"null".equalsIgnoreCase(text)) {
                return text;
            }
        }
        return null;
    }

    private static PipelineStepDefinition findByStepCodeOrStageCode(
            List<PipelineStepDefinition> steps, String selector) {
        if (steps == null || !EmptyValues.hasText(selector)) {
            return null;
        }
        for (PipelineStepDefinition step : steps) {
            boolean selectorMatchesStepOrStage = step != null
                    && (selector.equalsIgnoreCase(step.stepCode()) || selector.equalsIgnoreCase(step.stageCode()));
            if (selectorMatchesStepOrStage) {
                return step;
            }
        }
        return null;
    }

    private static boolean stepCodeMatches(PipelineStepDefinition step, PipelineStepDefinition current) {
        return current != null
                && EmptyValues.hasText(current.stepCode())
                && current.stepCode().equals(step.stepCode());
    }
}
