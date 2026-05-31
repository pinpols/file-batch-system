package com.example.batch.sdk.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;

/**
 * 调度上下文的 SDK 侧投影 —— 对齐平台 {@code com.example.batch.common.kafka.SchedulingContext}(随 {@link
 * com.example.batch.sdk.dispatcher.TaskDispatchMessage} 下沉)。
 *
 * <p>动机:租户 handler 常要拿 bizDate / 前后业务日 / 重试次数 /
 * 触发来源做增量逻辑,过去只能再回调平台查;这些都是**该任务派发时刻就已确定、终生不变**的事实,随消息下沉不会过期。
 *
 * <p>字段可空:平台当前按"工作日=周一~周五"近似 prev/next/isHoliday(暂不感知节假日日历);{@code triggerCode} / {@code
 * workflowRunId} 平台暂无来源列恒置 null。SDK 用 {@link JsonIgnoreProperties}{@code (ignoreUnknown=true)}
 * 包容平台后续加字段。
 *
 * @param bizDate 实例业务日,正常恒非空
 * @param prevBizDate 前一个业务日(近似:跳过周末)
 * @param nextBizDate 下一个业务日(近似:跳过周末)
 * @param isHoliday 当前仅判周末,故"节假日"语义=周末
 * @param attemptNo 本次执行尝试序号(retry/reclaim 递增)
 * @param triggerType 触发类型(SCHEDULED / MANUAL / API / DEPENDENCY 等)
 * @param triggerCode 触发来源编码(平台暂无来源列,恒 null)
 * @param workflowRunId 所属 workflow run(平台暂无来源列,恒 null)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SdkSchedulingContext(
    LocalDate bizDate,
    LocalDate prevBizDate,
    LocalDate nextBizDate,
    Boolean isHoliday,
    Integer attemptNo,
    String triggerType,
    String triggerCode,
    Long workflowRunId) {}
