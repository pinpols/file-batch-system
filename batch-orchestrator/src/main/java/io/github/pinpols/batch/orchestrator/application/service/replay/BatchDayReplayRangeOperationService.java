package io.github.pinpols.batch.orchestrator.application.service.replay;

import io.github.pinpols.batch.common.enums.BatchLifecycleStatus;
import io.github.pinpols.batch.common.enums.ConfigLifecycleStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
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
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ADR-049 closed date-range operations over the existing single-day execution unit. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BatchDayReplayRangeOperationService {

  private static final String OPERATION_TYPE = "BATCH_DAY_RANGE";
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

  /** Creates a frozen preview and an operation identity. Candidate day details are stored without tokens. */
  @Transactional
  public ReplayOperationPreviewResponse preview(BatchDayReplayRangeCommand command) {
    validate(command, false);
    UUID operationKey = UUID.randomUUID();
    Instant now = dateTimeSupport.nowInstant();
    List<PreparedDay> preparedDays = prepareDays(command, now, false);
    List<ReplayOperationPreviewResponse.DayPreview> days = previewDays(preparedDays);
    int candidateCount = days.stream().mapToInt(ReplayOperationPreviewResponse.DayPreview::candidateCount).sum();
    String requestHash = requestHash(command);
    String snapshotPayload = JsonUtils.toJson(days);
    String snapshotHash = sha256(snapshotPayload);
    String rawToken = UUID.randomUUID().toString();
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
   * Consumes the operation preview and creates the parent plus every daily child in one transaction.
   * Repeating the same operation key and request returns the existing parent without creating children.
   */
  @Transactional
  public ReplayOperationEntity submit(BatchDayReplayRangeCommand command) {
    validate(command, true);
    String requestHash = requestHash(command);
    ReplayOperationEntity existing = operationMapper.selectByIdentity(
        command.tenantId(), command.operationKey().toString());
    if (existing != null) {
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
    if (token == null || !command.operationKey().toString().equals(token.operationKey())
        || !requestHash.equals(token.requestHash())) {
      throw invalidPreview();
    }
    if (token.consumedAt() != null) {
      ReplayOperationEntity consumedOperation = token.operationId() == null
          ? null
          : operationMapper.selectById(command.tenantId(), token.operationId());
      if (consumedOperation != null && requestHash.equals(consumedOperation.requestHash())) {
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
        .filter(day -> day.preview() != null && day.preview().totalCount() > 0)
        .toList();
    if (executableDays.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.no_candidates");
    }
    if (previewTokenMapper.consume(command.tenantId(), tokenHash, now) != 1) {
      throw invalidPreview();
    }

    String initialStatus = command.autoApprove()
        ? BatchLifecycleStatus.RUNNING.code()
        : ConfigLifecycleStatus.PENDING_APPROVAL.code();
    ReplayOperationEntity operation = ReplayOperationEntity.builder()
        .tenantId(command.tenantId())
        .operationKey(command.operationKey().toString())
        .operationType(OPERATION_TYPE)
        .status(initialStatus)
        .calendarCode(command.calendarCode())
        .fromDate(command.fromDate())
        .throughDate(command.throughDate())
        .scope(command.scope())
        .executionMode(firstNonBlank(command.executionMode(), "REPLAY"))
        .candidateSource(firstNonBlank(command.candidateSource(), "EXISTING_INSTANCES"))
        .scopePayload(JsonUtils.toJson(new ScopePayload(command.jobCodes(), command.versionIds())))
        .resultPolicy(command.resultPolicy())
        .configVersionPolicy(command.configVersionPolicy())
        .configVersion(command.configVersion())
        .requestHash(requestHash)
        .snapshotHash(snapshotHash)
        .snapshotPayload(snapshotPayload)
        .reason(command.reason())
        .requestedBy(command.requestedBy())
        .totalCount(executableDays.size())
        .succeededCount(0)
        .failedCount(0)
        .inFlightCount(executableDays.size())
        .startedAt(command.autoApprove() ? now : null)
        .traceId(command.traceId())
        .createdAt(now)
        .updatedAt(now)
        .build();
    operationMapper.insert(operation);
    ReplayOperationEntity persisted = operationMapper.selectByIdentity(
        command.tenantId(), command.operationKey().toString());
    if (persisted == null || persisted.id() == null) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day_replay.active_session_lost");
    }

    for (PreparedDay preparedDay : executableDays) {
      BatchDayReplaySubmitCommand childCommand = commandForDate(
          command, preparedDay.bizDate(), preparedDay.preview().previewToken());
      BatchDayReplaySessionEntity child = singleDayReplayService.submitForOperation(
          childCommand, persisted.id());
      if (child == null || child.id() == null
          || !persisted.id().equals(child.replayOperationId())) {
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
    ReplayOperationEntity operation = operationMapper.selectById(tenantId, operationId);
    if (operation == null || !OPERATION_TYPE.equals(operation.operationType())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.operation_not_found");
    }
    return operation;
  }

  @Transactional(readOnly = true)
  public List<ReplayOperationEntity> list(String tenantId, int limit) {
    if (!Texts.hasText(tenantId) || limit <= 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_argument");
    }
    return operationMapper.selectRecentByTenant(tenantId, Math.min(limit, 200));
  }

  @Transactional
  public ReplayOperationEntity approve(String tenantId, Long operationId, String approver) {
    ReplayOperationEntity operation = operationMapper.selectForUpdate(tenantId, operationId);
    if (operation == null || !OPERATION_TYPE.equals(operation.operationType())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.operation_not_found");
    }
    if (!ConfigLifecycleStatus.PENDING_APPROVAL.code().equals(operation.status())) {
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
    ReplayOperationEntity operation = operationMapper.selectForUpdate(tenantId, operationId);
    if (operation == null || !OPERATION_TYPE.equals(operation.operationType())) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.batch_day_replay.operation_not_found");
    }
    for (Long sessionId : operationMapper.selectChildSessionIds(tenantId, operationId)) {
      BatchDayReplaySessionEntity child = sessionMapper.selectForUpdate(tenantId, sessionId);
      if (child != null && (BatchLifecycleStatus.RUNNING.code().equals(child.status())
          || ConfigLifecycleStatus.PENDING_APPROVAL.code().equals(child.status()))) {
        singleDayReplayService.cancel(tenantId, sessionId);
      }
    }
    operationMapper.refreshFromChildSessions(tenantId, operationId, dateTimeSupport.nowInstant());
    return operationMapper.selectById(tenantId, operationId);
  }

  private List<PreparedDay> prepareDays(
      BatchDayReplayRangeCommand command, Instant now, boolean issueChildPreviewTokens) {
    long inclusiveSpan = ChronoUnit.DAYS.between(command.fromDate(), command.throughDate()) + 1;
    List<PreparedDay> days = new ArrayList<>((int) inclusiveSpan);
    for (LocalDate date = command.fromDate(); !date.isAfter(command.throughDate()); date = date.plusDays(1)) {
      boolean calendarBlocked = disasterDayOverrideMapper.selectActiveByCalendarBizDate(
              command.tenantId(), command.calendarCode(), date, now) != null
          || "HOLIDAY".equalsIgnoreCase(planCalendarMapper.selectEffectiveDayType(
              command.tenantId(), command.calendarCode(), date));
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
      if (day == null) {
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
    if (command == null || !Texts.hasText(command.tenantId()) || !Texts.hasText(command.calendarCode())
        || command.fromDate() == null || command.throughDate() == null
        || command.throughDate().isBefore(command.fromDate()) || !Texts.hasText(command.scope())
        || !Texts.hasText(command.reason()) || !Texts.hasText(command.requestedBy())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_argument");
    }
    long inclusiveSpan = ChronoUnit.DAYS.between(command.fromDate(), command.throughDate()) + 1;
    if (properties.getMaxRangeDays() <= 0 || inclusiveSpan > properties.getMaxRangeDays()) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.range_too_large");
    }
    if (submitting && (command.operationKey() == null || !Texts.hasText(command.previewToken()))) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.batch_day_replay.invalid_argument");
    }
  }

  private String requestHash(BatchDayReplayRangeCommand command) {
    return sha256(JsonUtils.toJson(new RequestFingerprint(
        command.tenantId(), command.calendarCode(), command.fromDate(), command.throughDate(),
        command.scope(), command.jobCodes(), command.versionIds(), command.resultPolicy(),
        command.configVersionPolicy(), command.configVersion(), command.reason(), command.requestedBy(),
        command.autoApprove(), command.traceId(), command.executionMode(), command.candidateSource())));
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
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
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
      String traceId,
      String executionMode,
      String candidateSource) {}
}
