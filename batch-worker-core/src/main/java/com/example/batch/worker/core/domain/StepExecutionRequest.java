package com.example.batch.worker.core.domain;

import java.util.Map;

/**
 * Pipeline 单步执行请求，由调度层在触发具体 StepHandler 前构造。
 * 携带租户、Job 编码、步骤编码及运行时上下文 Map，
 * 是 StepHandler 接口的统一入参，避免方法参数扩散。
 */
public record StepExecutionRequest(
    String tenantId,
    String jobCode,
    String stepCode,
    String workerId,
    Map<String, Object> context) {}
