package com.example.batch.orchestrator.application.service.replay;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import com.example.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import com.example.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.ResultVersionEntity;
import com.example.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import com.example.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-020 §决策 §实施分阶段 Stages 3 + 6 — 批量日维度重放后端入口。
 *
 * <p>覆盖能力：
 *
 * <ul>
 *   <li>{@link #submit(BatchDayReplaySubmitCommand)} —— 创建 session + 物化 entries（按 scope 解析候选）；
 *       autoApprove=true 直接 RUNNING，否则 PENDING_APPROVAL；
 *   <li>{@link #approve} / {@link #cancel} —— 状态机推进；
 *   <li>{@link #executeOutputsOnly} —— OUTPUTS_ONLY scope 的同步路径，直接调用 {@link
 *       ResultVersionPromoteService#promote} 不创建新 instance。
 * </ul>
 *
 * <p>不在本 Stage 范围：dispatcher（Stage 4）/ terminal 回填（Stage 5）。其它 scope 的 entry 当前停在 PENDING，等 Stage 4
 * 落地后由 dispatcher 驱动 rerun。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDayReplayService {

  static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
  static final String STATUS_RUNNING = "RUNNING";
  static final String STATUS_SUCCEEDED = "SUCCEEDED";
  static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
  static final String STATUS_CANCELLED = "CANCELLED";

  static final String ENTRY_PENDING = "PENDING";
  static final String ENTRY_SUCCEEDED = "SUCCEEDED";
  static final String ENTRY_FAILED = "FAILED";

  static final String SCOPE_ALL = "ALL";
  static final String SCOPE_ALL_FAILED = "ALL_FAILED";
  static final String SCOPE_SUBSET = "SUBSET_JOB_CODES";
  static final String SCOPE_OUTPUTS_ONLY = "OUTPUTS_ONLY";

  private final BatchDayReplaySessionMapper sessionMapper;
  private final BatchDayReplayEntryMapper entryMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final ResultVersionMapper resultVersionMapper;
  private final ResultVersionPromoteService promoteService;
  private final BatchDateTimeSupport dateTimeSupport;

  /**
   * 提交 replay session：写聚合 + 物化 entries。同 (tenant, calendarCode, bizDate) 已存在 active session 则拒绝。
   */
  @Transactional
  public BatchDayReplaySessionEntity submit(BatchDayReplaySubmitCommand command) {
    validateCommand(command);
    Instant now = dateTimeSupport.nowInstant();

    String scope = normalizeScope(command.scope());
    String initialStatus = command.autoApprove() ? STATUS_RUNNING : STATUS_PENDING_APPROVAL;

    // 物化 entries
    List<BatchDayReplayEntryEntity> entries = materializeEntries(command, scope, now);
    if (entries.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.no_candidates");
    }

    BatchDayReplaySessionEntity session =
        BatchDayReplaySessionEntity.builder()
            .tenantId(command.tenantId())
            .calendarCode(command.calendarCode())
            .bizDate(command.bizDate())
            .scope(scope)
            .scopePayload(buildScopePayload(command, scope))
            .resultPolicy(defaultIfBlank(command.resultPolicy(), "CREATE_NEW_VERSION"))
            .configVersionPolicy(
                defaultIfBlank(command.configVersionPolicy(), "USE_ORIGINAL_CONFIG"))
            .configVersion(command.configVersion())
            .reason(command.reason())
            .status(initialStatus)
            .totalCount(entries.size())
            .succeededCount(0)
            .failedCount(0)
            .inFlightCount(0)
            .requestedBy(command.requestedBy())
            .startedAt(STATUS_RUNNING.equals(initialStatus) ? now : null)
            .traceId(command.traceId())
            .createdAt(now)
            .updatedAt(now)
            .build();

    try {
      sessionMapper.insert(session);
    } catch (DuplicateKeyException duplicate) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day_replay.active_session_exists");
    }
    // Record 不可变，MyBatis useGeneratedKeys 写不回 id；用 active 唯一索引重读拿持久化后的行。
    BatchDayReplaySessionEntity persisted =
        sessionMapper.selectActiveByCalendarBizDate(
            command.tenantId(), command.calendarCode(), command.bizDate());
    if (persisted == null || persisted.id() == null) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day_replay.active_session_lost");
    }

    // entries 关联 session_id
    Long sessionId = persisted.id();
    List<BatchDayReplayEntryEntity> linkedEntries = new ArrayList<>(entries.size());
    for (BatchDayReplayEntryEntity e : entries) {
      linkedEntries.add(e.toBuilder().sessionId(sessionId).build());
    }
    entryMapper.insertBatch(linkedEntries);

    log.info(
        "batch_day_replay submitted: tenantId={}, calendarCode={}, bizDate={}, scope={},"
            + " sessionId={}, entries={}, status={}",
        command.tenantId(),
        command.calendarCode(),
        command.bizDate(),
        scope,
        sessionId,
        entries.size(),
        initialStatus);
    return persisted;
  }

  /** PENDING_APPROVAL → RUNNING；记录 approver。 */
  @Transactional
  public BatchDayReplaySessionEntity approve(String tenantId, Long sessionId, String approver) {
    BatchDayReplaySessionEntity session = loadOrThrow(tenantId, sessionId);
    if (!STATUS_PENDING_APPROVAL.equals(session.status())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day_replay.approve_state_invalid");
    }
    Instant now = dateTimeSupport.nowInstant();
    int updated =
        sessionMapper.updateStatus(
            tenantId,
            sessionId,
            STATUS_RUNNING,
            List.of(STATUS_PENDING_APPROVAL),
            now,
            null,
            approver,
            now);
    if (updated == 0) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day_replay.approve_state_invalid");
    }
    return sessionMapper.selectById(tenantId, sessionId);
  }

  /** PENDING_APPROVAL / RUNNING → CANCELLED。已 RUNNING 的 instance 不强杀（让它自己跑完）。 */
  @Transactional
  public BatchDayReplaySessionEntity cancel(String tenantId, Long sessionId) {
    BatchDayReplaySessionEntity session = loadOrThrow(tenantId, sessionId);
    if (!isCancellable(session.status())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day_replay.cancel_state_invalid");
    }
    Instant now = dateTimeSupport.nowInstant();
    int updated =
        sessionMapper.updateStatus(
            tenantId,
            sessionId,
            STATUS_CANCELLED,
            List.of(STATUS_PENDING_APPROVAL, STATUS_RUNNING),
            null,
            now,
            null,
            now);
    if (updated == 0) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day_replay.cancel_state_invalid");
    }
    return sessionMapper.selectById(tenantId, sessionId);
  }

  /**
   * OUTPUTS_ONLY scope 同步执行：把 scope_payload.versionIds 列出的 result_version 直接 promote 到
   * EFFECTIVE，每条对应一行 entry 落 SUCCEEDED / FAILED；session 自动 SUCCEEDED / PARTIAL_FAILED。
   */
  @Transactional
  public BatchDayReplaySessionEntity executeOutputsOnly(String tenantId, Long sessionId) {
    BatchDayReplaySessionEntity session = loadOrThrow(tenantId, sessionId);
    if (!SCOPE_OUTPUTS_ONLY.equals(session.scope())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day_replay.outputs_only_scope_required");
    }
    if (!STATUS_RUNNING.equals(session.status())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.not_running");
    }
    List<BatchDayReplayEntryEntity> entries =
        entryMapper.selectBySessionAndStatus(sessionId, ENTRY_PENDING, Integer.MAX_VALUE);
    if (entries == null) {
      entries = List.of();
    }
    Instant now = dateTimeSupport.nowInstant();
    int succeeded = 0;
    int failed = 0;
    for (BatchDayReplayEntryEntity entry : entries) {
      Long versionId = entry.resultVersionId();
      try {
        if (versionId == null) {
          throw BizException.of(
              ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.outputs_only_version_missing");
        }
        promoteService.promote(tenantId, versionId);
        entryMapper.updateStatus(entry.id(), ENTRY_SUCCEEDED, null, versionId, null, now, now, now);
        succeeded++;
      } catch (Exception failure) {
        entryMapper.updateStatus(
            entry.id(),
            ENTRY_FAILED,
            null,
            versionId,
            truncate(failure.getMessage(), 1024),
            now,
            now,
            now);
        failed++;
        log.warn(
            "batch_day_replay outputs_only entry failed: sessionId={}, entryId={}, msg={}",
            sessionId,
            entry.id(),
            failure.getMessage());
      }
    }
    sessionMapper.updateCounts(
        tenantId, sessionId, succeeded, failed, 0, session.totalCount(), now);
    String terminalStatus = failed > 0 ? STATUS_PARTIAL_FAILED : STATUS_SUCCEEDED;
    sessionMapper.updateStatus(
        tenantId, sessionId, terminalStatus, List.of(STATUS_RUNNING), null, now, null, now);
    log.info(
        "batch_day_replay outputs_only completed: sessionId={}, succeeded={}, failed={},"
            + " terminalStatus={}",
        sessionId,
        succeeded,
        failed,
        terminalStatus);
    return sessionMapper.selectById(tenantId, sessionId);
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private void validateCommand(BatchDayReplaySubmitCommand command) {
    if (command == null
        || !Texts.hasText(command.tenantId())
        || !Texts.hasText(command.calendarCode())
        || command.bizDate() == null
        || !Texts.hasText(command.scope())
        || !Texts.hasText(command.reason())
        || !Texts.hasText(command.requestedBy())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_argument");
    }
  }

  private String normalizeScope(String scope) {
    String upper = scope.trim().toUpperCase(Locale.ROOT);
    if (!List.of(SCOPE_ALL, SCOPE_ALL_FAILED, SCOPE_SUBSET, SCOPE_OUTPUTS_ONLY).contains(upper)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_scope");
    }
    return upper;
  }

  private List<BatchDayReplayEntryEntity> materializeEntries(
      BatchDayReplaySubmitCommand command, String scope, Instant now) {
    if (SCOPE_OUTPUTS_ONLY.equals(scope)) {
      return materializeOutputsOnlyEntries(command, now);
    }
    List<String> statuses =
        switch (scope) {
          case SCOPE_ALL -> List.of("SUCCESS", "FAILED", "PARTIAL_FAILED");
          case SCOPE_ALL_FAILED -> List.of("FAILED", "PARTIAL_FAILED");
          case SCOPE_SUBSET -> List.of("SUCCESS", "FAILED", "PARTIAL_FAILED");
          default -> List.of();
        };
    List<String> jobCodes =
        SCOPE_SUBSET.equals(scope) && command.jobCodes() != null && !command.jobCodes().isEmpty()
            ? command.jobCodes()
            : List.of();
    if (SCOPE_SUBSET.equals(scope) && jobCodes.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.subset_job_codes_required");
    }
    List<JobInstanceEntity> candidates =
        jobInstanceMapper.selectBatchDayCandidates(
            command.tenantId(), command.calendarCode(), command.bizDate(), statuses, jobCodes);
    if (candidates == null) {
      return List.of();
    }
    List<BatchDayReplayEntryEntity> entries = new ArrayList<>(candidates.size());
    for (JobInstanceEntity ji : candidates) {
      if (ji == null
          || ji.getJobCode() == null
          || ji.getJobCode().isBlank()
          || ji.getId() == null) {
        continue;
      }
      entries.add(
          BatchDayReplayEntryEntity.builder()
              .tenantId(ji.getTenantId())
              .jobCode(ji.getJobCode())
              .sourceInstanceId(ji.getId())
              .status(ENTRY_PENDING)
              .createdAt(now)
              .updatedAt(now)
              .build());
    }
    return entries;
  }

  private List<BatchDayReplayEntryEntity> materializeOutputsOnlyEntries(
      BatchDayReplaySubmitCommand command, Instant now) {
    if (command.versionIds() == null || command.versionIds().isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.outputs_only_version_ids_required");
    }
    // R7-A3-P1: 一次批量取所有 versionId，避免 N+1 selectById 循环。
    List<ResultVersionEntity> versions =
        resultVersionMapper.selectByIds(command.tenantId(), command.versionIds());
    Map<Long, ResultVersionEntity> byId = new HashMap<>(versions.size() * 2);
    for (ResultVersionEntity v : versions) {
      byId.put(v.id(), v);
    }
    List<BatchDayReplayEntryEntity> entries = new ArrayList<>(command.versionIds().size());
    for (Long versionId : command.versionIds()) {
      ResultVersionEntity version = byId.get(versionId);
      if (version == null) {
        throw BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found");
      }
      entries.add(
          BatchDayReplayEntryEntity.builder()
              .tenantId(command.tenantId())
              .jobCode(deriveJobCode(version.businessKey()))
              .sourceInstanceId(version.jobInstanceId())
              .resultVersionId(versionId)
              .status(ENTRY_PENDING)
              .createdAt(now)
              .updatedAt(now)
              .build());
    }
    return entries;
  }

  private String deriveJobCode(String businessKey) {
    // ADR-017 形如 job:{jobCode}:{bizDate}。R2-P1-5：之前 fallback 返回 "UNKNOWN"，
    // BatchDayReplayTerminalReconciler 按 (sessionId, tenantId, jobCode) 匹配不到任何 entry
    // → session 永远卡 RUNNING、inFlight 永不减。改为 fail-fast：解析失败立即拒绝创建 replay entry，
    // 让运维显式修 business_key 而不是产生卡死的 session。
    if (!Texts.hasText(businessKey)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.replay.business_key_blank",
          businessKey == null ? "<null>" : "<blank>");
    }
    String[] parts = businessKey.split(":");
    if (parts.length < 2 || !Texts.hasText(parts[1])) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.replay.business_key_unparseable", businessKey);
    }
    return parts[1];
  }

  private String buildScopePayload(BatchDayReplaySubmitCommand command, String scope) {
    Map<String, Object> payload = new LinkedHashMap<>();
    if (SCOPE_SUBSET.equals(scope) && command.jobCodes() != null) {
      payload.put("jobCodes", command.jobCodes());
    }
    if (SCOPE_OUTPUTS_ONLY.equals(scope) && command.versionIds() != null) {
      payload.put("versionIds", command.versionIds());
    }
    return payload.isEmpty() ? null : JsonUtils.toJson(payload);
  }

  private boolean isCancellable(String status) {
    return STATUS_PENDING_APPROVAL.equals(status) || STATUS_RUNNING.equals(status);
  }

  private BatchDayReplaySessionEntity loadOrThrow(String tenantId, Long sessionId) {
    if (!Texts.hasText(tenantId) || sessionId == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_argument");
    }
    BatchDayReplaySessionEntity session = sessionMapper.selectById(tenantId, sessionId);
    if (session == null) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.not_found");
    }
    return session;
  }

  private String defaultIfBlank(String value, String defaultValue) {
    return Texts.hasText(value) ? value : defaultValue;
  }

  private String truncate(String text, int max) {
    if (text == null) {
      return null;
    }
    return text.length() <= max ? text : text.substring(0, max);
  }
}
