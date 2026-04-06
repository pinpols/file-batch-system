package com.example.batch.worker.core.domain;

import java.util.Map;

public record PipelineStepTemplate(
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
     * 从参数对象创建 PipelineStepTemplate。
     */
    public static PipelineStepTemplate from(PipelineStepTemplateParam param) {
        return new PipelineStepTemplate(
                param.stepCode(),
                param.stepName(),
                param.stageCode(),
                param.stepOrder(),
                param.implCode(),
                param.stepParams(),
                param.timeoutSeconds(),
                param.retryPolicy(),
                param.retryMaxCount(),
                param.enabled()
        );
    }
}
