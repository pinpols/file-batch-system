package io.github.pinpols.batch.console.domain.job.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.LocalDate;
/** 绑定一次selectByQuery操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record BatchDayQuery(
    String tenantId,
    String calendarCode,
    LocalDate fromBizDate,
    LocalDate toBizDate,
    PageRequest pageRequest) {}
