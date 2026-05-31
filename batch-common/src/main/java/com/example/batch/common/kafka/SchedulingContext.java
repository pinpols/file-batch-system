package com.example.batch.common.kafka;

import java.time.LocalDate;

/**
 * SDK Phase 2 §2.1:派单时随 {@link TaskDispatchMessage} 下沉的"调度上下文"。
 *
 * <p>动机:SDK handler 经常要拿 bizDate / 前后业务日 / 重试次数 / 触发来源做增量逻辑,过去只能再回调平台查。
 * 这些都是**该任务派发时刻就已确定、终生不变**的事实(不像 payload/timeout 配置会被管理员改),因此随消息下沉**不会**重蹈 v1
 * 消息因业务字段过期而被瘦身(P1-2.2)的覆辙。
 *
 * <p>字段可空说明:
 *
 * <ul>
 *   <li>{@code bizDate}:实例业务日,正常恒非空。
 *   <li>{@code prevBizDate} / {@code nextBizDate}:按"工作日=周一~周五"近似计算(暂不感知节假日日历, 见 {@code
 *       BizDateArithmetic} 类注释);需要节假日精度的留待后续 iteration。
 *   <li>{@code isHoliday}:当前仅判周末,故"节假日"语义=周末;后续接入 business_calendar 后才精确。
 *   <li>{@code attemptNo}:本次执行的尝试序号(retry/reclaim 会递增);取自 {@code job_instance.run_attempt}。
 *   <li>{@code triggerType}:触发类型(SCHEDULED / MANUAL / API / DEPENDENCY 等)。
 *   <li>{@code triggerCode} / {@code workflowRunId}:平台当前无对应来源列,统一置 null;待后续补列后填充。
 * </ul>
 */
public record SchedulingContext(
    LocalDate bizDate,
    LocalDate prevBizDate,
    LocalDate nextBizDate,
    Boolean isHoliday,
    Integer attemptNo,
    String triggerType,
    String triggerCode,
    Long workflowRunId) {}
