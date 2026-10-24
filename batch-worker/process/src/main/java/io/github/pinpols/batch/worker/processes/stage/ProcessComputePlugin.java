package io.github.pinpols.batch.worker.processes.stage;

import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.plugin.ReplayCapabilityDeclaration;
import java.util.Map;
import io.github.pinpols.batch.worker.processes.domain.ProcessJobContext;
import io.github.pinpols.batch.worker.processes.domain.ProcessStage;
import io.github.pinpols.batch.worker.processes.domain.ProcessStageResult;

/**
 * PROCESS 业务加工插件契约。
 *
 * <p>设计依据:{@code docs/design/batch-classification-and-gaps.md} §4.5。 PROCESS 走 WAP+bookends 模式,5 个
 * stage 各对应插件一个生命周期方法:
 *
 * <ul>
 *   <li>{@link #prepare(ProcessJobContext)} —— Pre-flight:解析配置、校验合法性、检查 target 可达,失败抛异常即可早停
 *   <li>{@link #compute(ProcessJobContext)} —— Write:计算源数据并写入 staging(框架提供共享 batch.process_staging
 *       表)
 *   <li>{@link #validate(ProcessJobContext)} —— Audit:在 staging 上跑数据质量规则,失败阻断 publish
 *   <li>{@link #commit(ProcessJobContext)} —— Publish:atomic 把 staging 数据写入目标表
 *   <li>{@link #feedback(ProcessJobContext)} —— Post-hook:清理 staging、推进水位、写审计 / 指标
 * </ul>
 *
 * <p>插件按需 opt-in:不实现的方法走默认 no-op,框架仍会在对应 stage 写一条 pipeline_step_run。 默认实现把所有方法都当 success 返回,适合"只在
 * COMPUTE 干一件事"的简单插件(典型如旧版 e2eProcessCompute 测试桩)。
 */
public interface ProcessComputePlugin {

  String implCode();

  /**
   * 声明整个 PROCESS 生命周期在 Worker 租约丢失后是否可安全重跑。
   *
   * <p>默认拒绝。只有 COMPUTE、COMMIT
   * 和 FEEDBACK 对相同任务及业务身份的跨尝试执行均安全，才可声明重放安全。PREPARE 和
   * VALIDATE 必须保持只读。含外部副作用的插件必须保留 UNKNOWN 或
   * NONE，除非外部系统提供持久化幂等合同。
   */
  default IdempotencyCapability idempotencyCapability() {
    return IdempotencyCapability.UNKNOWN;
  }

  /** 解析冻结 PROCESS 配置的全部副作用；声明不完整时拒绝执行。 */
  default ReplayCapabilityDeclaration replayCapability(Map<String, Object> processConfig) {
    return replayCapabilityDeclaration();
  }

  /** 注册目录采用保守声明，必须适用于该插件的所有配置。 */
  default ReplayCapabilityDeclaration replayCapabilityDeclaration() {
    return ReplayCapabilityDeclaration.unknown(implCode());
  }

  /** PREPARE:解析 step_params、校验合法性、检查 target 可达;失败抛 {@link RuntimeException} 即可早停。 */
  default void prepare(ProcessJobContext context) {
    // 默认 no-op:简单插件不需要预校验。
  }

  /**
   * COMPUTE:计算并写入 staging(默认情况下,sqlTransformCompute 等框架内置插件会写到 batch.process_staging;自定义插件可以选择不写
   * staging,但这样后续 validate/commit/feedback 也无意义)。
   *
   * @return 必须返回非 null 的 {@link ProcessStageResult};失败时直接 return failure(...)即可,框架不会再调后续方法
   */
  ProcessStageResult compute(ProcessJobContext context);

  /** VALIDATE:对 staging 行跑质量规则;失败抛异常或返回 failure。 */
  default ProcessStageResult validate(ProcessJobContext context) {
    return ProcessStageResult.success(ProcessStage.VALIDATE);
  }

  /** COMMIT:atomic 把 staging → target;失败抛异常,框架已开事务,自动 rollback。 */
  default ProcessStageResult commit(ProcessJobContext context) {
    return ProcessStageResult.success(ProcessStage.COMMIT);
  }

  /** FEEDBACK:推进水位、清理 staging、写审计;尽量 best-effort,主链路成功后此处异常应只 log warn 不抛。 */
  default ProcessStageResult feedback(ProcessJobContext context) {
    return ProcessStageResult.success(ProcessStage.FEEDBACK);
  }
}
