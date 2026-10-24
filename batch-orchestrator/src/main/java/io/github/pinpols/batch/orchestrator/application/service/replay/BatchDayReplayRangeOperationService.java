package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.enums.BatchLifecycleStatus;
import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.enums.ReplayOperationType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.config.ReplayOperationProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplaySessionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.ReplayOperationPreviewTokenEntity;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayReplaySessionMapper;
import io.github.pinpols.batch.orchestrator.mapper.DisasterDayOverrideMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationMapper;
import io.github.pinpols.batch.orchestrator.mapper.ReplayOperationPreviewTokenMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR-049 日期闭区间操作复用既有单日执行单元。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDayReplayRangeOperationService {
  private static final String REPLAY_INVALID_ARGUMENT = "error.batch_day_replay.invalid_argument";

  private static final String OPERATION_TYPE = ReplayOperationType.BATCH_DAY_RANGE.code();
  private static final Duration PREVIEW_TOKEN_TTL = Duration.ofMinutes(5);
  private static final String DISPOSITION_READY = "READY";
  private static final String DISPOSITION_NO_CANDIDATES = "NO_CANDIDATES";
  private static final String DISPOSITION_CALENDAR_BLOCKED = "CALENDAR_BLOCKED";

  private final BatchDayReplayService singleDayReplayService;
  private final ReplayOperationMapper operationMapper;
  private final ReplayOperationPreviewTokenMapper previewTokenMapper;
  private final BatchDayReplaySessionMapper sessionMapper;
  private final BatchDayPlanCalendarMapper planCalendarMapper;
  private final DisasterDayOverrideMapper disasterDayOverrideMapper;
  private final BatchDateTimeSupport dateTimeSupport;
  private final ReplayOperationProperties properties;

  /** 创建冻结预览与操作身份；候选日期明细不保存凭证。 */
  @Transactional
  public ReplayOperationPreviewResponse preview(BatchDayReplayRangeCommand command) {
    validate(command, false);
    operationMapper.setTenantContext(command.tenantId());
    previewTokenMapper.setTenantContext(command.tenantId());
    UUID operationKey = UUID.randomUUID();
    Instant now = dateTimeSupport.nowInstant();
    List<PreparedDay> preparedDays = prepareDays(command, now, false);
    List<ReplayOperationPreviewResponse.DayPreview> days = previewDays(preparedDays);
    int candidateCount = days.stream().mapToInt(ReplayOperationPreviewResponse.DayPreview::candidateCount).sum();
    String requestHash = requestHash(command);
    String snapshotPayload = JsonUtils.toJson(days);
    String snapshotHash = sha256(snapshotPayload);
    UUID rawTokenId = UUID.randomUUID();
    String rawToken = rawTokenId.toString();
    Instant expiresAt = now.plus(PREVIEW_TOKEN_TTL);
    String tokenHash = sha256(rawToken);
    previewTokenMapper.insert(ReplayOperationPreviewTokenEntity.builder()
        .tenantId(command.tenantId())
        .tokenHash(tokenHash)
        .operationKey(operationKey.toString())
        .requestHash(requestHash)
        .snapshotHash(snapshotHash)
        .snapshotPayload(snapshotPayload)
        .expiresAt(expiresAt)
        .createdAt(now)
        .build());
    return new ReplayOperationPreviewResponse(
        command.tenantId(), operationKey, OPERATION_TYPE, command.calendarCode(), command.fromDate(),
        command.throughDate(), command.scope(), candidateCount, rawToken, expiresAt, days);
  }

  /**
   * 同一事务消费操作预览并创建父操作和全部逐日子项。
   * 相同操作键和请求重提时返回原父操作，不重复创建子项。
   */
  @Transactional
  public ReplayOperationEntity submit(BatchDayReplayRangeCommand command) {
    validate(command, true);
    operationMapper.setTenantContext(command.tenantId());
    previewTokenMapper.setTenantContext(command.tenantId());
    String requestHash = requestHash(command);
    ReplayOperationEntity existing = operationMapper.selectByIdentity(
        command.tenantId(), command.operationKey().toString());
    if (EmptyChecks.isNotNull(existing)) {
      if (!requestHash.equals(existing.requestHash())) {
        throw invalidPreview();
      }
      return existing;
    }

    String tokenHash = sha256(command.previewToken());
    ReplayOperationPreviewTokenEntity token;
    try {
      token = previewTokenMapper.selectForUpdate(command.tenantId(), tokenHash);
    } catch (PessimisticLockingFailureException concurrentConsumption) {
      throw invalidPreview();
    }
    boolean previewTokenMatchesRequest =
        EmptyChecks.isNotNull(token)
            && command.operationKey().toString().equals(token.operationKey())
            && requestHash.equals(token.requestHash());
    if (!previewTokenMatchesRequest) {
      throw invalidPreview();
    }
    if (EmptyChecks.isNotNull(token.consumedAt())) {
      ReplayOperationEntity consumedOperation = EmptyChecks.isNull(token.operationId())
          ? null
          : operationMapper.selectById(command.tenantId(), token.operationId());
      boolean requestMatchesConsumedOperation =
          EmptyChecks.isNotNull(consumedOperation) && requestHash.equals(consumedOperation.requestHash());
      if (requestMatchesConsumedOperation) {
        return consumedOperation;
      }
      throw invalidPreview();
    }
    Instant now = dateTimeSupport.nowInstant();
    if (!token.expiresAt().isAfter(now)) {
      throw invalidPreview();
    }

    List<PreparedDay> preparedDays = prepareDays(command, now, true);
    List<ReplayOperationPreviewResponse.DayPreview> days = previewDays(preparedDays);
    String snapshotPayload = JsonUtils.toJson(days);
    String snapshotHash = sha256(snapshotPayload);
    if (!snapshotHash.equals(token.snapshotHash())) {
      throw invalidPreview();
    }
    List<PreparedDay> executableDays = preparedDays.stream()
        .filter(day -> EmptyChecks.isNotNull(day.preview()) && day.preview().totalCount() > 0)
        .toList();
    if (EmptyChecks.isEmpty(Objects.requireNonNull(executableDays))) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.no_candidates");
    }
    if (previewTokenMapper.consume(command.tenantId(), tokenHash, now) != 1) {
      throw invalidPreview();
    }

    ReplaySubmissionSnapshot snapshot = new ReplaySubmissionSnapshot(
        requestHash, snapshotHash, snapshotPayload, executableDays.size());
    ReplayOperationEntity operation = buildOperation(command, snapshot, now);
    operationMapper.insert(operation);
    ReplayOperationEntity persisted = operationMapper.selectByIdentity(
        command.tenantId(), command.operationKey().toString());
    if (EmptyChecks.isNull(persisted) || EmptyChecks.isNull(persisted.id())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.active_session_lost");
    }

    for (PreparedDay preparedDay : executableDays) {
      BatchDayReplaySubmitCommand childCommand = commandForDate(
          command, preparedDay.bizDate(), preparedDay.preview().previewToken());
      BatchDayReplaySessionEntity child = singleDayReplayService.submitForOperation(
          childCommand, persisted.id());
      boolean childSessionIsMissingOrLinkedToAnotherOperation = EmptyChecks.isNull(child)
          || EmptyChecks.isNull(child.id())
          || !persisted.id().equals(child.replayOperationId());
      if (childSessionIsMissingOrLinkedToAnotherOperation) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.active_session_lost");
      }
    }
    if (previewTokenMapper.linkOperation(command.tenantId(), tokenHash, persisted.id()) != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.active_session_lost");
    }
    operationMapper.refreshFromChildSessions(command.tenantId(), persisted.id(), now);
    ReplayOperationEntity result = operationMapper.selectById(command.tenantId(), persisted.id());
    log.info("batch day replay operation accepted: tenantId={}, operationId={}, operationKey={}, days={}",
        command.tenantId(), persisted.id(), command.operationKey(), executableDays.size());
    return result;
  }

  @Transactional(readOnly = true)
  public ReplayOperationEntity get(String tenantId, Long operationId) {
    operationMapper.setTenantContext(tenantId);
    ReplayOperationEntity operation = operationMapper.selectById(tenantId, operationId);
    if (EmptyChecks.isNull(operation) || !OPERATION_TYPE.equals(operation.operationType())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.operation_not_found");
    }
    return operation;
  }

  @Transactional(readOnly = true)
  public List<ReplayOperationEntity> list(String tenantId, int limit) {
    if (!Texts.hasText(tenantId) || limit <= 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    operationMapper.setTenantContext(tenantId);
    return operationMapper.selectRecentByTenant(tenantId, Math.min(limit, 200));
  }

  @Transactional(readOnly = true)
  public List<BatchDayReplaySessionEntity> sessions(String tenantId, Long operationId) {
    get(tenantId, operationId);
    return sessionMapper.selectByReplayOperation(tenantId, operationId);
  }

  @Transactional
  public ReplayOperationEntity approve(String tenantId, Long operationId, String approver) {
    operationMapper.setTenantContext(tenantId);
    if (!Texts.hasText(approver)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    ReplayOperationEntity operation = operationMapper.selectForUpdate(tenantId, operationId);
    if (EmptyChecks.isNull(operation) || !OPERATION_TYPE.equals(operation.operationType())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.operation_not_found");
    }
    if (!ConfigLifecycleStatus.PENDING_APPROVAL.code().equals(operation.status())) {
      if (approver.equals(operation.approvedBy())) {
        return operation;
      }
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.approve_state_invalid");
    }
    for (Long sessionId : operationMapper.selectChildSessionIds(tenantId, operationId)) {
      singleDayReplayService.approve(tenantId, sessionId, approver);
    }
    Instant now = dateTimeSupport.nowInstant();
    if (operationMapper.recordApproval(tenantId, operationId, approver, now) != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.approve_state_invalid");
    }
    operationMapper.refreshFromChildSessions(tenantId, operationId, now);
    return operationMapper.selectById(tenantId, operationId);
  }

  @Transactional
  public ReplayOperationEntity cancel(String tenantId, Long operationId) {
    operationMapper.setTenantContext(tenantId);
    ReplayOperationEntity operation = operationMapper.selectForUpdate(tenantId, operationId);
    if (EmptyChecks.isNull(operation) || !OPERATION_TYPE.equals(operation.operationType())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.operation_not_found");
    }
    for (Long sessionId : operationMapper.selectChildSessionIds(tenantId, operationId)) {
      BatchDayReplaySessionEntity child = sessionMapper.selectForUpdate(tenantId, sessionId);
      boolean childCanBeCancelled =
          EmptyChecks.isNotNull(child)
              && (BatchLifecycleStatus.RUNNING.code().equals(child.status())
                  || ConfigLifecycleStatus.PENDING_APPROVAL.code().equals(child.status()));
      if (childCanBeCancelled) {
        singleDayReplayService.cancel(tenantId, sessionId);
      }
    }
    Instant refreshedAt = dateTimeSupport.nowInstant();
    operationMapper.refreshFromChildSessions(tenantId, operationId, refreshedAt);
    return operationMapper.selectById(tenantId, operationId);
  }

  private record ReplaySubmissionSnapshot(
      String requestHash, String snapshotHash, String snapshotPayload, int executableDayCount) {}

  private ReplayOperationEntity buildOperation(
      BatchDayReplayRangeCommand command, ReplaySubmissionSnapshot snapshot, Instant now) {
    String initialStatus = command.autoApprove()
        ? BatchLifecycleStatus.RUNNING.code()
        : ConfigLifecycleStatus.PENDING_APPROVAL.code();
    ReplayOperationEntity.ReplayOperationEntityBuilder operationBuilder = ReplayOperationEntity.builder()
        .tenantId(command.tenantId())
        .operationKey(command.operationKey().toString())
        .operationType(OPERATION_TYPE)
        .status(initialStatus)
        .calendarCode(command.calendarCode())
        .fromDate(command.fromDate())
        .throughDate(command.throughDate())
        .scope(command.scope());
    String executionMode = firstNonBlank(command.executionMode(), "REPLAY");
    operationBuilder.executionMode(executionMode);
    String candidateSource = firstNonBlank(command.candidateSource(), "EXISTING_INSTANCES");
    operationBuilder.candidateSource(candidateSource);
    ScopePayload scopePayloadFields = new ScopePayload(command.jobCodes(), command.versionIds());
    String scopePayload = JsonUtils.toJson(scopePayloadFields);
    ReplayOperationEntity operation = operationBuilder
        .scopePayload(scopePayload)
        .resultPolicy(command.resultPolicy())
        .configVersionPolicy(command.configVersionPolicy())
        .configVersion(command.configVersion())
        .requestHash(snapshot.requestHash())
        .snapshotHash(snapshot.snapshotHash())
        .snapshotPayload(snapshot.snapshotPayload())
        .reason(command.reason())
        .requestedBy(command.requestedBy())
        .totalCount(snapshot.executableDayCount())
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(snapshot.executableDayCount())
        .startedAt(command.autoApprove() ? now : null)
        .traceId(command.traceId())
        .createdAt(now)
        .updatedAt(now)
        .build();
    return operation;
  }

  private List<PreparedDay> prepareDays(
      BatchDayReplayRangeCommand command, Instant now, boolean issueChildPreviewTokens) {
    long inclusiveSpan = ChronoUnit.DAYS.between(command.fromDate(), command.throughDate()) + 1;
    List<PreparedDay> days = new ArrayList<>((int) inclusiveSpan);
    for (LocalDate date = command.fromDate(); !date.isAfter(command.throughDate()); date = date.plusDays(1)) {
      boolean calendarBlocked = EmptyChecks.isNotNull(disasterDayOverrideMapper.selectActiveByCalendarBizDate(
          command.tenantId(), command.calendarCode(), date, now));
      if (!calendarBlocked) {
        String effectiveDayType = planCalendarMapper.selectEffectiveDayType(
            command.tenantId(), command.calendarCode(), date);
        calendarBlocked = "HOLIDAY".equalsIgnoreCase(effectiveDayType);
      }
      if (calendarBlocked) {
        days.add(new PreparedDay(date, null, DISPOSITION_CALENDAR_BLOCKED));
      } else {
        BatchDayReplaySubmitCommand childCommand = commandForDate(command, date);
        BatchDayReplayPreviewResponse preview = issueChildPreviewTokens
            ? singleDayReplayService.preview(childCommand)
            : singleDayReplayService.previewImpact(childCommand);
        String disposition = preview.totalCount() == 0
            ? DISPOSITION_NO_CANDIDATES
            : DISPOSITION_READY;
        days.add(new PreparedDay(date, preview, disposition));
      }
    }
    return List.copyOf(days);
  }

  private static List<ReplayOperationPreviewResponse.DayPreview> previewDays(List<PreparedDay> preparedDays) {
    return preparedDays.stream().map(prepared -> {
      BatchDayReplayPreviewResponse day = prepared.preview();
      if (EmptyChecks.isNull(day)) {
        return new ReplayOperationPreviewResponse.DayPreview(
            prepared.bizDate(), prepared.disposition(), 0, List.of(), List.of(), List.of(), List.of(), List.of());
      }
      return new ReplayOperationPreviewResponse.DayPreview(
          day.bizDate(), prepared.disposition(), day.totalCount(), day.entries(),
          day.resultVersionImpacts(), day.assetPartitionImpacts(), day.dispatchImpacts(), day.warnings());
    }).toList();
  }

  private static BatchDayReplaySubmitCommand commandForDate(
      BatchDayReplayRangeCommand range, LocalDate bizDate) {
    return commandForDate(range, bizDate, null);
  }

  private static BatchDayReplaySubmitCommand commandForDate(
      BatchDayReplayRangeCommand range, LocalDate bizDate, String previewToken) {
    return BatchDayReplaySubmitCommand.builder()
        .tenantId(range.tenantId())
        .calendarCode(range.calendarCode())
        .bizDate(bizDate)
        .scope(range.scope())
        .jobCodes(range.jobCodes())
        .versionIds(range.versionIds())
        .resultPolicy(range.resultPolicy())
        .configVersionPolicy(range.configVersionPolicy())
        .configVersion(range.configVersion())
        .reason(range.reason())
        .requestedBy(range.requestedBy())
        .autoApprove(range.autoApprove())
        .traceId(range.traceId())
        .executionMode(range.executionMode())
        .candidateSource(range.candidateSource())
        .previewToken(previewToken)
        .build();
  }

  private void validate(BatchDayReplayRangeCommand command, boolean submitting) {
    if (EmptyChecks.isNull(command)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    if (!Texts.hasText(command.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    if (!Texts.hasText(command.calendarCode())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    if (EmptyChecks.isNull(command.fromDate())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    if (EmptyChecks.isNull(command.throughDate())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    if (command.throughDate().isBefore(command.fromDate())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    if (!Texts.hasText(command.scope())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    if (!Texts.hasText(command.reason())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    if (!Texts.hasText(command.requestedBy())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
    long inclusiveSpan = ChronoUnit.DAYS.between(command.fromDate(), command.throughDate()) + 1;
    if (properties.getMaxRangeDays() <= 0 || inclusiveSpan > properties.getMaxRangeDays()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.range_too_large");
    }
    boolean replaySubmissionIdentityIsIncomplete =
        submitting && (EmptyChecks.isNull(command.operationKey()) || !Texts.hasText(command.previewToken()));
    if (replaySubmissionIdentityIsIncomplete) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REPLAY_INVALID_ARGUMENT);
    }
  }

  private String requestHash(BatchDayReplayRangeCommand command) {
    RequestFingerprint fingerprint = new RequestFingerprint(
        command.tenantId(), command.calendarCode(), command.fromDate(), command.throughDate(),
        command.scope(), command.jobCodes(), command.versionIds(), command.resultPolicy(),
        command.configVersionPolicy(), command.configVersion(), command.reason(), command.requestedBy(),
        command.autoApprove(), command.executionMode(), command.candidateSource());
    String fingerprintJson = JsonUtils.toJson(fingerprint);
    return sha256(fingerprintJson);
  }

  private static String firstNonBlank(String value, String defaultValue) {
    return Texts.hasText(value) ? value : defaultValue;
  }

  private static BizException invalidPreview() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.preview_stale");
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
      byte[] digestBytes = digest.digest(valueBytes);
      return HexFormat.of().formatHex(digestBytes);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private record PreparedDay(
      LocalDate bizDate, BatchDayReplayPreviewResponse preview, String disposition) {}

  private record ScopePayload(List<String> jobCodes, List<Long> versionIds) {}

  private record RequestFingerprint(
      String tenantId,
      String calendarCode,
      LocalDate fromDate,
      LocalDate throughDate,
      String scope,
      List<String> jobCodes,
      List<Long> versionIds,
      String resultPolicy,
      String configVersionPolicy,
      Integer configVersion,
      String reason,
      String requestedBy,
      boolean autoApprove,
      String executionMode,
      String candidateSource) {}
}
