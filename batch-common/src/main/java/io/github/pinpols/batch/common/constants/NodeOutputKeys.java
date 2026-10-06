package io.github.pinpols.batch.common.constants;

/**
 * ADR-009 workflow 节点产出（{@code outputs}）与 ADR-041 跨阶段 count 信封的共享键。
 *
 * <p>worker adapter 把节点产出写进 task attributes 的 {@code nodeOutputs} 袋
 * （{@code PipelineRuntimeKeys.NODE_OUTPUTS}），orchestrator 提取后持久化到
 * {@code workflow_node_run.output}，供 workflow DSL（{@code $.nodes.&lt;X&gt;.output.&lt;key&gt;}）与
 * {@code CountContinuityOutboxService} 的跨阶段连续性核对消费。生产方（各 worker 模块）与消费方
 * （batch-orchestrator）都需要同一份键名，故登记在 batch-common，而不是任一 worker 模块内。
 *
 * <p>本表只收「没有 attributes 归属类」的产出键：与 attributes 键同名同义的产出键（如 {@code fileId} /
 * {@code recordCount} / {@code highWaterMarkOut} / {@code processedCount}）按既有约定复用各自键表常量，
 * 不为同一语义引入第二份事实来源。
 */
public final class NodeOutputKeys {

  /**
   * ADR-041 归一化 count 信封的入端计数（import=文件原始行数，process=处理数，export=导出行数，dispatch=文件数）。
   *
   * <p>orchestrator 在 {@code CountContinuityOutboxService} 里按 {@code 上一跳.outputCount == 本跳.inputCount}
   * 核跨阶段连续性。
   */
  public static final String INPUT_COUNT = "inputCount";

  /** ADR-041 归一化 count 信封的出端计数（import=入库行数，process=发布行数，export=导出行数）。 */
  public static final String OUTPUT_COUNT = "outputCount";

  /** PROCESS 批次键（{@code ProcessJobContext#getBatchKey}）：staging / 发布范围的业务标识。 */
  public static final String BATCH_KEY = "batchKey";

  private NodeOutputKeys() {}
}
