package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.LocalTime;

/**
 * {@code batch.batch_window} 行的不可变快照（MyBatis 通过 {@code resultMap+constructor} 映射）。
 *
 * <p>字段顺序与 V3 DDL 列一致。
 *
 * <p><b>不要加 Spring Data 注解</b>（{@code @Table @Id @Column}）—— 本表已迁 MyBatis 后由 {@link
 * io.github.pinpols.batch.orchestrator.mapper.BatchWindowMapper} 接管 CRUD；保留 SDJ 注解会被框架误扫成
 * Repository。
 */
public record BatchWindowEntity(
    Long id,
    String tenantId,
    String windowCode,
    String windowName,
    String timezone,
    LocalTime startTime,
    LocalTime endTime,
    String endStrategy,
    String outOfWindowAction,
    Boolean allowCrossDay,
    Boolean enabled) {}
