package io.github.pinpols.batch.console.application.ops.response;

/**
 * 工作流运行实例生命周期动作结果（cancel / terminate / pause / resume）。
 *
 * <p>编排内部 {@code /internal/workflow-runs/{id}/{action}} 恒返回 {@code Map.of("id", "status")} 两个固定键
 * （{@code id} 为 workflow_run 主键，{@code status} 为翻转后的目标状态）。Console 代理直接将响应反序列化为该类型。
 */
public record ConsoleWorkflowRunActionResponse(Long id, String status) {}
