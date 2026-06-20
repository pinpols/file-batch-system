package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.storage.ObjectNotFoundException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.FileStateMachine;
import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.file.S3GovernanceStorage.StorageObjectView;
import com.example.batch.orchestrator.infrastructure.redis.FileGovernanceMetricsCacheService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
/** 共享文件治理实现。具体的 @Scheduled 包装类委托到此处， 使每个任务可以独立演进，避免将无关的扫描耦合到同一个调度器 Bean 中。 */
public class FileGovernanceScheduler {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String STATUS_TIMEOUT = "TIMEOUT";
  private static final String SCHEDULER_NAME = "file-governance-scheduler";
  private static final String STATUS_TRIGGERED = "TRIGGERED";
  private static final String STATUS_WAITING_MANUAL_CONFIRM = "WAITING_MANUAL_CONFIRM";
  private static final String ACTOR_SYSTEM = "SYSTEM";

  private record ArrivalGroupUpdateState(String arrivalState, String reason, Instant now) {}

  private record ArrivalGroupUpdateFiles(
      List<Map<String, Object>> groupFiles, Set<String> requiredFiles, Set<String> missingFiles) {}

  private record ArrivalGroupUpdateContext(
      ArrivalGroupKey key, ArrivalGroupUpdateState state, ArrivalGroupUpdateFiles files) {}

  private final FileGovernanceRepository fileGovernanceRepository;
  private final S3GovernanceStorage s3GovernanceStorage;
  private final FileGovernanceProperties properties;
  private final FileGovernanceMetricsCacheService metricsCacheService;
  private final MeterRegistry meterRegistry;
  private final AtomicLong arrivalDelayViolations = new AtomicLong();
  private final AtomicLong arrivalDelayMaxSeconds = new AtomicLong();
  private final AtomicLong arrivalGroupWaitingCount = new AtomicLong();
  private final AtomicLong arrivalGroupTriggeredCount = new AtomicLong();
  private final AtomicLong arrivalGroupTimeoutCount = new AtomicLong();
  private final AtomicLong processingDelayViolations = new AtomicLong();
  private final AtomicLong processingDelayMaxSeconds = new AtomicLong();

  @PostConstruct
  void initializeMeters() {
    meterRegistry.gauge("batch.file.arrival.delay.violations", arrivalDelayViolations);
    meterRegistry.gauge("batch.file.arrival.delay.max.seconds", arrivalDelayMaxSeconds);
    meterRegistry.gauge("batch.file.arrival.group.waiting.count", arrivalGroupWaitingCount);
    meterRegistry.gauge("batch.file.arrival.group.triggered.count", arrivalGroupTriggeredCount);
    meterRegistry.gauge("batch.file.arrival.group.timeout.count", arrivalGroupTimeoutCount);
    meterRegistry.gauge("batch.pipeline.processing.delay.violations", processingDelayViolations);
    meterRegistry.gauge("batch.pipeline.processing.delay.max.seconds", processingDelayMaxSeconds);
  }

