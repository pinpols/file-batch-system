package io.github.pinpols.batch.worker.core.domain;

import io.github.pinpols.batch.common.i18n.AbstractLocalizedErrorEntity;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class TaskExecutionReport extends AbstractLocalizedErrorEntity {

  private Long taskId;
  private String tenantId;
  private String workerId;

  /** Worker 侧的 traceId，用于让 orchestrator 同一条任务实例的日志/审计能够串起来。 */
  private String traceId;

  private boolean success;
  private String code;
  private String message;
  private String resultSummary;
  private String errorCode;
  private String failureClass;

  /**
   * i18n message key (来自 BizException.of)。Worker 侧 BizException 命中 i18n key 时填入, orchestrator 持久化到
   * job_task.error_key,console 读路径按当前 Locale 重渲染。null 表示老 literal / 第三方异常,errorMessage 是唯一展示来源。
   */

  /**
   * 增量执行模式(ExecutionMode.INCREMENTAL)下 worker 上报的新水位高点。业务逻辑通过 {@code
   * ExecutionContext.getAttributes().put(PipelineRuntimeKeys.HIGH_WATER_MARK_OUT, ...)}
   * 写入;框架不自动推进。null 表示本次执行无水位变化,orchestrator 不更新 job_instance.high_water_mark_out。
   */
  private String highWaterMarkOut;

  /** ADR-014:可选;当 worker 持有当前分区 invocation id 时随上报一并发送。 */
  private String partitionInvocationId;

  /**
   * ADR-009 Stage 1.2: 节点产出 Map(供下游 workflow 节点 $.nodes.&lt;X&gt;.output.&lt;key&gt; 引用)。Worker 侧仅放
   * JSON 原生类型(String/Number/Boolean/List/Map);orchestrator 在 SUCCESS 路径写入 workflow_node_run.output
   * JSONB。null/empty 等价"无产出",DSL 引用按 null fallback。
   */
  private Map<String, Object> outputs;

  /**
   * ADR-030 §C/E: 当本次任务执行成功但 ContentVerifier 发现产物级问题时携带的失败列表。每个元素 {@code {code, message,
   * evidence}}，对应 {@link io.github.pinpols.batch.common.verifier.VerifyResult}。
   *
   * <p>null/empty 等价"verifier 全部通过 / 无适用 verifier / verifier 未启用"。 worker 在成功路径不会因 verifier 失败把
   * success 翻成 false——硬中止策略由后续 PR 接入。
   *
   * <p>orchestrator 后续 PR 消费：在同事务里写 {@code outbox_event(event_type='verifier.failure.v1')} 供告警面板订阅。
   */
  private List<Map<String, Object>> verifierFailures;
}
