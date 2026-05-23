package com.example.batch.orchestrator.application.plan;

import com.example.batch.common.enums.ShardStrategy;
import com.example.batch.orchestrator.domain.entity.JobDefinitionEntity;
import java.util.Map;

/**
 * 动态分片计划中分区数的解析策略接口。 每个实现封装一种解析方式（显式覆盖、数据量估算、历史时长估算、在线 Worker 容量）。 {@link
 * DefaultSchedulePlanBuilder} 链式调用多个解析器，第一个返回正值的结果生效。
 *
 * <h2>扩展点约定 (2026-05-23 审计登记)</h2>
 *
 * <ul>
 *   <li><b>策略链权重</b>: 实现必须用 {@code @Order} 指定优先级,序号越小越先尝试; 数据量 / 时长 / Worker 容量等通用估算应放在显式覆盖之后,
 *       避免 override 形同虚设。
 *   <li><b>不可扩展边界</b>: 平台分片语义属于 Pipeline / Job 调度核心, <b>不允许在无 ADR 批准的情况下新增实现</b>;
 *       新增前必须先确认现有 4 个实现 (显式 / 数据量 / 时长 / Worker) 无法满足业务。
 *   <li><b>幂等约束</b>: {@code resolve()} 必须为纯函数(不可有副作用 / 不可写库), 因为同一 plan 评估期内可能被多次调用。
 *   <li><b>返回 0 语义</b>: 不适用时必须返回 {@code 0}(由后续 resolver 接手), 不要抛异常打断链路。
 * </ul>
 */
public interface PartitionCountResolver {

  /** 能解析时返回正整数分区数，不适用时返回 {@code 0}。 */
  int resolve(
      JobDefinitionEntity jobDefinition, Map<String, Object> params, ShardStrategy shardStrategy);
}