  /** 文件治理指标由中心定时收口，先保证延迟可见，再谈更复杂的告警策略。 */
  public void collectLatencyMetrics() {
    if (!properties.getLatency().isEnabled()) {
      return;
    }
    String tenantId = properties.getReconcile().getDefaultTenantId();
    sweepStaleRunningPipelines(tenantId);
    Map<String, Object> metrics =
        metricsCacheService.compute(
            tenantId,
            properties.getLatency().getArrivalDelayThresholdSeconds(),
            properties.getLatency().getProcessingDelayThresholdSeconds(),
            properties.getLatency().getProcessingDelayMaxAgeSeconds(),
            properties.getLatency().getSampleSize());
    long arrivalCount = asLong(metrics.get("arrivalDelayViolations"));
    long arrivalMax = asLong(metrics.get("maxArrivalDelaySeconds"));
    long processingCount = asLong(metrics.get("processingDelayViolations"));
    long processingMax = asLong(metrics.get("maxProcessingDelaySeconds"));
    arrivalDelayViolations.set(arrivalCount);
    arrivalDelayMaxSeconds.set(arrivalMax);
    processingDelayViolations.set(processingCount);
    processingDelayMaxSeconds.set(processingMax);
    metricsCacheService.write(tenantId, metrics);

    if (arrivalCount > 0) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> samples =
          (List<Map<String, Object>>) metrics.getOrDefault("arrivalDelaySamples", List.of());
      log.warn(
          "file arrival delay violations detected: count={}, maxDelaySeconds={}," + " samples={}",
          arrivalCount,
          arrivalMax,
          samples);
    }
    if (processingCount > 0) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> samples =
          (List<Map<String, Object>>) metrics.getOrDefault("processingDelaySamples", List.of());
      log.warn(
          "pipeline processing delay violations detected: count={}, maxDelaySeconds={},"
              + " samples={}",
          processingCount,
          processingMax,
          samples);
    }
  }

  private void sweepStaleRunningPipelines(String tenantId) {
    long staleSeconds = properties.getLatency().getStaleRunningFailSeconds();
    int limit = properties.getLatency().getStaleSweepBatchSize();
    if (staleSeconds <= 0 || limit <= 0) {
      return;
    }
    try {
      FileGovernanceRepository.StaleSweepResult result =
          fileGovernanceRepository.markStaleRunningPipelinesAndStepsFailed(
              tenantId, staleSeconds, limit);
      if (result.failedPipelines() <= 0) {
        return;
      }
      log.warn(
          "stale running pipeline sweep finalized records: tenantId={}, staleSeconds={},"
              + " failedPipelines={}, failedSteps={}",
          tenantId,
          staleSeconds,
          result.failedPipelines(),
          result.failedSteps());
    } catch (Exception exception) {
      log.warn(
          "stale running pipeline sweep failed: tenantId={}, staleSeconds={}, error={}",
          tenantId,
          staleSeconds,
          exception.getMessage(),
          exception);
    }
  }

  public Map<String, Object> loadLatencyMetrics(String tenantId) {
    return metricsCacheService.load(
        tenantId,
        properties.getLatency().getArrivalDelayThresholdSeconds(),
        properties.getLatency().getProcessingDelayThresholdSeconds(),
        properties.getLatency().getProcessingDelayMaxAgeSeconds(),
        properties.getLatency().getSampleSize());
  }

  private long asLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return 0L;
    }
    return Long.parseLong(String.valueOf(value));
  }

  public void manageFileArrivalGroups() {
    if (!properties.getArrival().isEnabled()) {
      return;
    }
    List<Map<String, Object>> candidates =
        fileGovernanceRepository.selectArrivalGovernanceCandidates(
            properties.getArrival().getBatchSize());
    Map<ArrivalGroupKey, List<Map<String, Object>>> grouped = new HashMap<>();
    for (Map<String, Object> candidate : candidates) {
      ArrivalGroupKey key = ArrivalGroupKey.from(candidate);
      if (key == null) {
        continue;
      }
      grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(candidate);
    }
    long waitingGroups = 0L;
    long triggeredGroups = 0L;
    long timeoutGroups = 0L;
    Instant now = BatchDateTimeSupport.utcNow();
    for (Map.Entry<ArrivalGroupKey, List<Map<String, Object>>> entry : grouped.entrySet()) {
      ArrivalGroupDecision decision = evaluateArrivalGroup(entry.getKey(), entry.getValue(), now);
      if (decision == null || decision.state() == null) {
        continue;
      }
      switch (decision.state()) {
        case "WAITING_ARRIVAL", "WAITING_FILE_GROUP", STATUS_WAITING_MANUAL_CONFIRM ->
            waitingGroups++;
        case STATUS_TRIGGERED -> triggeredGroups++;
        case STATUS_TIMEOUT -> timeoutGroups++;
        default -> {}
      }
    }
    arrivalGroupWaitingCount.set(waitingGroups);
    arrivalGroupTriggeredCount.set(triggeredGroups);
    arrivalGroupTimeoutCount.set(timeoutGroups);
  }

  public void cleanupArchivedFiles() {
    if (!properties.getArchive().isEnabled()) {
      return;
    }
    Instant cutoff =
        BatchDateTimeSupport.utcNow()
            .minus(properties.getArchive().getRetentionDays(), ChronoUnit.DAYS);
    List<Map<String, Object>> files =
        fileGovernanceRepository.selectArchivedFilesForCleanup(
            cutoff, properties.getArchive().getCleanupBatchSize());
    for (Map<String, Object> fileRecord : files) {
      cleanupArchivedFile(fileRecord);
    }
  }

  /**
   * 托管上传会话孤儿清理（#440）：{@code createUploadSession} 写入的占位 file_record（RECEIVED + APP_MANAGED +
   * WAITING_ARRIVAL），前端既不上传也不调 confirmFileArrival 时会永久滞留—— 到达组调度不处理（无 fileGroupCode）、归档清理不清（非
   * ARCHIVED）、对账不清（S3 对象不存在）。 超过 TTL 且对象存储确认无对象的占位行在此置为 DELETED 终态；对象已存在（用户上传了但没 confirm）则跳过并记日志。
   */
  public void cleanupOrphanUploadSessions() {
    if (!properties.getUploadSession().isCleanupEnabled()) {
      return;
    }
    List<Map<String, Object>> sessions =
        fileGovernanceRepository.selectOrphanUploadSessions(
            properties.getUploadSession().getOrphanTtlSeconds(),
            properties.getUploadSession().getCleanupBatchSize());
    if (sessions.isEmpty()) {
      return;
    }
    long cleaned = 0L;
    long skipped = 0L;
    for (Map<String, Object> session : sessions) {
      if (cleanupOrphanUploadSession(session)) {
        cleaned++;
      } else {
        skipped++;
      }
    }
    log.info(
        "orphan upload session cleanup finished: candidates={}, cleaned={}, skipped={},"
            + " ttlSeconds={}",
        sessions.size(),
        cleaned,
        skipped,
        properties.getUploadSession().getOrphanTtlSeconds());
  }

  public void reconcileObjectStorage() {
    if (!properties.getReconcile().isEnabled()) {
      return;
    }
    List<StorageObjectView> objects =
        s3GovernanceStorage.listObjects(
            properties.getReconcile().getPrefix(),
            properties.getReconcile().getBatchSize(),
            properties.getReconcile().isIncludeTemporaryObjects());
    for (StorageObjectView object : objects) {
      reconcileObject(object);
    }
  }

  private void cleanupArchivedFile(Map<String, Object> fileRecord) {
    Long fileId = toLong(fileRecord.get("id"));
    String tenantId = text(fileRecord.get("tenant_id"));
    String storagePath = text(fileRecord.get("storage_path"));
    String storageType = text(fileRecord.get("storage_type"));
    try {
      if (fileId == null || tenantId == null) {
        return;
      }
      if (fileGovernanceRepository.countActivePipelineInstances(tenantId, fileId) > 0
          || fileGovernanceRepository.countPendingDispatchRecords(tenantId, fileId) > 0) {
        return;
      }
      if ("S3".equalsIgnoreCase(storageType) || "OSS".equalsIgnoreCase(storageType)) {
        s3GovernanceStorage.removeObject(storagePath);
      }
      Map<String, Object> cleanupMetadata = new LinkedHashMap<>();
      cleanupMetadata.put("cleanupAt", BatchDateTimeSupport.utcNow().toString());
      cleanupMetadata.put("cleanupReason", "ARCHIVE_RETENTION_EXPIRED");
      // 调用方:archived → deleted 是合法迁移。Repository 仅作纯 DAO 写入,
      // 此处不再依赖 Repository 内部抛 BizException,失败由调用方静默吞 (容错型清理)
      String currentStatus = text(fileRecord.get("file_status"));
      FileStateMachine.assertTransition(currentStatus, "DELETED");
      fileGovernanceRepository.updateFileStatus(
          tenantId, fileId, currentStatus, "DELETED", cleanupMetadata);
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storagePath", storagePath);
      auditDetail.put("storageType", storageType);
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "CLEANUP",
              "SUCCESS",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "cleanup-" + fileId,
              auditDetail));
    } catch (Exception exception) {
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storagePath", storagePath);
      auditDetail.put("errorMessage", exception.getMessage());
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "CLEANUP",
              "FAILED",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "cleanup-" + fileId,
              auditDetail));
      log.warn(
          "archived file cleanup failed: fileId={}, error={}",
          fileId,
          exception.getMessage(),
          exception);
    }
  }

  /**
   * 单条孤儿会话清理。
   *
   * @return true = 已清理；false = 跳过（对象已上传未确认 / 并发状态变化 / 存储侧不确定 / 异常）
   */
  private boolean cleanupOrphanUploadSession(Map<String, Object> fileRecord) {
    Long fileId = toLong(fileRecord.get("id"));
    String tenantId = text(fileRecord.get("tenant_id"));
    String storageBucket = text(fileRecord.get("storage_bucket"));
    String storagePath = text(fileRecord.get("storage_path"));
    if (fileId == null || tenantId == null || storagePath == null) {
      return false;
    }
    try {
      // 清理前用对象存储 statSize 确认对象确实不存在:对象已存在 = 用户上传了但没 confirm,
      // 不能删,跳过并记日志,留给人工 confirm 或后续治理。
      try {
        long sizeBytes = s3GovernanceStorage.objectSize(storageBucket, storagePath);
        log.info(
            "skip orphan upload session, object uploaded but never confirmed: tenantId={},"
                + " fileId={}, storagePath={}, sizeBytes={}",
            tenantId,
            fileId,
            storagePath,
            sizeBytes);
        return false;
      } catch (ObjectNotFoundException notFound) {
        // 期望分支:对象确实不存在 → 确认是孤儿占位行,继续清理
      }
      Map<String, Object> cleanupMetadata = new LinkedHashMap<>();
      cleanupMetadata.put("cleanupAt", BatchDateTimeSupport.utcNow().toString());
      cleanupMetadata.put("cleanupReason", "UPLOAD_SESSION_ORPHAN_EXPIRED");
      // file_audit_log.file_id 外键禁止物理删除 file_record;状态机不允许 RECEIVED → DELETED 直跳,
      // 沿用归档清理的软删路径,经 ARCHIVED 中转两步置 DELETED 终态。首跳 CAS 在 currentStatus 上,
      // 并发期间状态已变(如恰好开始 PARSING)则放弃本轮,留给下一轮重新判定。
      FileStateMachine.assertTransition("RECEIVED", "ARCHIVED");
      int archived =
          fileGovernanceRepository.updateFileStatus(
              tenantId, fileId, "RECEIVED", "ARCHIVED", cleanupMetadata);
      if (archived <= 0) {
        return false;
      }
      FileStateMachine.assertTransition("ARCHIVED", "DELETED");
      fileGovernanceRepository.updateFileStatus(tenantId, fileId, "ARCHIVED", "DELETED", null);
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storageBucket", storageBucket);
      auditDetail.put("storagePath", storagePath);
      auditDetail.put("cleanupReason", "UPLOAD_SESSION_ORPHAN_EXPIRED");
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "CLEANUP",
              "SUCCESS",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "orphan-upload-" + fileId,
              auditDetail));
      return true;
    } catch (Exception exception) {
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storagePath", storagePath);
      auditDetail.put("errorMessage", exception.getMessage());
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "CLEANUP",
              "FAILED",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "orphan-upload-" + fileId,
              auditDetail));
      log.warn(
          "orphan upload session cleanup failed: tenantId={}, fileId={}, error={}",
          tenantId,
          fileId,
          exception.getMessage(),
          exception);
      return false;
    }
  }

  private void reconcileObject(StorageObjectView object) {
    if (object == null || object.objectName() == null || object.objectName().endsWith(".done")) {
      return;
    }
    String tenantId = properties.getReconcile().getDefaultTenantId();
    if (fileGovernanceRepository.existsFileRecordByStoragePath(
        tenantId, object.bucket(), object.objectName())) {
      return;
    }
    String fileName =
        object.objectName().contains("/")
            ? object.objectName().substring(object.objectName().lastIndexOf('/') + 1)
            : object.objectName();
    String fileCategory = resolveFileCategory(object.objectName());
    String fileStatus = resolveFileStatus(fileCategory);
    String traceId = "reconcile-" + sanitizeTrace(fileName);
    Long fileId =
        fileGovernanceRepository.createReconciledFileRecord(
            new FileGovernanceRepository.ReconciledFileRecordCommand(
                new FileGovernanceRepository.FileIdentity(
                    tenantId, fileCategory, fileName, resolveFileFormatType(fileName)),
                object.size(),
                new FileGovernanceRepository.FileStorage(
                    "S3", object.objectName(), object.bucket()),
                ACTOR_SYSTEM,
                fileStatus,
                traceId,
                buildReconcileMetadata(object)));
    fileGovernanceRepository.appendAudit(
        new FileGovernanceRepository.FileAuditCommand(
            tenantId,
            fileId,
            "RECONCILE_REGISTER",
            "SUCCESS",
            new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
            traceId,
            Map.of("bucket", object.bucket(), "storagePath", object.objectName())));
  }

  private ArrivalGroupDecision evaluateArrivalGroup(
      ArrivalGroupKey key, List<Map<String, Object>> groupFiles, Instant now) {
    if (groupFiles == null || groupFiles.isEmpty()) {
      return new ArrivalGroupDecision(null);
    }
    Set<String> requiredFiles =
        parseRequiredFileSet(text(groupFiles.get(0).get("required_file_set")));
    Set<String> arrivedFiles = new HashSet<>();
    for (Map<String, Object> file : groupFiles) {
      String fileName = text(file.get("file_name"));
      if (fileName != null) {
        arrivedFiles.add(fileName);
      }
    }
    Set<String> missingFiles = new HashSet<>(requiredFiles);
    missingFiles.removeAll(arrivedFiles);
    Instant latestTolerableTime =
        parseInstant(text(groupFiles.get(0).get("latest_tolerable_time")));
    boolean triggerOnComplete =
        parseBoolean(
            text(groupFiles.get(0).get("trigger_on_complete")),
            properties.getArrival().isTriggerOnComplete());
    String timeoutAction =
        defaultText(
            text(groupFiles.get(0).get("arrival_timeout_action")),
            properties.getArrival().getDefaultTimeoutAction());
    boolean timedOut = latestTolerableTime != null && now.isAfter(latestTolerableTime);
    if (timedOut) {
      if ("MANUAL_CONFIRM".equalsIgnoreCase(timeoutAction)) {
        updateGroupState(
            new ArrivalGroupUpdateContext(
                key,
                new ArrivalGroupUpdateState(
                    STATUS_WAITING_MANUAL_CONFIRM, "TIMEOUT_WAITING_MANUAL_CONFIRM", now),
                new ArrivalGroupUpdateFiles(groupFiles, requiredFiles, missingFiles)));
        return new ArrivalGroupDecision(STATUS_WAITING_MANUAL_CONFIRM);
      }
      if ("BLOCK_DOWNSTREAM".equalsIgnoreCase(timeoutAction)
          || "BLOCK".equalsIgnoreCase(timeoutAction)) {
        updateGroupState(
            new ArrivalGroupUpdateContext(
                key,
                new ArrivalGroupUpdateState(STATUS_TIMEOUT, "LATEST_TOLERABLE_TIME_EXCEEDED", now),
                new ArrivalGroupUpdateFiles(groupFiles, requiredFiles, missingFiles)));
        return new ArrivalGroupDecision(STATUS_TIMEOUT);
      }
      updateGroupState(
          new ArrivalGroupUpdateContext(
              key,
              new ArrivalGroupUpdateState(
                  STATUS_TRIGGERED, "TIMEOUT_OVERRIDE_" + timeoutAction, now),
              new ArrivalGroupUpdateFiles(groupFiles, requiredFiles, missingFiles)));
      return new ArrivalGroupDecision(STATUS_TRIGGERED);
    }
    if (!requiredFiles.isEmpty() && missingFiles.isEmpty()) {
      if (properties.getArrival().isRequireVerified() && !allMembersVerified(groupFiles)) {
        // 文件名虽齐,但有成员缺完整性背书(checksum_type=NONE)→ 保持等待,不放行;超时已在上方走 timeoutAction
        updateGroupState(
            new ArrivalGroupUpdateContext(
                key,
                new ArrivalGroupUpdateState("WAITING_ARRIVAL", "ARRIVED_PENDING_VERIFY", now),
                new ArrivalGroupUpdateFiles(groupFiles, requiredFiles, missingFiles)));
        return new ArrivalGroupDecision("WAITING_ARRIVAL");
      }
      if (triggerOnComplete) {
        updateGroupState(
            new ArrivalGroupUpdateContext(
                key,
                new ArrivalGroupUpdateState(STATUS_TRIGGERED, "ALL_FILES_ARRIVED", now),
                new ArrivalGroupUpdateFiles(groupFiles, requiredFiles, missingFiles)));
        return new ArrivalGroupDecision(STATUS_TRIGGERED);
      }
      updateGroupState(
          new ArrivalGroupUpdateContext(
              key,
              new ArrivalGroupUpdateState(
                  STATUS_WAITING_MANUAL_CONFIRM, "COMPLETE_WAITING_MANUAL_CONFIRM", now),
              new ArrivalGroupUpdateFiles(groupFiles, requiredFiles, missingFiles)));
      return new ArrivalGroupDecision(STATUS_WAITING_MANUAL_CONFIRM);
    }
    // 配置不全守卫:有 fileGroupCode 但 requiredFileSet 为空 → 既不能判定"完成"也不能判定"等"。
    // 此时若仍写 WAITING_ARRIVAL,scheduler 会在每个 30s tick 重复评估同一行,造成日志 +
    // updated_at 抖动(2026-05-01 5207.fw 类噪声根因)。改为静默跳过,等业务方补全 metadata 或
    // 数据治理手工置终态。
    if (requiredFiles.isEmpty()) {
      log.warn(
          "skip arrival group with empty requiredFileSet: tenantId={}, fileGroupCode={},"
              + " arrivedCount={} — metadata 不全,无法判定触发条件;请补 requiredFileSet 或置终态",
          key.tenantId(),
          key.fileGroupCode(),
          arrivedFiles.size());
      return new ArrivalGroupDecision(null);
    }
    updateGroupState(
        new ArrivalGroupUpdateContext(
            key,
            new ArrivalGroupUpdateState("WAITING_ARRIVAL", "WAITING_REQUIRED_FILES", now),
            new ArrivalGroupUpdateFiles(groupFiles, requiredFiles, missingFiles)));
    return new ArrivalGroupDecision("WAITING_ARRIVAL");
  }

  private void updateGroupState(ArrivalGroupUpdateContext context) {
    // 幂等跳过:group 内所有文件 arrivalState + arrivalReason 已与目标一致,跳过 update / audit / log
    // (避免 schedule tick 在稳态下反复写 updated_at 和重复 INFO 日志,2026-05-01 噪声治理)。
    String targetState = context.state().arrivalState();
    String targetReason = context.state().reason();
    boolean allInSync = true;
    for (Map<String, Object> file : context.files().groupFiles()) {
      String currentState = text(file.get("arrival_state"));
      String currentReason = text(file.get("arrival_reason"));
      if (!targetState.equals(currentState) || !Objects.equals(targetReason, currentReason)) {
        allInSync = false;
        break;
      }
    }
    if (allInSync) {
      return;
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("fileGroupCode", context.key().fileGroupCode());
    metadata.put("waitFileGroupMode", context.key().waitFileGroupMode());
    metadata.put("requiredFileSet", context.key().requiredFileSet());
    metadata.put("arrivalTimeoutAction", context.key().arrivalTimeoutAction());
    metadata.put("arrivalState", targetState);
    metadata.put("arrivalReason", targetReason);
    metadata.put("arrivalCheckedAt", context.state().now().toString());
    metadata.put("groupArrivedCount", context.files().groupFiles().size());
    metadata.put("groupRequiredCount", context.files().requiredFiles().size());
    metadata.put("groupMissingFileSet", String.join(",", context.files().missingFiles()));
    if (STATUS_TRIGGERED.equals(targetState)) {
      metadata.put("arrivalTriggeredAt", context.state().now().toString());
    }
    if (STATUS_TIMEOUT.equals(targetState)) {
      metadata.put("arrivalTimedOutAt", context.state().now().toString());
    }
    for (Map<String, Object> file : context.files().groupFiles()) {
      Long fileId = toLong(file.get("id"));
      String tenantId = text(file.get("tenant_id"));
      if (fileId == null || tenantId == null) {
        continue;
      }
      fileGovernanceRepository.updateFileMetadata(tenantId, fileId, metadata);
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              tenantId,
              fileId,
              "ARRIVAL_GROUP_" + context.state().arrivalState(),
              "SUCCESS",
              new FileGovernanceRepository.FileAuditActor(ACTOR_SYSTEM, SCHEDULER_NAME),
              "arrival-group-" + context.key().fileGroupCode(),
              metadata));
    }
    log.info(
        "file arrival group updated: tenantId={}, fileGroupCode={}, state={}, reason={},"
            + " arrivedCount={}, requiredCount={}, missingCount={}",
        context.key().tenantId(),
        context.key().fileGroupCode(),
        context.state().arrivalState(),
        context.state().reason(),
        context.files().groupFiles().size(),
        context.files().requiredFiles().size(),
        context.files().missingFiles().size());
  }

  private Object buildReconcileMetadata(StorageObjectView object) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("reconciled", true);
    metadata.put("etag", object.etag());
    metadata.put("lastModified", object.lastModified());
    return metadata;
  }

  private String resolveFileCategory(String objectName) {
    if (objectName.startsWith("archive/")) {
      return "ARCHIVE";
    }
    if (objectName.startsWith("outbound/")) {
      return "OUTPUT";
    }
    return "INPUT";
  }

  private String resolveFileStatus(String fileCategory) {
    return switch (fileCategory) {
      case "ARCHIVE" -> "ARCHIVED";
      case "OUTPUT" -> "GENERATED";
      default -> "RECEIVED";
    };
  }

  private String resolveFileFormatType(String fileName) {
    String lowerName = fileName == null ? "" : fileName.toLowerCase();
    if (lowerName.endsWith(".csv")) {
      return "DELIMITED";
    }
    if (lowerName.endsWith(".xlsx") || lowerName.endsWith(".xls")) {
      return "EXCEL";
    }
    if (lowerName.endsWith(".xml")) {
      return "XML";
    }
    if (lowerName.endsWith(".json")) {
      return "JSON";
    }
    return "BINARY";
  }

  private String sanitizeTrace(String fileName) {
    return fileName == null ? "object" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private Long toLong(Object value) {
    if (value instanceof Number number) {
      return number.longValue();
    }
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return text.isBlank() ? null : Long.valueOf(text);
  }

  private String text(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private Instant parseInstant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (Exception exception) {
      SwallowedExceptionLogger.warn(FileGovernanceScheduler.class, "catch:Exception", exception);

      return null;
    }
  }

  /** 组内每个成员都有完整性背书(checksum_type 非空且非 NONE,即入站 MANIFEST 注入了 checksum)。 */
  private boolean allMembersVerified(List<Map<String, Object>> groupFiles) {
    for (Map<String, Object> file : groupFiles) {
      String checksumType = text(file.get("checksum_type"));
      if (checksumType == null || checksumType.isBlank() || "NONE".equalsIgnoreCase(checksumType)) {
        return false;
      }
    }
    return true;
  }

  private Set<String> parseRequiredFileSet(String value) {
    Set<String> files = new HashSet<>();
    if (value == null || value.isBlank()) {
      return files;
    }
    for (String item : value.split(",")) {
      String trimmed = item.trim();
      if (!trimmed.isBlank()) {
        files.add(trimmed);
      }
    }
    return files;
  }

  private boolean parseBoolean(String value, boolean fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Boolean.parseBoolean(value);
  }

  private String defaultText(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private record ArrivalGroupKey(
      String tenantId,
      String fileGroupCode,
      String waitFileGroupMode,
      String requiredFileSet,
      String arrivalTimeoutAction) {

    static ArrivalGroupKey from(Map<String, Object> candidate) {
      if (candidate == null) {
        return null;
      }
      String tenantId = textValue(candidate.get("tenant_id"));
      String fileGroupCode = textValue(candidate.get("file_group_code"));
      if (tenantId == null
          || tenantId.isBlank()
          || fileGroupCode == null
          || fileGroupCode.isBlank()) {
        return null;
      }
      return new ArrivalGroupKey(
          tenantId,
          fileGroupCode,
          defaultString(textValue(candidate.get("wait_file_group_mode")), "ALL_OF"),
          defaultString(textValue(candidate.get("required_file_set")), ""),
          defaultString(textValue(candidate.get("arrival_timeout_action")), "MANUAL_CONFIRM"));
    }

    private static String textValue(Object value) {
      return value == null ? null : String.valueOf(value);
    }

    private static String defaultString(String value, String fallback) {
      return value == null || value.isBlank() ? fallback : value;
    }

    // 显式覆写与 record 默认生成逻辑一致，仅为绕过 Alibaba 插件对 record 的识别盲区。
    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (!(obj instanceof ArrivalGroupKey that)) {
        return false;
      }
      return Objects.equals(tenantId, that.tenantId)
          && Objects.equals(fileGroupCode, that.fileGroupCode)
          && Objects.equals(waitFileGroupMode, that.waitFileGroupMode)
          && Objects.equals(requiredFileSet, that.requiredFileSet)
          && Objects.equals(arrivalTimeoutAction, that.arrivalTimeoutAction);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          tenantId, fileGroupCode, waitFileGroupMode, requiredFileSet, arrivalTimeoutAction);
    }
  }

  private record ArrivalGroupDecision(String state) {}
}
