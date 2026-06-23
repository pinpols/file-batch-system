package io.github.pinpols.batch.orchestrator.controller.request;

/**
 * ADR-046 P2 切片 2.2:批量上报的逐项结果。
 *
 * <p>{@code ok=true} 表示该 partition 的 outcome 已成功推进;{@code ok=false} 时 {@code error} 给原因 (版本 CAS 冲突
 * / 校验失败 / 其它),worker 只需重报失败项 —— **批内某项失败不影响其余项**(各自独立事务)。
 */
public record TaskReportResultPayload(Long taskId, boolean ok, String error) {}
