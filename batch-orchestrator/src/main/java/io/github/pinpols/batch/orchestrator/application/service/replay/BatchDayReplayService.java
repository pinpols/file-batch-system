package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.enums.BatchDayReplayCandidateSource;
import io.github.pinpols.batch.common.enums.BatchDayReplayExecutionMode;
import io.github.pinpols.batch.common.enums.BatchDayReplayScope;
import io.github.pinpols.batch.common.enums.BatchLifecycleStatus;
import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.enums.ConfigVersionPolicy;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ResultVersionPolicy;
import io.github.pinpols.batch.common.enums.ScheduleType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlan;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanBuilder;
import io.github.pinpols.batch.orchestrator.application.plan.SchedulePlanCommand;
import io.github.pinpols.batch.orchestrator.application.service.version.ResultVersionPromoteService;
import io.github.pinpols.batch.orchestrator.config.BatchDayDryRunProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDefinitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplayEntryMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import io.github.pinpols.batch.orchestrator.mapper.view.BatchDayReplayAssetPartitionImpactView;
import io.github.pinpols.batch.orchestrator.mapper.view.BatchDayReplayDispatchImpactView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ADR-020 / ADR-026 批量日重放与整日 dry-run 后端入口。
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
 * <p>历史实例与调度计划两类候选都先物化为 entry，再由 dispatcher 驱动；dry-run 强制使用隔离结果策略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDayReplayService {

  static final String STATUS_PENDING_APPROVAL = ConfigLifecycleStatus.PENDING_APPROVAL.code();
  static final String STATUS_RUNNING = BatchLifecycleStatus.RUNNING.code();
  static final String STATUS_SUCCEEDED = "SUCCEEDED";
  static final String STATUS_PARTIAL_FAILED = JobInstanceStatus.PARTIAL_FAILED.code();
  static final String STATUS_CANCELLED = "CANCELLED";

  static final String ENTRY_PENDING = "PENDING";
  static final String ENTRY_SUCCEEDED = "SUCCEEDED";
  static final String ENTRY_FAILED = "FAILED";

  private final BatchDayReplaySessionMapper sessionMapper;
  private final BatchDayReplayEntryMapper entryMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final ResultVersionMapper resultVersionMapper;
  private final ResultVersionPromoteService promoteService;
  private final BatchDateTimeSupport dateTimeSupport;
  private final BatchDayDryRunProperties dryRunProperties;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final SchedulePlanBuilder schedulePlanBuilder;
  private final BatchDayPlanCalendarMapper planCalendarMapper;
  private final DisasterDayOverrideMapper disasterDayOverrideMapper;
  private final BatchTimezoneProvider timezoneProvider;

  /**
   * 提交 replay session：写聚合 + 物化 entries。同 (tenant, calendarCode, bizDate) 已存在 active session 则拒绝。
   */
  @Transactional
  public BatchDayReplaySessionEntity submit(BatchDayReplaySubmitCommand command) {
    validateCommand(command);
    Instant now = dateTimeSupport.nowInstant();

    String scope = normalizeScope(command.scope());
    String executionMode = normalizeExecutionMode(command.executionMode());
    String candidateSource = normalizeCandidateSource(command.candidateSource());
    String configVersionPolicy =
        normalizeConfigVersionPolicy(command.configVersionPolicy(), command.configVersion());
    validateModeContract(scope, executionMode, candidateSource, true);
    String initialStatus = command.autoApprove() ? STATUS_RUNNING : STATUS_PENDING_APPROVAL;

    // 物化 entries
    List<BatchDayReplayEntryEntity> entries = materializeEntries(command, scope, now);
    if (entries.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.no_candidates");
    }
    enforceDryRunCapacity(executionMode, entries.size());

    BatchDayReplaySessionEntity session = BatchDayReplaySessionEntity.builder()
        .tenantId(command.tenantId())
        .calendarCode(command.calendarCode())
        .bizDate(command.bizDate())
        .scope(scope)
        .executionMode(executionMode)
        .candidateSource(candidateSource)
        .scopePayload(buildScopePayload(command, scope))
        .resultPolicy(resolveResultPolicy(command, executionMode))
        .configVersionPolicy(configVersionPolicy)
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
    BatchDayReplaySessionEntity persisted = sessionMapper.selectActiveByCalendarBizDate(
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
            + " executionMode={}, candidateSource={}, sessionId={}, entries={}, status={}",
        command.tenantId(),
        command.calendarCode(),
        command.bizDate(),
        scope,
        executionMode,
        candidateSource,
        sessionId,
        entries.size(),
        initialStatus);
    return persisted;
  }

  /** 只读预览 replay 影响范围：复用 submit 的候选解析,但不写 session/entry、不触发审批。 */
  @Transactional(readOnly = true)
  public BatchDayReplayPreviewResponse preview(BatchDayReplaySubmitCommand command) {
    validateCommand(command);
    Instant now = dateTimeSupport.nowInstant();
    String scope = normalizeScope(command.scope());
    String executionMode = normalizeExecutionMode(command.executionMode());
    String candidateSource = normalizeCandidateSource(command.candidateSource());
    String configVersionPolicy =
        normalizeConfigVersionPolicy(command.configVersionPolicy(), command.configVersion());
    validateModeContract(scope, executionMode, candidateSource, false);
    List<BatchDayReplayEntryEntity> entries = materializeEntries(command, scope, now);
    String resultPolicy = resolveResultPolicy(command, executionMode);
    Map<Long, String> versionBusinessKeys = loadVersionBusinessKeys(command, scope);
    List<BatchDayReplayPreviewResponse.PreviewEntry> previewEntries = entries.stream()
        .map(entry -> toPreviewEntry(command, scope, entry, versionBusinessKeys))
        .toList();
    List<BatchDayReplayPreviewResponse.ResultVersionImpact> impacts = previewEntries.stream()
        .map(entry -> toResultVersionImpact(entry, resultPolicy))
        .toList();
    List<BatchDayReplayPreviewResponse.AssetPartitionImpact> assetPartitionImpacts =
        loadAssetPartitionImpacts(command.tenantId(), previewEntries);
    List<BatchDayReplayPreviewResponse.DispatchImpact> dispatchImpacts =
        loadDispatchImpacts(command.tenantId(), previewEntries);
    List<String> warnings = entries.isEmpty() ? List.of("NO_CANDIDATES") : List.of();
    return new BatchDayReplayPreviewResponse(
        command.tenantId(),
        command.calendarCode(),
        command.bizDate(),
        scope,
        executionMode,
        candidateSource,
        resultPolicy,
        configVersionPolicy,
        command.configVersion(),
        entries.size(),
        previewEntries,
        impacts,
        assetPartitionImpacts,
        dispatchImpacts,
        warnings);
  }

  /** 查询 replay session 详情，统一复用应用层租户和存在性校验。 */
  @Transactional(readOnly = true)
  public BatchDayReplaySessionEntity getSession(String tenantId, Long sessionId) {
    return loadOrThrow(tenantId, sessionId);
  }

  /** 查询 replay entry 进度列表；Controller 只负责 HTTP 参数，查询约束留在应用层。 */
  @Transactional(readOnly = true)
  public List<BatchDayReplayEntryEntity> listEntries(
      String tenantId, Long sessionId, String status, int limit) {
    if (!Texts.hasText(tenantId) || sessionId == null || limit <= 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_argument");
    }
    loadOrThrow(tenantId, sessionId);
    return entryMapper.selectBySessionAndStatus(sessionId, tenantId, status, limit);
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
    int updated = sessionMapper.updateStatus(
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
    int updated = sessionMapper.updateStatus(
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
    if (!BatchDayReplayScope.OUTPUTS_ONLY.code().equals(session.scope())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.batch_day_replay.outputs_only_scope_required");
    }
    if (!STATUS_RUNNING.equals(session.status())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.not_running");
    }
    List<BatchDayReplayEntryEntity> entries =
        entryMapper.selectBySessionAndStatus(sessionId, tenantId, ENTRY_PENDING, Integer.MAX_VALUE);
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
    if (BatchDayReplayScope.fromCodeOrNull(upper) == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_scope");
    }
    return upper;
  }

  private String normalizeExecutionMode(String value) {
    String normalized = defaultIfBlank(value, BatchDayReplayExecutionMode.REPLAY.code())
        .trim()
        .toUpperCase(Locale.ROOT);
    if (BatchDayReplayExecutionMode.fromCodeOrNull(normalized) == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_execution_mode");
    }
    return normalized;
  }

  private String normalizeCandidateSource(String value) {
    String normalized = defaultIfBlank(
            value, BatchDayReplayCandidateSource.EXISTING_INSTANCES.code())
        .trim()
        .toUpperCase(Locale.ROOT);
    if (BatchDayReplayCandidateSource.fromCodeOrNull(normalized) == null) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_candidate_source");
    }
    return normalized;
  }

  private String normalizeConfigVersionPolicy(String value, Integer configVersion) {
    String requested = defaultIfBlank(value, ConfigVersionPolicy.USE_ORIGINAL_CONFIG.code());
    ConfigVersionPolicy policy = ConfigVersionPolicy.fromCodeOrNull(requested);
    if (EmptyChecks.isNull(policy)
        || (policy == ConfigVersionPolicy.USE_SPECIFIED_VERSION
            && EmptyChecks.isNull(configVersion))) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_argument");
    }
    return policy.code();
  }

  private void validateModeContract(
      String scope, String executionMode, String candidateSource, boolean requireEnabled) {
    boolean dryRun = BatchDayReplayExecutionMode.DRY_RUN.code().equals(executionMode);
    if (!dryRun
        && !BatchDayReplayCandidateSource.EXISTING_INSTANCES.code().equals(candidateSource)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.schedule_plan_requires_dry_run");
    }
    if (!dryRun) {
      return;
    }
    if (requireEnabled && !dryRunProperties.isEnabled()) {
      throw BizException.of(ResultCode.FORBIDDEN, "error.batch_day_replay.dry_run_disabled");
    }
    if (BatchDayReplayScope.OUTPUTS_ONLY.code().equals(scope)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.dry_run_outputs_only_forbidden");
    }
  }

  private void enforceDryRunCapacity(String executionMode, int entryCount) {
    if (!BatchDayReplayExecutionMode.DRY_RUN.code().equals(executionMode)) {
      return;
    }
    int limit = Math.max(1, dryRunProperties.getMaxActiveEntries());
    if (entryCount > limit) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          "error.batch_day_replay.dry_run_capacity_exceeded",
          entryCount,
          limit);
    }
  }

  private String resolveResultPolicy(BatchDayReplaySubmitCommand command, String executionMode) {
    if (BatchDayReplayExecutionMode.DRY_RUN.code().equals(executionMode)) {
      return ResultVersionPolicy.DRY_RUN_ONLY.code();
    }
    return defaultIfBlank(command.resultPolicy(), ResultVersionPolicy.CREATE_NEW_VERSION.code());
  }

  private List<BatchDayReplayEntryEntity> materializeEntries(
      BatchDayReplaySubmitCommand command, String scope, Instant now) {
    if (BatchDayReplayCandidateSource.SCHEDULE_PLAN
        .code()
        .equals(normalizeCandidateSource(command.candidateSource()))) {
      return materializeSchedulePlanEntries(command, scope, now);
    }
    BatchDayReplayScope scopeType = BatchDayReplayScope.fromCodeOrNull(scope);
    if (scopeType == BatchDayReplayScope.OUTPUTS_ONLY) {
      return materializeOutputsOnlyEntries(command, now);
    }
    List<String> statuses =
        switch (scopeType) {
          case ALL, SUBSET_JOB_CODES ->
            List.of(
                BatchLifecycleStatus.SUCCESS.code(),
                BatchLifecycleStatus.FAILED.code(),
                JobInstanceStatus.PARTIAL_FAILED.code());
          case ALL_FAILED ->
            List.of(BatchLifecycleStatus.FAILED.code(), JobInstanceStatus.PARTIAL_FAILED.code());
          case OUTPUTS_ONLY -> List.of();
        };
    List<String> jobCodes = scopeType == BatchDayReplayScope.SUBSET_JOB_CODES
            && command.jobCodes() != null
            && !command.jobCodes().isEmpty()
        ? command.jobCodes()
        : List.of();
    if (scopeType == BatchDayReplayScope.SUBSET_JOB_CODES && jobCodes.isEmpty()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.subset_job_codes_required");
    }
    List<JobInstanceEntity> candidates = jobInstanceMapper.selectBatchDayCandidates(
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
      entries.add(BatchDayReplayEntryEntity.builder()
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

  private List<BatchDayReplayEntryEntity> materializeSchedulePlanEntries(
      BatchDayReplaySubmitCommand command, String scope, Instant now) {
    BatchDayReplayScope scopeType = BatchDayReplayScope.fromCodeOrNull(scope);
    if (scopeType == BatchDayReplayScope.ALL_FAILED
        || scopeType == BatchDayReplayScope.OUTPUTS_ONLY) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.schedule_plan_scope_invalid");
    }
    List<String> requestedCodes = scopeType == BatchDayReplayScope.SUBSET_JOB_CODES
        ? normalizeRequestedJobCodes(command.jobCodes())
        : List.of();
    if (isCalendarBlocked(command, now)) {
      return List.of();
    }
    List<JobDefinitionEntity> definitions =
        jobDefinitionMapper.selectByTenantAndEnabled(command.tenantId(), true);
    if (EmptyChecks.isEmpty(definitions)) {
      return List.of();
    }
    List<BatchDayReplayEntryEntity> entries = new ArrayList<>();
    for (JobDefinitionEntity definition : definitions) {
      if (!isSchedulePlanCandidate(
          definition, command.calendarCode(), command.bizDate(), requestedCodes)) {
        continue;
      }
      SchedulePlan plan = schedulePlanBuilder.build(new SchedulePlanCommand(
          command.tenantId(), definition.jobCode(), command.bizDate().toString(), Map.of()));
      entries.add(BatchDayReplayEntryEntity.builder()
          .tenantId(command.tenantId())
          .jobCode(definition.jobCode())
          .planSnapshot(buildPlanSnapshot(definition, plan, command.bizDate()))
          .status(ENTRY_PENDING)
          .createdAt(now)
          .updatedAt(now)
          .build());
    }
    if (scopeType == BatchDayReplayScope.SUBSET_JOB_CODES
        && entries.size() != requestedCodes.size()) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.schedule_plan_jobs_unavailable");
    }
    return entries;
  }

  private List<String> normalizeRequestedJobCodes(List<String> jobCodes) {
    if (EmptyChecks.isEmpty(jobCodes)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.subset_job_codes_required");
    }
    return jobCodes.stream().filter(Texts::hasText).map(String::trim).distinct().toList();
  }

  private boolean isSchedulePlanCandidate(
      JobDefinitionEntity definition,
      String calendarCode,
      LocalDate bizDate,
      List<String> requestedCodes) {
    if (definition == null
        || !Texts.hasText(definition.jobCode())
        || !calendarCode.equals(definition.calendarCode())) {
      return false;
    }
    if (EmptyChecks.isNotEmpty(requestedCodes) && !requestedCodes.contains(definition.jobCode())) {
      return false;
    }
    if (ScheduleType.MANUAL.code().equalsIgnoreCase(definition.scheduleType())) {
      return false;
    }
    if (!ScheduleType.CRON.code().equalsIgnoreCase(definition.scheduleType())) {
      return true;
    }
    if (!Texts.hasText(definition.scheduleExpr())) {
      return false;
    }
    try {
      ZoneId zone = timezoneProvider.resolveOrDefault(definition.timezone());
      ZonedDateTime dayStart = bizDate.atStartOfDay(zone);
      ZonedDateTime next =
          CronExpression.parse(definition.scheduleExpr()).next(dayStart.minusNanos(1));
      return next != null && next.isBefore(bizDate.plusDays(1).atStartOfDay(zone));
    } catch (RuntimeException invalidSchedule) {
      log.warn(
          "skip invalid schedule-plan candidate: tenantId={}, jobCode={}, scheduleExpr={}, msg={}",
          definition.tenantId(),
          definition.jobCode(),
          definition.scheduleExpr(),
          invalidSchedule.getMessage());
      return false;
    }
  }

  private boolean isCalendarBlocked(BatchDayReplaySubmitCommand command, Instant now) {
    if (disasterDayOverrideMapper.selectActiveByCalendarBizDate(
            command.tenantId(), command.calendarCode(), command.bizDate(), now)
        != null) {
      return true;
    }
    return "HOLIDAY"
        .equalsIgnoreCase(planCalendarMapper.selectEffectiveDayType(
            command.tenantId(), command.calendarCode(), command.bizDate()));
  }

  private String buildPlanSnapshot(
      JobDefinitionEntity definition, SchedulePlan plan, LocalDate bizDate) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("schemaVersion", "batch-day-dry-run-plan-v1");
    snapshot.put("jobDefinitionId", definition.id());
    snapshot.put("jobDefinitionVersion", definition.version());
    snapshot.put("jobCode", definition.jobCode());
    snapshot.put("bizDate", bizDate.toString());
    snapshot.put("scheduleType", definition.scheduleType());
    snapshot.put("scheduleExpr", definition.scheduleExpr());
    snapshot.put("timezone", definition.timezone());
    snapshot.put("queueCode", plan.getQueueCode());
    snapshot.put("workerGroup", plan.getWorkerGroup());
    snapshot.put("workerType", plan.getDefaultWorkerType());
    snapshot.put("partitionCount", plan.getPartitionCount());
    snapshot.put("defaultParams", definition.defaultParams());
    return JsonUtils.toJson(snapshot);
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
    List<BatchDayReplayEntryEntity> entries =
        new ArrayList<>(command.versionIds().size());
    for (Long versionId : command.versionIds()) {
      ResultVersionEntity version = byId.get(versionId);
      if (version == null) {
        throw BizException.of(ResultCode.NOT_FOUND, "error.result_version.not_found");
      }
      entries.add(BatchDayReplayEntryEntity.builder()
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
    // 让运维显式修 business_key 而不是产生长期停滞的 session。
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
    if (BatchDayReplayScope.SUBSET_JOB_CODES.code().equals(scope) && command.jobCodes() != null) {
      payload.put("jobCodes", command.jobCodes());
    }
    if (BatchDayReplayScope.OUTPUTS_ONLY.code().equals(scope) && command.versionIds() != null) {
      payload.put("versionIds", command.versionIds());
    }
    return payload.isEmpty() ? null : JsonUtils.toJson(payload);
  }

  private BatchDayReplayPreviewResponse.PreviewEntry toPreviewEntry(
      BatchDayReplaySubmitCommand command,
      String scope,
      BatchDayReplayEntryEntity entry,
      Map<Long, String> versionBusinessKeys) {
    String action = BatchDayReplayScope.OUTPUTS_ONLY.code().equals(scope)
        ? "PROMOTE_RESULT_VERSION"
        : entry.sourceInstanceId() == null ? "LAUNCH_SCHEDULE_PLAN" : "RERUN_INSTANCE";
    String businessKey = BatchDayReplayScope.OUTPUTS_ONLY.code().equals(scope)
        ? versionBusinessKeys.getOrDefault(entry.resultVersionId(), "")
        : "job:" + entry.jobCode() + ":" + command.bizDate();
    return new BatchDayReplayPreviewResponse.PreviewEntry(
        entry.jobCode(), entry.sourceInstanceId(), entry.resultVersionId(), action, businessKey);
  }

  private BatchDayReplayPreviewResponse.ResultVersionImpact toResultVersionImpact(
      BatchDayReplayPreviewResponse.PreviewEntry entry, String resultPolicy) {
    String action =
        entry.resultVersionId() == null ? "CREATE_NEW_RESULT_VERSION" : "PROMOTE_EXISTING_VERSION";
    return new BatchDayReplayPreviewResponse.ResultVersionImpact(
        entry.businessKey(),
        entry.sourceInstanceId(),
        entry.resultVersionId(),
        action,
        resultPolicy);
  }

  private List<BatchDayReplayPreviewResponse.AssetPartitionImpact> loadAssetPartitionImpacts(
      String tenantId, List<BatchDayReplayPreviewResponse.PreviewEntry> previewEntries) {
    List<String> businessKeys = previewEntries.stream()
        .map(BatchDayReplayPreviewResponse.PreviewEntry::businessKey)
        .filter(Texts::hasText)
        .distinct()
        .toList();
    if (businessKeys.isEmpty()) {
      return List.of();
    }
    List<BatchDayReplayAssetPartitionImpactView> rows =
        entryMapper.selectAssetPartitionImpacts(tenantId, businessKeys);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .map(row -> new BatchDayReplayPreviewResponse.AssetPartitionImpact(
            row.businessKey(),
            row.assetCode(),
            row.partitionKey(),
            row.currentResultVersionId(),
            row.freshnessStatus()))
        .toList();
  }

  private List<BatchDayReplayPreviewResponse.DispatchImpact> loadDispatchImpacts(
      String tenantId, List<BatchDayReplayPreviewResponse.PreviewEntry> previewEntries) {
    List<Long> sourceInstanceIds = previewEntries.stream()
        .map(BatchDayReplayPreviewResponse.PreviewEntry::sourceInstanceId)
        .filter(id -> id != null && id > 0)
        .distinct()
        .toList();
    if (sourceInstanceIds.isEmpty()) {
      return List.of();
    }
    List<BatchDayReplayDispatchImpactView> rows =
        entryMapper.selectDispatchImpacts(tenantId, sourceInstanceIds);
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    return rows.stream()
        .map(row -> new BatchDayReplayPreviewResponse.DispatchImpact(
            row.sourceInstanceId(),
            row.recordCount(),
            row.sentCount(),
            row.failedCount(),
            row.pendingReceiptCount()))
        .toList();
  }

  private Map<Long, String> loadVersionBusinessKeys(
      BatchDayReplaySubmitCommand command, String scope) {
    if (!BatchDayReplayScope.OUTPUTS_ONLY.code().equals(scope)
        || command.versionIds() == null
        || command.versionIds().isEmpty()) {
      return Map.of();
    }
    List<ResultVersionEntity> versions =
        resultVersionMapper.selectByIds(command.tenantId(), command.versionIds());
    Map<Long, String> businessKeys = new HashMap<>(versions.size() * 2);
    for (ResultVersionEntity version : versions) {
      businessKeys.put(version.id(), version.businessKey());
    }
    return businessKeys;
  }

  private static String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Long longValue(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    return Long.parseLong(String.valueOf(value));
  }

  private static long longValueOrZero(Object value) {
    Long parsed = longValue(value);
    return parsed == null ? 0L : parsed;
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
