package io.github.pinpols.batch.orchestrator.domain.entity;

/**
 * job_partition → (id, partition_status) 的轻量投影。
 *
 * <p>perf(#5): {@code DefaultTaskOutcomeService.advancePartitionAndInstance} 的常规 REPORT 路径只需要各分区的
 * <b>状态</b>来做成功/失败计数、allFinished 判定与按节点计分区,并不需要整行 {@code job_partition}(尤其是 {@code output_summary}
 * 这个可能很大的 jsonb 列)。用这个投影替代 {@code selectByQuery(select *)} 全量拉行,把每次 REPORT 的 O(N) 大列读降为 O(N)
 * 两小列读;{@code output_summary} 只在「节点完成 / 实例终态」聚合产出时才按需全量再读。
 */
public record PartitionStatusRef(Long id, String partitionStatus) {}
