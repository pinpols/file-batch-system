package io.github.pinpols.batch.orchestrator.application.service.task;

import io.github.pinpols.batch.orchestrator.domain.param.JobPartitionPromoteStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.JobTaskPromoteStatusParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.application.service.governance.RelatedFileReferenceValidator;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.param.ClaimPartitionParam;
import io.github.pinpols.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseParam;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 守护分片生命周期的关键状态转换:
 *
 * <ul>
 *   <li>claimPartition: 分片不存在返 null;CAS 成功重新读最新;CAS 失败返原对象
 *   <li>renewLease: 同样的不存在 / 成功 / 失败语义
 *   <li>reclaimExpiredPartitions: 过期租约推回 WAITING(非 READY,避免跳过 outbox)
 * </ul>
 *
 * <p>releaseForDispatch 涉及 @Transactional 回滚语义(TransactionAspectSupport), 用纯单元测无法准确还原 Spring
 * 事务行为,留给集成测覆盖。
 */
@DisplayName("分区生命周期服务: 认领, 续租, 过期回收与派发放行口径")
class DefaultPartitionLifecycleServiceTest {

  @Mock
  private JobPartitionMapper jobPartitionMapper;

  @Mock
  private JobTaskMapper jobTaskMapper;

  @Mock
  private RelatedFileReferenceValidator relatedFileReferenceValidator;

  @Mock
  private JobInstanceMapper jobInstanceMapper;

