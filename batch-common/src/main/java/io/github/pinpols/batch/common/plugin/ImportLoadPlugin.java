package io.github.pinpols.batch.common.plugin;

import java.util.List;
import java.util.Map;

/** 导入 LOAD 插件：将校验后的逻辑行（map）持久化到上游系统。新实现注册为 Spring bean，通过 {@link #id()} 标识。 */
public interface ImportLoadPlugin {

  /** 稳定标识符，如 {@link WorkerPluginIds#IMPORT_LOAD_CUSTOMER_ACCOUNT}。 */
  @SuppressWarnings("java:S112")
  String id();

  /**
   * 持久化一批经校验的 NDJSON 行（通常为 camelCase 键），返回已确认处理的逻辑行数。
   * 正常返回必须确认整批，包括由幂等规则确认已存在的记录；否则 LOAD 阶段失败且不推进该分块位点。
   * 插件发生异常或部分副作用时，恢复安全性由其幂等声明及执行围栏约束。
   */
  @SuppressWarnings("java:S112")
  int loadChunk(ImportLoadContext context, List<Map<String, Object>> records) throws Exception;

  /**
   * 声明重复 LOAD 分块在检查点恢复、任务重试和重领期间是否安全。
   * 当能力不保证重放安全时，{@code LoadStep} 会在需要保护的执行路径中设置任务围栏；
   * 在检查点恢复被禁用或降级时，必须先持久化围栏，再发生首次外部副作用。
   */
  default IdempotencyCapability idempotencyCapability() {
    return IdempotencyCapability.UNKNOWN;
  }

  /** 注册目录采用保守声明，必须适用于该插件的所有配置。 */
  default ReplayCapabilityDeclaration replayCapabilityDeclaration() {
    return ReplayCapabilityDeclaration.unknown(id());
  }

  /**
   * 解析冻结模板配置的完整外部副作用合同。
   * 任一副作用或恢复保证无法证明时，必须返回 UNKNOWN。
   */
  default ReplayCapabilityDeclaration replayCapability(Map<String, Object> templateConfig) {
    return replayCapabilityDeclaration();
  }
}
