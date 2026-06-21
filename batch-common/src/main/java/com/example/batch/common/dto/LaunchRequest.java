package com.example.batch.common.dto;

import com.example.batch.common.enums.TriggerType;
import java.time.Instant;
import java.time.LocalDate;
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
 * <p>两个字段允许为 null (向后兼容: V94 之前的 trigger 不算 interval, instance 落 null, worker 业务用 bizDate 回退).
 */
@Builder
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
     * <p>装箱为 {@link Boolean} 是为了让 JSON 反序列化容忍缺省字段（旧 trigger / 测试构造的 launch 请求未发送 该字段时，记录构造器收到
     * {@code null}），下游 setter 按需将 null 视为 false（DB 列默认值回退）。
     */
    Boolean dryRun) {

  /** 简洁回退构造器: 不带 interval 的旧调用方 (RERUN / 历史路径) 直接传 7 参. */
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
        false);
  }

  /** 9 参兼容构造：仅带 data interval，replay_session_id + dry_run 默认 null/false。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public LaunchRequest(
      String tenantId,
      String jobCode,
      LocalDate bizDate,
      TriggerType triggerType,
      String requestId,
      String traceId,
      Map<String, Object> params,
      Instant dataIntervalStart,
      Instant dataIntervalEnd) {
    this(
        tenantId,
        jobCode,
        bizDate,
        triggerType,
        requestId,
        traceId,
        params,
        dataIntervalStart,
        dataIntervalEnd,
        null,
        false);
  }

  /** 10 参兼容构造：含 replay_session_id，dry_run 默认 false。 */
  @SuppressWarnings("PMD.ExcessiveParameterList")
  public LaunchRequest(
      String tenantId,
      String jobCode,
      LocalDate bizDate,
      TriggerType triggerType,
      String requestId,
      String traceId,
      Map<String, Object> params,
      Instant dataIntervalStart,
      Instant dataIntervalEnd,
      Long replaySessionId) {
    this(
        tenantId,
        jobCode,
        bizDate,
        triggerType,
        requestId,
        traceId,
        params,
        dataIntervalStart,
        dataIntervalEnd,
        replaySessionId,
        false);
  }
}
