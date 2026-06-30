package io.github.pinpols.batch.orchestrator.application.service.version;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-017 §决策 §实施分阶段 Stage 4 — PENDING / EFFECTIVE 状态推进入口。
 *
 * <p>调用方：console-api（promote / reject）或 ADR-020 OUTPUTS_ONLY 重放。两种动作：
 *
 * <ul>
 *   <li>{@link #promote(String, Long)} —— 把 PENDING 行推到 EFFECTIVE，同事务内把同 business_key 的旧 EFFECTIVE
 *       推到 SUPERSEDED；保证 partial unique index 不变量。
 *   <li>{@link #rejectPending(String, Long)} —— 把 PENDING 行直接 ARCHIVED，相当于"放弃这个候选"。
 * </ul>
 *
 * <p>当前 Stage 4 只暴露内部 service；console controller / approval_request 集成保留给非后端层。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultVersionPromoteService {

  private final ResultVersionMapper resultVersionMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final BatchDateTimeSupport dateTimeSupport;
  private final AssetPartitionService assetPartitionService;

  /** 把 PENDING 版本推到 EFFECTIVE。同事务内把同 business_key 旧 EFFECTIVE 推到 SUPERSEDED。 */
  @Transactional
  public ResultVersionEntity promote(String tenantId, Long versionId) {
    ResultVersionEntity target = loadOrThrow(tenantId, versionId);
    if (!"PENDING".equals(target.status())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.result_version.promote_state_invalid");
    }
    Instant now = dateTimeSupport.nowInstant();
    // 1) 同 business_key 的旧 EFFECTIVE → SUPERSEDED
    resultVersionMapper.supersedePriorEffective(tenantId, target.businessKey(), now);
    // 2) 当前 PENDING → EFFECTIVE（CAS 在 status='PENDING' 上守护，并发 promote 抢同一行只允许一份成功）
    int updated = resultVersionMapper.promoteToEffective(tenantId, versionId, now);
    if (updated == 0) {
      throw new OptimisticLockingFailureException(
          "result_version promote race lost: tenantId="
              + tenantId
              + ", id="
              + versionId
              + ", expected status=PENDING");
    }
    ResultVersionEntity promoted = loadOrThrow(tenantId, versionId);
    materializePromotedVersion(promoted);
    log.info(
        "result_version promoted: tenantId={}, id={}, businessKey={}, versionNo={}",
        tenantId,
        versionId,
        target.businessKey(),
        target.versionNo());
    return promoted;
  }

  /** 拒绝 PENDING 版本（直接 ARCHIVED），不影响其它版本。 */
  @Transactional
  public ResultVersionEntity rejectPending(String tenantId, Long versionId) {
    ResultVersionEntity target = loadOrThrow(tenantId, versionId);
    if (!"PENDING".equals(target.status())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.result_version.reject_state_invalid");
    }
    Instant now = dateTimeSupport.nowInstant();
    int updated = resultVersionMapper.rejectPending(tenantId, versionId, now);
    if (updated == 0) {
      throw new OptimisticLockingFailureException(
          "result_version reject race lost: tenantId=" + tenantId + ", id=" + versionId);
    }
    log.info(
        "result_version rejected: tenantId={}, id={}, businessKey={}, versionNo={}",
        tenantId,
        versionId,
        target.businessKey(),
        target.versionNo());
    return loadOrThrow(tenantId, versionId);
  }

  private ResultVersionEntity loadOrThrow(String tenantId, Long versionId) {
    if (!Texts.hasText(tenantId) || versionId == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.result_version.invalid_argument");
    }
    ResultVersionEntity row = resultVersionMapper.selectById(tenantId, versionId);
    if (row == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found");
    }
    return row;
  }

  private void materializePromotedVersion(ResultVersionEntity promoted) {
    if (promoted == null
        || promoted.jobInstanceId() == null
        || !"EFFECTIVE".equals(promoted.status())) {
      return;
    }
    JobInstanceEntity instance =
        jobInstanceMapper.selectById(promoted.tenantId(), promoted.jobInstanceId());
    if (instance == null) {
      log.warn(
          "asset partition materialization skipped after promote: job_instance missing,"
              + " tenantId={}, resultVersionId={}, jobInstanceId={}",
          promoted.tenantId(),
          promoted.id(),
          promoted.jobInstanceId());
      return;
    }
    assetPartitionService.materializeEffectiveJobPartition(instance, promoted);
  }
}
