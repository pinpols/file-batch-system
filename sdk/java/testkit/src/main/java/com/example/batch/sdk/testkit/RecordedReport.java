package com.example.batch.sdk.testkit;

import java.util.Map;

/**
 * {@link FakeBatchPlatform} 捕获的一次 {@code POST /internal/tasks/{id}/report} 调用快照。
 *
 * <p>测试据此断言 handler 执行结果:{@code success} / {@code message} / {@code outputs} 字段集对齐主项目 {@code
 * TaskExecutionReportDto}。
 *
 * @param taskId orchestrator 任务主键
 * @param success handler 返回的成功标志
 * @param message 结果摘要 / 错误文本
 * @param outputs 业务输出(handler {@code SdkTaskResult.output()});平台落 runtimeAttributes 透传下游
 * @param errorCode 失败时异常类名(handler 抛异常或 {@code fail(Throwable)});成功时 null
 * @param raw 原始 report body(其它字段断言用)
 */
public record RecordedReport(
    Long taskId,
    boolean success,
    String message,
    Map<String, Object> outputs,
    String errorCode,
    Map<String, Object> raw) {}
