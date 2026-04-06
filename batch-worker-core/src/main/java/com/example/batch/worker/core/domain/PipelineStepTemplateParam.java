package com.example.batch.worker.core.domain;

import java.util.Map;

/**
 * 流水线步骤模板参数对象。
 * 封装 PipelineStepTemplate 的构造参数，遵循编码规范（参数 ≥ 7 必须封装）。
 */
public record PipelineStepTemplateParam(
        String stepCode,
        String stepName,
        String stageCode,
        Integer stepOrder,
        String implCode,
        Map<String, Object> stepParams,
        Integer timeoutSeconds,
        String retryPolicy,
        Integer retryMaxCount,
        boolean enabled
) {
    /**
     * 便利构造方法，支持常见参数组合。
     */
    public static PipelineStepTemplateParam of(
            String stepCode,
            String stepName,
            String stageCode,
            Integer stepOrder,
            String implCode,
            Map<String, Object> stepParams,
            Integer timeoutSeconds,
            String retryPolicy,
            Integer retryMaxCount,
            boolean enabled
    ) {
        return new PipelineStepTemplateParam(
                stepCode,
                stepName,
                stageCode,
                stepOrder,
                implCode,
                stepParams,
                timeoutSeconds,
                retryPolicy,
                retryMaxCount,
                enabled
        );
    }

    /**
     * 转换为 PipelineStepTemplate。
     */
    public PipelineStepTemplate toTemplate() {
        return new PipelineStepTemplate(
                stepCode,
                stepName,
                stageCode,
                stepOrder,
                implCode,
                stepParams,
                timeoutSeconds,
                retryPolicy,
                retryMaxCount,
                enabled
        );
    }
}

