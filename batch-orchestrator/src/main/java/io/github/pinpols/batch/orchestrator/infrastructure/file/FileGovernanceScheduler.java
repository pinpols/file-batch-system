package io.github.pinpols.batch.orchestrator.infrastructure.file;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.FileGovernanceProperties;
import io.github.pinpols.batch.orchestrator.infrastructure.redis.FileGovernanceMetricsCacheService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
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
  private final FileGovernanceProperties properties;
  private final FileGovernanceMetricsCacheService metricsCacheService;
  private final MeterRegistry meterRegistry;

  /** ADR-046:到达组满足条件时,若该组是文件束则发起 BUNDLE_* launch。 */
  private final BundleArrivalLauncher bundleArrivalLauncher;

  private final FileGovernanceStorageMaintenance storageMaintenance;

  private final AtomicLong arrivalDelayViolations = new AtomicLong();
  private final AtomicLong arrivalDelayMaxSeconds = new AtomicLong();
  private final AtomicLong arrivalGroupWaitingCount = new AtomicLong();
  private final AtomicLong arrivalGroupTriggeredCount = new AtomicLong();
  private final AtomicLong arrivalGroupTimeoutCount = new AtomicLong();
  private final AtomicLong processingDelayViolations = new AtomicLong();
  private final AtomicLong processingDelayMaxSeconds = new AtomicLong();

  public FileGovernanceScheduler(
      FileGovernanceRepository fileGovernanceRepository,
      S3GovernanceStorage s3GovernanceStorage,
      FileGovernanceProperties properties,
      FileGovernanceMetricsCacheService metricsCacheService,
      MeterRegistry meterRegistry,
      BundleArrivalLauncher bundleArrivalLauncher) {
    this.fileGovernanceRepository = fileGovernanceRepository;
    this.properties = properties;
    this.metricsCacheService = metricsCacheService;
    this.meterRegistry = meterRegistry;
    this.bundleArrivalLauncher = bundleArrivalLauncher;
    this.storageMaintenance =
        new FileGovernanceStorageMaintenance(
            fileGovernanceRepository, s3GovernanceStorage, properties);
  }

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

  /** 文件治理指标由中心定时收敛，先保证延迟可见，再谈更复杂的告警策略。 */
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
    storageMaintenance.cleanupArchivedFiles();
  }

  /**
   * 托管上传会话孤儿清理（#440）：{@code createUploadSession} 写入的占位 file_record（RECEIVED + APP_MANAGED +
   * WAITING_ARRIVAL），前端既不上传也不调 confirmFileArrival 时会永久滞留—— 到达组调度不处理（无 fileGroupCode）、归档清理不清（非
   * ARCHIVED）、对账不清（S3 对象不存在）。 超过 TTL 且对象存储确认无对象的占位行在此置为 DELETED 终态；对象已存在（用户上传了但没 confirm）则跳过并记日志。
   */
  public void cleanupOrphanUploadSessions() {
    storageMaintenance.cleanupOrphanUploadSessions();
  }

  public void reconcileObjectStorage() {
    storageMaintenance.reconcileObjectStorage();
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
      return triggerArrivalGroup(
          key, groupFiles, requiredFiles, missingFiles, "TIMEOUT_OVERRIDE_" + timeoutAction, now);
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
        return triggerArrivalGroup(
            key, groupFiles, requiredFiles, missingFiles, "ALL_FILES_ARRIVED", now);
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

  private ArrivalGroupDecision triggerArrivalGroup(
      ArrivalGroupKey key,
      List<Map<String, Object>> groupFiles,
      Set<String> requiredFiles,
      Set<String> missingFiles,
      String reason,
      Instant now) {
    try {
      bundleArrivalLauncher.launchIfBundle(key.tenantId(), key.fileGroupCode(), groupFiles);
    } catch (RuntimeException exception) {
      // P1-2:束 launch 失败 → 保持组 retryable(不丢触发)。但永久畸形组(混 jobCode/bizDate)会每轮
      // sweep 重试,只 ERROR 日志运维不可见 → 加 counter 供告警(瞬时故障可恢复,永久故障靠该 metric
      // 触达人工修数据;不引终态以免误丢「迟到文件可修复」的组)。
      meterRegistry.counter("batch.file.arrival.bundle.launch.failed").increment();
      log.error(
          "file arrival group bundle launch failed, keep group retryable: tenantId={},"
              + " fileGroupCode={}, reason={}",
          key.tenantId(),
          key.fileGroupCode(),
          reason,
          exception);
      return new ArrivalGroupDecision("WAITING_ARRIVAL");
    }
    updateGroupState(
        new ArrivalGroupUpdateContext(
            key,
            new ArrivalGroupUpdateState(STATUS_TRIGGERED, reason, now),
            new ArrivalGroupUpdateFiles(groupFiles, requiredFiles, missingFiles)));
    return new ArrivalGroupDecision(STATUS_TRIGGERED);
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
      String bizDate,
      String fileGroupCode,
      String waitFileGroupMode,
      String requiredFileSet,
      String arrivalTimeoutAction) {

    private static final String MISSING_BIZ_DATE = "__MISSING_BIZ_DATE__";

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
          defaultString(textValue(candidate.get("biz_date")), MISSING_BIZ_DATE),
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
          && Objects.equals(bizDate, that.bizDate)
          && Objects.equals(fileGroupCode, that.fileGroupCode)
          && Objects.equals(waitFileGroupMode, that.waitFileGroupMode)
          && Objects.equals(requiredFileSet, that.requiredFileSet)
          && Objects.equals(arrivalTimeoutAction, that.arrivalTimeoutAction);
    }

    @Override
    public int hashCode() {
      return Objects.hash(
          tenantId,
          bizDate,
          fileGroupCode,
          waitFileGroupMode,
          requiredFileSet,
          arrivalTimeoutAction);
    }
  }

  private record ArrivalGroupDecision(String state) {}
}
