package com.example.batch.common.verifier;

import com.example.batch.common.enums.JobType;
import java.util.Set;

/**
 * 产物内容验收 SPI（ADR-030）。
 *
 * <p>实现类作为 Spring bean 注册，{@link ContentVerifierRegistry} 自动收集并按 {@link #appliesTo()} 与 {@link
 * #stageCode()} 路由。
 *
 * <h2>什么放进 Verifier，什么不放</h2>
 *
 * <ul>
 *   <li>✅ 跨 worker 实现一致的产物级断言：导出文件非空 / 行数对账 / DISPATCH 回执存在
 *   <li>✅ Stage 完成后能立刻判断的"快照式"业务正确性
 *   <li>❌ 业务校验链（field validation / value range）—— 那是 import worker 的 PreprocessStep 职责
 *   <li>❌ 跨 job_instance 的数据治理（数据对账走 ADR-021，不复用本 SPI）
 * </ul>
 *
 * <h2>失败语义</h2>
 *
 * 返回 {@link VerifyResult#fail} 不会自动中止任务；由调用方（worker stage hook 或 orchestrator）按"软告警 /
 * 硬中止"策略决定。Verifier 本身只产出结论 + 证据。
 */
public interface ContentVerifier {

  /** Verifier 业务码，全 case；与 {@link VerifyResult#code()} 的命名空间一致。 用于 metrics tag 与 outbox 事件路由。 */
  String code();

  /** 适用的 job_type 集合；空集合视为"任何 job_type 都不跑"（防意外注册）。 */
  Set<JobType> appliesTo();

  /**
   * 适用的 stage 业务码。 实现可以返回 null 表示"任何 stage"，但只在跨 stage verifier（如 task 终态后才校验 总体产物）时这样做；常规 verifier
   * 应明确绑定到一个 stage。
   */
  default String stageCode() {
    return null;
  }

  /**
   * ADR-030 §G 硬中止开关。
   *
   * <ul>
   *   <li>{@code false}（默认）：软告警——失败仅写 outbox + metrics，task 仍按 SUCCESS 推进
   *   <li>{@code true}：失败将让 worker 把整个 pipeline 翻为 FAILED（错误码 VERIFIER_FATAL）
   * </ul>
   *
   * <p>选 true 仅当"产物级问题等价于业务失败"——比如 DISPATCH 没回执 → 下游对账一定挂。 默认 false 是为了避免错把告警拔成中断；业务方设计 verifier
   * 时显式 opt-in。
   */
  default boolean fatal() {
    return false;
  }

  /** 执行验证。实现必须保证幂等且无副作用（不写库、不发消息）。 */
  VerifyResult verify(VerifyContext context);
}
