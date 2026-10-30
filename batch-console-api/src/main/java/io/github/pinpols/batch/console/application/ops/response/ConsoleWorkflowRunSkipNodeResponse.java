package io.github.pinpols.batch.console.application.ops.response;

/**
 * 工作流运行实例跳过节点结果（{@code POST /api/console/workflow-runs/{id}/skip-node}）。
 *
 * <p>编排内部 {@code /internal/workflow-runs/{id}/skip-node} 恒返回 {@code Map.of("id", "nodeCode",
 * "nodeStatus")} 三个固定键（{@code nodeStatus} 恒为 {@code "SKIPPED"}）。Console 代理直接将响应反序列化为该类型。
 */
public record ConsoleWorkflowRunSkipNodeResponse(Long id, String nodeCode, String nodeStatus) {}
