package com.example.batch.worker.core.support;

/**
 * 阶段执行结果的标准失败码。
 *
 * <ul>
 *   <li>{@link #BUSINESS_ERROR} — 步骤抛出的预期业务异常（BizException）</li>
 *   <li>{@link #INFRA_ERROR} — 意外的基础设施/运行时异常</li>
 *   <li>{@link #STEP_NOT_FOUND} — 给定 implCode 未注册任何步骤实现</li>
 *   <li>{@link #PIPELINE_STEP_MISSING} — pipeline 步骤定义缺失或为空</li>
 * </ul>
 */
public enum StageFailureCode {

    BUSINESS_ERROR,
    INFRA_ERROR,
    STEP_NOT_FOUND,
    PIPELINE_STEP_MISSING
}
