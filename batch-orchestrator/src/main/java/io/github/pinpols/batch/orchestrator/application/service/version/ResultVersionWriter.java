package io.github.pinpols.batch.orchestrator.application.service.version;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.service.dataquality.DataQualityCheckExecutor;
import io.github.pinpols.batch.orchestrator.application.service.dataquality.DataQualityGateOutcome;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-017 §决策 §实施分阶段 Stage 2 — 结果版本写入入口。
 *
 * <p>orchestrator 在 job_instance 进入终态（SUCCESS / PARTIAL_FAILED）时调用 {@link
 * #writeOnTerminal(JobInstanceEntity, Map)}，将 worker 上报的 outputs 落到 {@code result_version} 主表，保证：
 *
 * <ul>
 *   <li>同 {@code (tenant_id, business_key)} 至多 1 行 EFFECTIVE（旧 EFFECTIVE 同事务推到 SUPERSEDED）；
 *   <li>每个 job_instance 至多 1 行 result_version（{@link ResultVersionMapper#selectByJobInstanceId}
 *       幂等保护，重复 report 不重复写入数据库）；
 *   <li>{@link #resolvePromotionPolicy} 从 {@code job_instance.rerun_policy_snapshot.resultPolicy}
 *       推导：{@code CREATE_NEW_VERSION} → AUTO_LATEST，{@code KEEP_BOTH / MANUAL_CONFIRM_EFFECTIVE} →
 *       MANUAL_APPROVAL，无 snapshot → AUTO_LATEST（首跑默认）。
 * </ul>
 *
 * <p>当前 Stage 2 仅落 {@code payload_storage='INLINE_JSON'} 路径；EXTERNAL_REF / FILE_RECORD 留 Stage 5。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultVersionWriter {

  static final String STATUS_EFFECTIVE = "EFFECTIVE";
  static final String STATUS_PENDING = "PENDING";
  static final String STATUS_DRY_RUN = "DRY_RUN";
  static final String PROMOTION_AUTO_LATEST = "AUTO_LATEST";
  static final String PROMOTION_MANUAL_APPROVAL = "MANUAL_APPROVAL";
  static final String PAYLOAD_STORAGE_INLINE_JSON = "INLINE_JSON";

  private final ResultVersionMapper resultVersionMapper;
  private final BatchDateTimeSupport dateTimeSupport;

  /**
   * ADR-021 DQ gate 执行器（可选注入）：BLOCKER 失败 → 强制 promotion_policy=MANUAL_APPROVAL；缺省 / NO_RULES →
   * 不影响现有 promote 流程。
   */
  private final ObjectProvider<DataQualityCheckExecutor> dqExecutorProvider;

  /**
   * 在 job_instance 进入 SUCCESS / PARTIAL_FAILED 终态时调用。 调用方必须确保已经在外层事务里完成了 instance
   * 状态转换；本方法在同一事务里写入版本。
   *
   * <p>非成功类终态（FAILED / CANCELLED / TERMINATED）以及 instance 缺关键字段（jobCode / bizDate）一律 skip ——
   * 不抛异常，避免污染状态机主链。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void writeOnTerminal(JobInstanceEntity instance, Map<String, Object> outputs) {
    if (instance == null
        || instance.getId() == null
        || !Texts.hasText(instance.getTenantId())
        || !isSuccessTerminal(instance.getInstanceStatus())) {
      return;
    }
    if (!Texts.hasText(instance.getJobCode()) || instance.getBizDate() == null) {
      return;
    }
    String tenantId = instance.getTenantId();
    String businessKey = buildBusinessKey(instance);

    resultVersionMapper.lockBusinessKey(tenantId, businessKey);

    // 幂等：同一 job_instance 只允许落 1 行 result_version（重复 report 不重复创建）
    ResultVersionEntity existing =
        resultVersionMapper.selectByJobInstanceId(tenantId, instance.getId());
    if (existing != null) {
      return;
    }

    String promotionPolicy = resolvePromotionPolicy(instance);
    Instant now = dateTimeSupport.nowInstant();
    String status;
    Instant effectiveAt;
    String dqGateStatus = null;
    boolean dryRun = Boolean.TRUE.equals(instance.getDryRun());
    if (dryRun) {
      // ADR-026 dry-run：不进 EFFECTIVE 链，落 DRY_RUN 状态供运维核对，不超越也不影响实盘版本
      status = STATUS_DRY_RUN;
      effectiveAt = null;
    } else {
      // ADR-021 DQ gate：若 BLOCKER 失败 → 强制 MANUAL_APPROVAL（即使 resultPolicy=CREATE_NEW_VERSION）
      DataQualityGateOutcome dqOutcome = runDqGateSafely(instance, businessKey);
      dqGateStatus =
          switch (dqOutcome.status()) {
            case BLOCKED -> "BLOCKED";
            case WARN -> "WARN";
            case PASS -> "PASS";
            case NO_RULES -> null;
          };
      boolean dqBlocked = dqOutcome.status() == DataQualityGateOutcome.GateStatus.BLOCKED;
      if (dqBlocked || PROMOTION_MANUAL_APPROVAL.equals(promotionPolicy)) {
        status = STATUS_PENDING;
        effectiveAt = null;
        if (dqBlocked) {
          promotionPolicy = PROMOTION_MANUAL_APPROVAL;
          log.warn(
              "DQ gate BLOCKED → result_version forced to PENDING/MANUAL_APPROVAL:"
                  + " tenantId={}, businessKey={}, jobInstanceId={}",
              tenantId,
              businessKey,
              instance.getId());
        }
      } else {
        // AUTO_LATEST：先把同 business_key 的旧 EFFECTIVE 推到 SUPERSEDED，再插入新 EFFECTIVE
        resultVersionMapper.supersedePriorEffective(tenantId, businessKey, now);
        status = STATUS_EFFECTIVE;
        effectiveAt = now;
      }
    }

    Integer maxVersion = resultVersionMapper.selectMaxVersionNo(tenantId, businessKey);
    int versionNo = (maxVersion == null ? 0 : maxVersion) + 1;

    final String finalDqGateStatus = dqGateStatus;
    ResultVersionEntity newVersion =
        ResultVersionEntity.builder()
            .dqGateStatus(finalDqGateStatus)
            .tenantId(tenantId)
            .businessKey(businessKey)
            .versionNo(versionNo)
            .jobInstanceId(instance.getId())
            .status(status)
            .effectiveAt(effectiveAt)
            .payloadStorage(PAYLOAD_STORAGE_INLINE_JSON)
            .payloadJson(serializeOutputs(outputs))
            .generatedAt(now)
            .generatedBy(resolveGeneratedBy(instance))
            .promotionPolicy(promotionPolicy)
            .createdAt(now)
            .updatedAt(now)
            .build();
    resultVersionMapper.insert(newVersion);

    log.info(
        "result_version written: tenantId={}, businessKey={}, versionNo={}, status={},"
            + " jobInstanceId={}",
        tenantId,
        businessKey,
        versionNo,
        status,
        instance.getId());
  }

  /** 业务主键格式（ADR-017 §业务主键定义）：{@code job:{jobCode}:{bizDate}}。 */
  private String buildBusinessKey(JobInstanceEntity instance) {
    return "job:" + instance.getJobCode() + ":" + instance.getBizDate();
  }

  /**
   * ADR-021 DQ gate 执行包装：executor bean 缺失 / 抛异常 → 返回 NO_RULES（不影响现有 promote 流程，避免 gate 自身故障阻塞业务）。
   */
  private DataQualityGateOutcome runDqGateSafely(JobInstanceEntity instance, String businessKey) {
    DataQualityCheckExecutor executor = dqExecutorProvider.getIfAvailable();
    if (executor == null) {
      return DataQualityGateOutcome.noRules();
    }
    try {
      return executor.execute(instance, businessKey);
    } catch (RuntimeException ex) {
      SwallowedExceptionLogger.warn(ResultVersionWriter.class, "catch:dq_gate_failure", ex);
      return DataQualityGateOutcome.noRules();
    }
  }

  private boolean isSuccessTerminal(String instanceStatus) {
    return JobInstanceStatus.SUCCESS.code().equals(instanceStatus)
        || JobInstanceStatus.PARTIAL_FAILED.code().equals(instanceStatus)
        || JobInstanceStatus.SUCCESS_DRY_RUN.code().equals(instanceStatus);
  }

  /**
   * 从 {@code job_instance.rerun_policy_snapshot.resultPolicy} 推导 promotion_policy：
   *
   * <ul>
   *   <li>{@code CREATE_NEW_VERSION} 或 null（首跑） → AUTO_LATEST
   *   <li>{@code KEEP_BOTH} / {@code MANUAL_CONFIRM_EFFECTIVE} → MANUAL_APPROVAL
   * </ul>
   */
  String resolvePromotionPolicy(JobInstanceEntity instance) {
    String snapshot = instance.getRerunPolicySnapshot();
    if (!Texts.hasText(snapshot)) {
      return PROMOTION_AUTO_LATEST;
    }
    try {
      Map<?, ?> payload = JsonUtils.fromJson(snapshot, Map.class);
      Object resultPolicy = payload == null ? null : payload.get("resultPolicy");
      if (resultPolicy == null) {
        return PROMOTION_AUTO_LATEST;
      }
      String value = String.valueOf(resultPolicy);
      if ("KEEP_BOTH".equalsIgnoreCase(value)
          || "MANUAL_CONFIRM_EFFECTIVE".equalsIgnoreCase(value)) {
        return PROMOTION_MANUAL_APPROVAL;
      }
      return PROMOTION_AUTO_LATEST;
    } catch (Exception parseFailure) {
      SwallowedExceptionLogger.warn(
          ResultVersionWriter.class, "catch:rerun_policy_snapshot_parse", parseFailure);
      return PROMOTION_AUTO_LATEST;
    }
  }

  private String resolveGeneratedBy(JobInstanceEntity instance) {
    String operatorId = instance.getOperatorId();
    return Texts.hasText(operatorId) ? operatorId : AuditLogConstants.OPERATOR_ID_SYSTEM;
  }

  private String serializeOutputs(Map<String, Object> outputs) {
    if (outputs == null || outputs.isEmpty()) {
      return "{}";
    }
    return JsonUtils.toJson(outputs);
  }
}
