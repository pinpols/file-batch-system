package com.example.batch.worker.core.support;

import java.util.Map;

/**
 * 安全增量补偿(opt-in)SPI —— worker-core 定义接口，各 worker 模块(import / export / dispatch)按 pipeline 类型实现。
 *
 * <p>由 {@link PipelineCompensationHook} 在 pipeline 失败落地点调用，对**本次 run 自己写入的、可幂等精确识别的**行 /
 * 对象执行**反向动作** （DELETE 业务行 / 删对象）。worker-core 通过 {@code ObjectProvider} 注入，不硬依赖任何具体 worker；无实现 bean
 * 时补偿整体跳过。
 *
 * <p><b>安全第一约束(实现方必须遵守)</b>：
 *
 * <ul>
 *   <li>只删**本 run 自己**、可幂等识别的行 / 对象;无法精确 scope(如模板没绑 run 标识列)→ <b>SKIP 不删</b> + 审计原因。 绝不出现无 {@code
 *       WHERE} / 宽 {@code WHERE} 的 DELETE。
 *   <li>反向动作是 <b>best-effort</b>：内部异常必须自行吞咽(记日志 + 返回 {@link CompensationResult#failed}),
 *       <b>不得上抛</b>——补偿失败不能掩盖 pipeline 的原始失败。
 *   <li>biz 库写路径遵守 ADR-001(MyBatis / JdbcTemplate),删 SQL 带 {@code tenant_id} + run 标识列, <b>禁</b>
 *       {@code ${}} 字符串拼接;表名若来自模板须先强校验白名单 / 正则。
 * </ul>
 */
public interface PipelineCompensator {

  /**
   * 该 compensator 适用的 pipeline 类型({@code "IMPORT"/"EXPORT"/"DISPATCH"})，与 {@code
   * AbstractPipelineStepExecutionAdapter#pipelineType()} 对齐(大小写不敏感)。
   */
  String pipelineType();

  /**
   * 对本 run 执行反向动作。
   *
   * @param tenantId 租户 ID
   * @param pipelineInstanceId 当前 pipeline 实例 ID(审计用)
   * @param fileId 当前 file_record id(export / dispatch 反向删对象时按它 scope);可为 null
   * @param attributes pipeline runtime attributes(含 templateConfig / batchNo / traceId / fileRecord
   *     等 run 标识来源)
   * @return 反向动作结果(已删行数 / 是否 SKIP / 原因);<b>永不抛异常</b>，内部失败转 {@link CompensationResult#failed}
   */
  CompensationResult compensate(
      String tenantId, Long pipelineInstanceId, Long fileId, Map<String, Object> attributes);
}