  private DefaultPartitionLifecycleService service;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    service = new DefaultPartitionLifecycleService(
        jobPartitionMapper, jobTaskMapper, jobInstanceMapper, relatedFileReferenceValidator);
    JobInstanceEntity instance = new JobInstanceEntity();
    instance.setBizDate(LocalDate.of(2026, 10, 19));
    when(jobInstanceMapper.selectById(anyString(), anyLong())).thenReturn(instance);
  }

  // ===== claimPartition =====

  @Test
  @DisplayName("createPartitions: 缺失同租户父实例时在批量写入前失败")
  void shouldRejectMissingParentInstanceBeforeBatchInsert() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("tenant-a");
    plan.setPartitions(List.of(new SchedulePlan.PartitionPlan()));
    when(jobInstanceMapper.selectById("tenant-a", 100L)).thenReturn(null);

    assertThatThrownBy(() -> service.createPartitions(plan, 100L, PartitionStatus.CREATED.code()))
        .isInstanceOf(BizException.class);
    verify(jobPartitionMapper, never()).insertBatch(any());
    verify(jobPartitionMapper, never()).insert(any());
  }

  @Test
  @DisplayName("createPartitions: 父实例并发变更的精确外键拒绝映射为稳定冲突")
  void shouldMapConcurrentParentReferenceViolationToConflict() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("tenant-a");
    SchedulePlan.PartitionPlan partition = new SchedulePlan.PartitionPlan();
    partition.setPartitionNo(1);
    plan.setPartitions(List.of(partition));
    SQLException sqlFailure =
        new SQLException("violates constraint fk_job_partition_instance_tenant_date", "23503");
    doThrow(new DataIntegrityViolationException("foreign key conflict", sqlFailure))
        .when(jobPartitionMapper)
        .insertBatch(any());

    assertThatThrownBy(() -> service.createPartitions(plan, 100L, PartitionStatus.CREATED.code()))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.CONFLICT));
  }

  @Test
  @DisplayName("createPartitions: 源文件并发删除的精确外键拒绝映射为文件不存在")
  void shouldMapConcurrentSourceFileReferenceViolationToNotFound() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("tenant-a");
    SchedulePlan.PartitionPlan partition = new SchedulePlan.PartitionPlan();
    partition.setPartitionNo(1);
    partition.setSourceFileId(99L);
    plan.setPartitions(List.of(partition));
    SQLException sqlFailure =
        new SQLException("violates constraint fk_job_partition_source_file_tenant", "23503");
    doThrow(new DataIntegrityViolationException("foreign key conflict", sqlFailure))
        .when(jobPartitionMapper)
        .insertBatch(any());

    assertThatThrownBy(() -> service.createPartitions(plan, 100L, PartitionStatus.CREATED.code()))
        .isInstanceOfSatisfying(
            BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.NOT_FOUND));
  }

  @Test
  @DisplayName("createPartitions: 其他数据库约束错误不伪装成父实例冲突")
  void shouldPreserveUnrelatedDatabaseConstraintFailure() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("tenant-a");
    SchedulePlan.PartitionPlan partition = new SchedulePlan.PartitionPlan();
    partition.setPartitionNo(1);
    plan.setPartitions(List.of(partition));
    DataIntegrityViolationException failure =
        new DataIntegrityViolationException("unrelated database constraint");
    doThrow(failure).when(jobPartitionMapper).insertBatch(any());

    assertThatThrownBy(() -> service.createPartitions(plan, 100L, PartitionStatus.CREATED.code()))
        .isSameAs(failure);
  }

  @Test
  @DisplayName("createPartitions: 外租户源文件使整批分区在写入前失败")
  void shouldRejectForeignTenantSourceFileBeforeBatchInsert() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("tenant-a");
    SchedulePlan.PartitionPlan partition = new SchedulePlan.PartitionPlan();
    partition.setPartitionNo(1);
    partition.setSourceFileId(99L);
    plan.setPartitions(List.of(partition));
    doThrow(BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found"))
        .when(relatedFileReferenceValidator)
        .validate("tenant-a", 99L);

    Assertions.assertThatThrownBy(
            () -> service.createPartitions(plan, 100L, PartitionStatus.CREATED.code()))
        .isInstanceOf(BizException.class);
    verify(jobPartitionMapper, never()).insertBatch(any());
    verify(jobPartitionMapper, never()).insert(any());
  }

  @Test
  @DisplayName("claimPartition: 分片不存在 → 返 null,不写表")
  void shouldReturnNull_whenClaimedPartitionMissing() {
    when(jobPartitionMapper.selectById("ta", 100L)).thenReturn(null);

    JobPartitionEntity result = service.claimPartition("ta", 100L, "worker-1", Instant.now());
    assertThat(result).isNull();
    verify(jobPartitionMapper, never()).claimPartition(any());
  }

  @Test
  @DisplayName("claimPartition: CAS 成功 → 重新读 DB 拿最新")
  void shouldReturnFreshPartition_whenClaimCasHits() {
    JobPartitionEntity existing = partition(100L, 1L);
    JobPartitionEntity fresh = partition(100L, 2L);
    when(jobPartitionMapper.selectById("ta", 100L)).thenReturn(existing);
    when(jobPartitionMapper.claimPartition(any(ClaimPartitionParam.class))).thenReturn(fresh);

    JobPartitionEntity result = service.claimPartition("ta", 100L, "worker-1", Instant.now());
    assertThat(result).isSameAs(fresh);

    ArgumentCaptor<ClaimPartitionParam> cap = ArgumentCaptor.forClass(ClaimPartitionParam.class);
    verify(jobPartitionMapper).claimPartition(cap.capture());
    ClaimPartitionParam p = cap.getValue();
    assertThat(p.getFromStatus()).isEqualTo(PartitionStatus.READY.code());
    assertThat(p.getToStatus()).isEqualTo(PartitionStatus.RUNNING.code());
    assertThat(p.getExpectedVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("claimPartition: CAS 失败 → 返已读到的原对象,不再读 DB")
  void shouldReturnExistingPartition_whenClaimCasMisses() {
    JobPartitionEntity existing = partition(100L, 5L);
    when(jobPartitionMapper.selectById("ta", 100L)).thenReturn(existing);
    when(jobPartitionMapper.claimPartition(any(ClaimPartitionParam.class))).thenReturn(null);

    JobPartitionEntity result = service.claimPartition("ta", 100L, "worker-1", Instant.now());
    assertThat(result).isSameAs(existing);
    // 只读一次(claim 前);CAS 失败不应再读
    verify(jobPartitionMapper, times(1)).selectById("ta", 100L);
  }

  // ===== renewLease =====

  @Test
  @DisplayName("renewLease: 分片不存在 → 返 null")
  void shouldReturnNull_whenRenewedPartitionMissing() {
    when(jobPartitionMapper.selectById("ta", 200L)).thenReturn(null);

    JobPartitionEntity result = service.renewLease("ta", 200L, "worker-1", Instant.now());
    assertThat(result).isNull();
    verify(jobPartitionMapper, never()).renewLease(any());
  }

  @Test
  @DisplayName("renewLease: 续约成功 → 返新版本")
  void shouldReturnFreshPartition_whenRenewSucceeds() {
    JobPartitionEntity existing = partition(200L, 3L);
    JobPartitionEntity fresh = partition(200L, 4L);
    when(jobPartitionMapper.selectById("ta", 200L)).thenReturn(existing, fresh);
    when(jobPartitionMapper.renewLease(any(RenewLeaseParam.class))).thenReturn(1);

    JobPartitionEntity result = service.renewLease("ta", 200L, "worker-1", Instant.now());
    assertThat(result).isSameAs(fresh);
  }

  @Test
  @DisplayName("renewLease: CAS 失败 → 返原对象")
  void shouldReturnExistingPartition_whenRenewCasMisses() {
    JobPartitionEntity existing = partition(200L, 3L);
    when(jobPartitionMapper.selectById("ta", 200L)).thenReturn(existing);
    when(jobPartitionMapper.renewLease(any(RenewLeaseParam.class))).thenReturn(0);

    JobPartitionEntity result = service.renewLease("ta", 200L, "worker-1", Instant.now());
    assertThat(result).isSameAs(existing);
  }

  // ===== reclaimExpiredPartitions =====

  @Test
  @DisplayName("reclaimExpiredPartitions: 过期分片状态推回 WAITING(非 READY)")
  void shouldPushBackToWaiting_whenReclaimingExpired() {
    JobPartitionEntity p1 = partition(300L, 1L);
    JobPartitionEntity p2 = partition(301L, 2L);
    when(jobPartitionMapper.selectExpiredLeases(
            "ta", PartitionStatus.READY.code(), PartitionStatus.RUNNING.code()))
        .thenReturn(List.of(p1, p2));
    when(jobPartitionMapper.markStatus(any(MarkPartitionStatusParam.class))).thenReturn(1);

    int reclaimed = service.reclaimExpiredPartitions("ta");
    assertThat(reclaimed).isEqualTo(2);

    ArgumentCaptor<MarkPartitionStatusParam> cap =
        ArgumentCaptor.forClass(MarkPartitionStatusParam.class);
    verify(jobPartitionMapper, times(2)).markStatus(cap.capture());
    for (MarkPartitionStatusParam param : cap.getAllValues()) {
      // 防回归:必须推回 WAITING,不能是 READY(否则跳过 outbox 写入数据库)
      assertThat(param.getPartitionStatus()).isEqualTo(PartitionStatus.WAITING.code());
      assertThat(param.getPartitionStatus()).isNotEqualTo(PartitionStatus.READY.code());
    }
  }

  @Test
  @DisplayName("reclaimExpiredPartitions: 无过期分片 → 返 0,不调 markStatus")
  void shouldReturnZero_whenNoExpiredLease() {
    when(jobPartitionMapper.selectExpiredLeases(anyString(), anyString(), anyString()))
        .thenReturn(List.of());

    assertThat(service.reclaimExpiredPartitions("ta")).isZero();
    verify(jobPartitionMapper, never()).markStatus(any());
  }

  @Test
  @DisplayName("reclaimExpiredPartitions: CAS 部分失败 → 计数仅累加成功的")
  void shouldCountOnlySuccessfulCas_whenReclaiming() {
    JobPartitionEntity p1 = partition(300L, 1L);
    JobPartitionEntity p2 = partition(301L, 2L);
    JobPartitionEntity p3 = partition(302L, 3L);
    when(jobPartitionMapper.selectExpiredLeases(anyString(), anyString(), anyString()))
        .thenReturn(List.of(p1, p2, p3));
    // 1 成功, 1 失败(被别的 reclaim 抢了), 1 成功
    when(jobPartitionMapper.markStatus(any(MarkPartitionStatusParam.class))).thenReturn(1, 0, 1);

    assertThat(service.reclaimExpiredPartitions("ta")).isEqualTo(2);
  }

  // ===== releaseForDispatch (无事务回滚的简单分支) =====

  @Test
  @DisplayName("releaseForDispatch: null 入参 → 返 false,不写表")
  void shouldReturnFalse_whenReleaseInputsMissing() {
    assertThat(service.releaseForDispatch(null, null, "x", "y")).isFalse();
    assertThat(service.releaseForDispatch(partition(1L, 0L), null, "x", "y")).isFalse();
    assertThat(service.releaseForDispatch(null, task(1L, 0L), "x", "y")).isFalse();
    verify(jobPartitionMapper, never())
        .promoteStatus(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && parameters.tenantId() != null && parameters.fromStatus() != null && parameters.toStatus() != null));
    verify(jobTaskMapper, never())
        .promoteStatus(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && parameters.tenantId() != null && parameters.fromStatus() != null && parameters.toStatus() != null));
  }

  @Test
  @DisplayName("releaseForDispatch: partition CAS 失败 → 返 false,不再推 task")
  void shouldReturnFalse_whenReleaseCasMisses() {
    JobPartitionEntity p = partition(1L, 0L);
    p.setTenantId("ta");
    JobTaskEntity t = task(1L, 0L);
    when(jobPartitionMapper.promoteStatus(
            JobPartitionPromoteStatusParam.builder()
                .tenantId("ta")
                .id(1L)
                .fromStatus(PartitionStatus.WAITING.code())
                .toStatus(PartitionStatus.READY.code())
                .expectedVersion(0L)
                .build()))
        .thenReturn(0);

    boolean ok =
        service.releaseForDispatch(p, t, PartitionStatus.WAITING.code(), TaskStatus.CREATED.code());
    assertThat(ok).isFalse();
    verify(jobTaskMapper, never())
        .promoteStatus(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && parameters.tenantId() != null && parameters.fromStatus() != null && parameters.toStatus() != null));
  }

  @Test
  @DisplayName("releaseForDispatch: 全成功 → 返 true + 内存对象状态/版本号同步推进")
  void shouldReturnTrueAndBumpState_whenReleaseSucceeds() {
    JobPartitionEntity p = partition(1L, 0L);
    p.setTenantId("ta");
    JobTaskEntity t = task(1L, 0L);
    t.setTenantId("ta");
    when(jobPartitionMapper.promoteStatus(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && parameters.tenantId() != null && parameters.fromStatus() != null && parameters.toStatus() != null)))
        .thenReturn(1);
    when(jobTaskMapper.promoteStatus(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && parameters.tenantId() != null && parameters.fromStatus() != null && parameters.toStatus() != null)))
        .thenReturn(1);

    boolean ok =
        service.releaseForDispatch(p, t, PartitionStatus.WAITING.code(), TaskStatus.CREATED.code());
    assertThat(ok).isTrue();
    assertThat(p.getPartitionStatus()).isEqualTo(PartitionStatus.READY.code());
    assertThat(p.getVersion()).isEqualTo(1L);
    assertThat(t.getTaskStatus()).isEqualTo(TaskStatus.READY.code());
    assertThat(t.getVersion()).isEqualTo(1L);
  }

  @Test
  @DisplayName("createPartitions: input_snapshot 固化 partition plan contract")
  @SuppressWarnings("unchecked")
  void shouldPersistPartitionPlanContract_whenCreatingPartitions() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("ta");
    plan.setJobCode("JOB_A");
    plan.setBizDate("2026-06-30");
    plan.setQueueCode("import_queue");
    plan.setWorkerGroup("IMPORT");
    plan.setResourceProfile("io-heavy");
    plan.setDownstreamChannelCode("sftp-primary");
    plan.setWindowCode("default_window");
    SchedulePlan.PartitionPlan partitionPlan = new SchedulePlan.PartitionPlan();
    partitionPlan.setPartitionNo(1);
    partitionPlan.setPartitionKey("JOB_A:2026-06-30:1");
    partitionPlan.setBusinessKey("JOB_A:2026-06-30");
    partitionPlan.setShardIndex(0);
    partitionPlan.setShardTotal(2);
    partitionPlan.setRangeStartInclusive(0L);
    partitionPlan.setRangeEndExclusive(50L);
    partitionPlan.setExpectedRows(50L);
    plan.setPartitions(List.of(partitionPlan));

    service.createPartitions(plan, 900L, PartitionStatus.CREATED.code());

    // PERF(5.1): fan-out 走单条多行 INSERT(insertBatch),不再逐条 insert
    ArgumentCaptor<List<JobPartitionEntity>> cap = ArgumentCaptor.forClass(List.class);
    verify(jobPartitionMapper).insertBatch(cap.capture());
    verify(jobPartitionMapper, never()).insert(any());
    assertThat(cap.getValue()).hasSize(1);
    assertThat(cap.getValue().getFirst().getJobInstanceBizDate())
        .isEqualTo(LocalDate.of(2026, 10, 19));
    Map<String, Object> snapshot = (Map<String, Object>)
        JsonUtils.fromJson(cap.getValue().get(0).getInputSnapshot(), Object.class);
    assertThat(snapshot).containsEntry("partitionPlanVersion", 1);
    assertThat(snapshot).containsEntry("shardIndex", 0);
    assertThat(snapshot).containsEntry("shardTotal", 2);
    assertThat(snapshot).containsEntry("rangeStartInclusive", 0);
    assertThat(snapshot).containsEntry("rangeEndExclusive", 50);
    assertThat(snapshot).containsEntry("expectedRows", 50);
    assertThat(snapshot).containsEntry("resourceProfile", "io-heavy");
    assertThat(snapshot).containsEntry("downstreamChannelCode", "sftp-primary");
  }

  @Test
  @DisplayName("createPartitions: 分发束按分区固化各自的下游渠道")
  @SuppressWarnings("unchecked")
  void shouldPersistPartitionChannel_whenCreatingDispatchBundle() {
    SchedulePlan plan = new SchedulePlan();
    plan.setTenantId("ta");
    plan.setJobCode("DISPATCH_BUNDLE");
    plan.setBizDate("2026-06-30");
    plan.setDefaultWorkerType("DISPATCH");
    plan.setDownstreamChannelCode("fallback-channel");
    SchedulePlan.PartitionPlan partitionPlan = new SchedulePlan.PartitionPlan();
    partitionPlan.setPartitionNo(1);
    partitionPlan.setTargetRef("sftp-partition-a");
    plan.setPartitions(List.of(partitionPlan));

    service.createPartitions(plan, 901L, PartitionStatus.WAITING.code());

    ArgumentCaptor<List<JobPartitionEntity>> cap = ArgumentCaptor.forClass(List.class);
    verify(jobPartitionMapper).insertBatch(cap.capture());
    Map<String, Object> snapshot = (Map<String, Object>)
        JsonUtils.fromJson(cap.getValue().getFirst().getInputSnapshot(), Object.class);
    assertThat(snapshot).containsEntry("downstreamChannelCode", "sftp-partition-a");
  }

  // ===== fixtures =====

  private JobPartitionEntity partition(Long id, Long version) {
    JobPartitionEntity p = new JobPartitionEntity();
    p.setId(id);
    p.setVersion(version);
    p.setTenantId("ta");
    return p;
  }

  private JobTaskEntity task(Long id, Long version) {
    JobTaskEntity t = new JobTaskEntity();
    t.setId(id);
    t.setVersion(version);
    return t;
  }
}
