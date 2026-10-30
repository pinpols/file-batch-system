package io.github.pinpols.batch.common.dto;

import io.github.pinpols.batch.common.enums.TriggerType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.Builder;

/**
 * Launch 请求模型. 触发侧 (CRON / FIXED_RATE / API / MANUAL) 计算 bizDate / 参数后封装提交给 orchestrator.
 *
 * <p><b>data_interval (V94)</b>: 半开区间 {@code [dataIntervalStart, dataIntervalEnd)},
 * 表达"本次跑应该处理哪段时间的数据". 由触发侧计算:
 *
 * <ul>
 *   <li>CRON / FIXED_RATE: {@code [thisFireAt, nextFireAt)} — IMPORT/EXPORT 业务可拼 SQL {@code WHERE
 *       update_time >= :start AND update_time < :end} 真实现分钟级切片
 *   <li>API / MANUAL: 调用方提供, 否则 null
 *   <li>fallback: 退化为 {@code [bizDate.atStartOfDay(zone), bizDate+1.atStartOfDay(zone))} 单点日级
 * </ul>
 *
 * <p>两个字段允许为空：未提供数据区间时，Worker 按本次固定的 bizDate 计算日级范围。
 */
@Builder(toBuilder = true)
public record LaunchRequest(
    String tenantId,
    String jobCode,
    LocalDate bizDate,
    TriggerType triggerType,
    String requestId,
    String traceId,
    Map<String, Object> params,
    Instant dataIntervalStart,
    Instant dataIntervalEnd,
    /** ADR-020 batch_day_replay_session.id 透传标签；NULL = 非 replay 创建。 */
    Long replaySessionId,
    /**
     * ADR-026 dry-run 演练标记；true = 不副作用（不写业务表 / 不发外部 IO / 不进 EFFECTIVE 链）。
     *
     * <p>装箱为 {@link Boolean} 以表达 JSON 请求中未显式指定的状态（调用方未发送该字段时，记录构造器收到
     * {@code null}），下游 setter 按需将 null 视为 false（DB 列默认值回退）。
     */
    Boolean dryRun,
    /** 外部事件规则编码；只描述事件类别，具体投递幂等身份仍由请求契约确定。 */
    String externalEventCode,
    /** Orchestrator 就绪检查观察到的上游版本；不代表处理器已经按此版本读取。 */
    List<DependencyResultReference> dependencyResultSnapshot,
    /** 顶层 Workflow 目标；内部 Job 节点请求使用 jobCode。 */
    Long workflowDefinitionId,
    /** 外部发送 EVENT 时使用的稳定事件来源标识。 */
    String externalEventSource,
    /** 由 externalEventSource 分配的稳定事件标识。 */
    String externalEventId) {

  /** 内部 Job 启动的简洁入口；数据区间、重放和外部事件字段通过建造器显式设置。 */
  public LaunchRequest(
      String tenantId,
      String jobCode,
      LocalDate bizDate,
      TriggerType triggerType,
      String requestId,
      String traceId,
      Map<String, Object> params) {
    this(
        tenantId,
        jobCode,
        bizDate,
        triggerType,
        requestId,
        traceId,
        params,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null);
  }

  /** Workflow 所有执行契约使用的顶层启动构造函数。 */
  public LaunchRequest(
      String tenantId,
      Long workflowDefinitionId,
      LocalDate bizDate,
      TriggerType triggerType,
      String requestId,
      String traceId,
      Map<String, Object> params) {
    this(
        tenantId,
        null,
        bizDate,
        triggerType,
        requestId,
        traceId,
        params,
        null,
        null,
        null,
        false,
        null,
        null,
        workflowDefinitionId,
        null,
        null);
  }
}
