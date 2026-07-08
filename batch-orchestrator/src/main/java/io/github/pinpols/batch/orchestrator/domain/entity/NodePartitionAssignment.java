package io.github.pinpols.batch.orchestrator.domain.entity;

/**
 * job_task → (job_partition_id, workflowNodeCode) 的轻量投影。
 *
 * <p>perf: {@code DefaultTaskOutcomeService.advancePartitionAndInstance} 只需要「哪个 partition 属于哪个 DAG
 * 节点」这一映射来做按节点的分区计数,并不需要整行 task(尤其是 {@code task_payload / effective_parameters} 两个大 JSON 列)。
 * 用这个投影替代 {@code selectByQuery(select *)} 全量拉行,避免 report 链路的 O(N) 大列读。
 *
 * <p>{@code nodeCode} 直接由 SQL 侧 {@code task_payload ->> 'workflowNodeCode'} 抽取,与原 {@code
 * TaskOutcomePayloadSupport.payloadStringValue(taskPayload, "workflowNodeCode")} 对齐;为空/缺失时由 Java
 * 侧沿用原有 fallback(单活动节点 / 当前节点)。
 */
public record NodePartitionAssignment(Long jobPartitionId, String nodeCode) {}
