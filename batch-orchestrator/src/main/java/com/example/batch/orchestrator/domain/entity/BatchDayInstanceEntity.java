package com.example.batch.orchestrator.domain.entity;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/**
 * 批次日实例投影（MyBatis 通过 {@code resultMap+constructor} 映射；不可变 record）。
 *
 * <p>{@link #version} 是乐观锁列：{@code BatchDayInstanceMapper.updateWithCas} 在 update 时检查 {@code WHERE
 * id=? AND version=?}，affected==0 时调用方应抛 {@code OptimisticLockingFailureException} 让 settle /
 * reopen / cutoff 调用方按各自语义重试或跳过。
 *
 * <p>{@link #timezoneSnapshot} 是创建时从 business_calendar.timezone 抓的快照 —— 事后日历改时区不影响 历史批次日的 cutoff_at
 * / sla_deadline_at 重放语义。
 *
 * <p><b>不要加 Spring Data 注解</b>（{@code @Table @Id @Version @Column}）—— 本表已迁 MyBatis 后由 {@code
 * BatchDayInstanceMapper} 接管 CRUD + 乐观锁；保留 SDJ 注解会被框架误扫成 Repository。
 */
@Builder(toBuilder = true)
public record BatchDayInstanceEntity(
    Long id,
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    String dayStatus,
    Instant openAt,
    Instant cutoffAt,
    Instant settledAt,
    Instant slaDeadlineAt,
    Integer lateCount,
    Integer catchupCount,
    String timezoneSnapshot,
    String dstPolicySnapshot,
    Boolean frozen,
    String operationReason,
    String operatedBy,
    Instant operatedAt,
    Long version,
    Instant createdAt,
    Instant updatedAt) {

  public BatchDayInstanceEntity(
      Long id,
      String tenantId,
      String calendarCode,
      LocalDate bizDate,
      String dayStatus,
      Instant openAt,
      Instant cutoffAt,
      Instant settledAt,
      Instant slaDeadlineAt,
      Integer lateCount,
      Integer catchupCount,
      String timezoneSnapshot,
      Long version,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        "gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET",
        false,
        null,
        null,
        null,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity(
      Long id,
      String tenantId,
      String calendarCode,
      LocalDate bizDate,
      String dayStatus,
      Instant openAt,
      Instant cutoffAt,
      Instant settledAt,
      Instant slaDeadlineAt,
      Integer lateCount,
      Integer catchupCount,
      String timezoneSnapshot,
      String dstPolicySnapshot,
      Long version,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        false,
        null,
        null,
        null,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity(
      Long id,
      String tenantId,
      String calendarCode,
      LocalDate bizDate,
      String dayStatus,
      Instant openAt,
      Instant cutoffAt,
      Instant settledAt,
      Instant slaDeadlineAt,
      Integer lateCount,
      Integer catchupCount,
      String timezoneSnapshot,
      Boolean frozen,
      String operationReason,
      String operatedBy,
      Instant operatedAt,
      Long version,
      Instant createdAt,
      Instant updatedAt) {
    this(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        "gap=RUN_AT_NEXT_VALID_TIME;overlap=RUN_ONCE_EARLIER_OFFSET",
        frozen,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withSlaDeadlineAt(Instant slaDeadlineAt, Instant updatedAt) {
    return new BatchDayInstanceEntity(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        frozen,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withLateCount(Integer lateCount, Instant updatedAt) {
    return new BatchDayInstanceEntity(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        frozen,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withCatchupCount(Integer catchupCount, Instant updatedAt) {
    return new BatchDayInstanceEntity(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        frozen,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withDayStatus(String dayStatus, Instant updatedAt) {
    return new BatchDayInstanceEntity(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        frozen,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withCutoffAt(Instant cutoffAt, Instant updatedAt) {
    return new BatchDayInstanceEntity(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        frozen,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withCutoff(Instant cutoffAt, Instant updatedAt) {
    return new BatchDayInstanceEntity(
        id,
        tenantId,
        calendarCode,
        bizDate,
        "CUTOFF",
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        frozen,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withSettled(
      String dayStatus, Instant settledAt, Instant updatedAt) {
    return new BatchDayInstanceEntity(
        id,
        tenantId,
        calendarCode,
        bizDate,
        dayStatus,
        openAt,
        cutoffAt,
        settledAt,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        frozen,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withReopened(Instant updatedAt) {
    return new BatchDayInstanceEntity(
        id,
        tenantId,
        calendarCode,
        bizDate,
        "IN_FLIGHT",
        openAt,
        cutoffAt,
        null,
        slaDeadlineAt,
        lateCount,
        catchupCount,
        timezoneSnapshot,
        dstPolicySnapshot,
        false,
        operationReason,
        operatedBy,
        operatedAt,
        version,
        createdAt,
        updatedAt);
  }

  public BatchDayInstanceEntity withManualOperation(BatchDayManualOperation op) {
    return toBuilder()
        .dayStatus(op.dayStatus())
        .frozen(op.frozen())
        .operationReason(op.operationReason())
        .operatedBy(op.operatedBy())
        .operatedAt(op.operatedAt())
        .settledAt(op.settledAt())
        .updatedAt(op.updatedAt())
        .build();
  }
}
