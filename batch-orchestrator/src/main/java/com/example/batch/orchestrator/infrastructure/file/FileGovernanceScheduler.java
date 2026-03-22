package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.file.MinioGovernanceStorage.StorageObjectView;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileGovernanceScheduler {

    private final FileGovernanceRepository fileGovernanceRepository;
    private final MinioGovernanceStorage minioGovernanceStorage;
    private final FileGovernanceProperties properties;
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

    /**
     * 文件治理指标由中心定时收口，先保证延迟可见，再谈更复杂的告警策略。
     */
    @Scheduled(fixedDelayString = "${batch.file-governance.latency.poll-interval-millis:30000}")
    public void collectLatencyMetrics() {
        if (!properties.getLatency().isEnabled()) {
            return;
        }
        long arrivalCount = fileGovernanceRepository.countArrivalDelayViolations(
                properties.getLatency().getArrivalDelayThresholdSeconds()
        );
        long arrivalMax = fileGovernanceRepository.maxArrivalDelaySeconds();
        long processingCount = fileGovernanceRepository.countProcessingDelayViolations(
                properties.getLatency().getProcessingDelayThresholdSeconds()
        );
        long processingMax = fileGovernanceRepository.maxProcessingDelaySeconds();
        arrivalDelayViolations.set(arrivalCount);
        arrivalDelayMaxSeconds.set(arrivalMax);
        processingDelayViolations.set(processingCount);
        processingDelayMaxSeconds.set(processingMax);

        if (arrivalCount > 0) {
            List<Map<String, Object>> samples = fileGovernanceRepository.selectArrivalDelaySamples(
                    properties.getLatency().getArrivalDelayThresholdSeconds(),
                    properties.getLatency().getSampleSize()
            );
            log.warn("file arrival delay violations detected: count={}, maxDelaySeconds={}, samples={}",
                    arrivalCount, arrivalMax, samples);
        }
        if (processingCount > 0) {
            List<Map<String, Object>> samples = fileGovernanceRepository.selectProcessingDelaySamples(
                    properties.getLatency().getProcessingDelayThresholdSeconds(),
                    properties.getLatency().getSampleSize()
            );
            log.warn("pipeline processing delay violations detected: count={}, maxDelaySeconds={}, samples={}",
                    processingCount, processingMax, samples);
        }
    }

    @Scheduled(fixedDelayString = "${batch.file-governance.arrival.poll-interval-millis:30000}")
    public void manageFileArrivalGroups() {
        if (!properties.getArrival().isEnabled()) {
            return;
        }
        List<Map<String, Object>> candidates = fileGovernanceRepository.selectArrivalGovernanceCandidates(
                properties.getArrival().getBatchSize()
        );
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
        Instant now = Instant.now();
        for (Map.Entry<ArrivalGroupKey, List<Map<String, Object>>> entry : grouped.entrySet()) {
            ArrivalGroupDecision decision = evaluateArrivalGroup(entry.getKey(), entry.getValue(), now);
            if (decision == null || decision.state() == null) {
                continue;
            }
            switch (decision.state()) {
                case "WAITING_ARRIVAL", "WAITING_FILE_GROUP", "WAITING_MANUAL_CONFIRM" -> waitingGroups++;
                case "TRIGGERED" -> triggeredGroups++;
                case "TIMEOUT" -> timeoutGroups++;
                default -> {
                }
            }
        }
        arrivalGroupWaitingCount.set(waitingGroups);
        arrivalGroupTriggeredCount.set(triggeredGroups);
        arrivalGroupTimeoutCount.set(timeoutGroups);
    }

    @Scheduled(fixedDelayString = "${batch.file-governance.archive.cleanup-interval-millis:60000}")
    public void cleanupArchivedFiles() {
        if (!properties.getArchive().isEnabled()) {
            return;
        }
        Instant cutoff = Instant.now().minus(properties.getArchive().getRetentionDays(), ChronoUnit.DAYS);
        List<Map<String, Object>> files = fileGovernanceRepository.selectArchivedFilesForCleanup(
                cutoff,
                properties.getArchive().getCleanupBatchSize()
        );
        for (Map<String, Object> fileRecord : files) {
            cleanupArchivedFile(fileRecord);
        }
    }

    @Scheduled(fixedDelayString = "${batch.file-governance.reconcile.poll-interval-millis:60000}")
    public void reconcileObjectStorage() {
        if (!properties.getReconcile().isEnabled()) {
            return;
        }
        List<StorageObjectView> objects = minioGovernanceStorage.listObjects(
                properties.getReconcile().getPrefix(),
                properties.getReconcile().getBatchSize(),
                properties.getReconcile().isIncludeTemporaryObjects()
        );
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
                minioGovernanceStorage.removeObject(storagePath);
            }
            Map<String, Object> cleanupMetadata = new LinkedHashMap<>();
            cleanupMetadata.put("cleanupAt", Instant.now().toString());
            cleanupMetadata.put("cleanupReason", "ARCHIVE_RETENTION_EXPIRED");
            fileGovernanceRepository.updateFileStatus(
                    tenantId,
                    fileId,
                    "DELETED",
                    cleanupMetadata
            );
            Map<String, Object> auditDetail = new LinkedHashMap<>();
            auditDetail.put("storagePath", storagePath);
            auditDetail.put("storageType", storageType);
            fileGovernanceRepository.appendAudit(
                    tenantId,
                    fileId,
                    "CLEANUP",
                    "SUCCESS",
                    "SYSTEM",
                    "file-governance-scheduler",
                    "cleanup-" + fileId,
                    auditDetail
            );
        } catch (Exception exception) {
            Map<String, Object> auditDetail = new LinkedHashMap<>();
            auditDetail.put("storagePath", storagePath);
            auditDetail.put("errorMessage", exception.getMessage());
            fileGovernanceRepository.appendAudit(
                    tenantId,
                    fileId,
                    "CLEANUP",
                    "FAILED",
                    "SYSTEM",
                    "file-governance-scheduler",
                    "cleanup-" + fileId,
                    auditDetail
            );
            log.warn("archived file cleanup failed: fileId={}, error={}", fileId, exception.getMessage(), exception);
        }
    }

    private void reconcileObject(StorageObjectView object) {
        if (object == null || object.objectName() == null || object.objectName().endsWith(".done")) {
            return;
        }
        String tenantId = properties.getReconcile().getDefaultTenantId();
        if (fileGovernanceRepository.existsFileRecordByStoragePath(tenantId, object.bucket(), object.objectName())) {
            return;
        }
        String fileName = object.objectName().contains("/")
                ? object.objectName().substring(object.objectName().lastIndexOf('/') + 1)
                : object.objectName();
        String fileCategory = resolveFileCategory(object.objectName());
        String fileStatus = resolveFileStatus(fileCategory);
        Long fileId = fileGovernanceRepository.createReconciledFileRecord(
                tenantId,
                fileCategory,
                fileName,
                resolveFileFormatType(fileName),
                object.size(),
                "S3",
                object.objectName(),
                object.bucket(),
                "SYSTEM",
                fileStatus,
                "reconcile-" + sanitizeTrace(fileName),
                buildReconcileMetadata(object)
        );
        fileGovernanceRepository.appendAudit(
                tenantId,
                fileId,
                "RECONCILE_REGISTER",
                "SUCCESS",
                "SYSTEM",
                "file-governance-scheduler",
                "reconcile-" + sanitizeTrace(fileName),
                Map.of("bucket", object.bucket(), "storagePath", object.objectName())
        );
    }

    private ArrivalGroupDecision evaluateArrivalGroup(ArrivalGroupKey key, List<Map<String, Object>> groupFiles, Instant now) {
        if (groupFiles == null || groupFiles.isEmpty()) {
            return new ArrivalGroupDecision(null);
        }
        Set<String> requiredFiles = parseRequiredFileSet(text(groupFiles.get(0).get("required_file_set")));
        Set<String> arrivedFiles = new HashSet<>();
        for (Map<String, Object> file : groupFiles) {
            String fileName = text(file.get("file_name"));
            if (fileName != null) {
                arrivedFiles.add(fileName);
            }
        }
        Set<String> missingFiles = new HashSet<>(requiredFiles);
        missingFiles.removeAll(arrivedFiles);
        Instant latestTolerableTime = parseInstant(text(groupFiles.get(0).get("latest_tolerable_time")));
        boolean triggerOnComplete = parseBoolean(text(groupFiles.get(0).get("trigger_on_complete")), properties.getArrival().isTriggerOnComplete());
        String timeoutAction = defaultText(text(groupFiles.get(0).get("arrival_timeout_action")), properties.getArrival().getDefaultTimeoutAction());
        boolean timedOut = latestTolerableTime != null && now.isAfter(latestTolerableTime);
        if (timedOut) {
            if ("MANUAL_CONFIRM".equalsIgnoreCase(timeoutAction)) {
                updateGroupState(groupFiles, key, "WAITING_MANUAL_CONFIRM", "TIMEOUT_WAITING_MANUAL_CONFIRM", now, requiredFiles, missingFiles);
                return new ArrivalGroupDecision("WAITING_MANUAL_CONFIRM");
            }
            if ("BLOCK_DOWNSTREAM".equalsIgnoreCase(timeoutAction) || "BLOCK".equalsIgnoreCase(timeoutAction)) {
                updateGroupState(groupFiles, key, "TIMEOUT", "LATEST_TOLERABLE_TIME_EXCEEDED", now, requiredFiles, missingFiles);
                return new ArrivalGroupDecision("TIMEOUT");
            }
            updateGroupState(groupFiles, key, "TRIGGERED", "TIMEOUT_OVERRIDE_" + timeoutAction, now, requiredFiles, missingFiles);
            return new ArrivalGroupDecision("TRIGGERED");
        }
        if (!requiredFiles.isEmpty() && missingFiles.isEmpty()) {
            if (triggerOnComplete) {
                updateGroupState(groupFiles, key, "TRIGGERED", "ALL_FILES_ARRIVED", now, requiredFiles, missingFiles);
                return new ArrivalGroupDecision("TRIGGERED");
            }
            updateGroupState(groupFiles, key, "WAITING_MANUAL_CONFIRM", "COMPLETE_WAITING_MANUAL_CONFIRM", now, requiredFiles, missingFiles);
            return new ArrivalGroupDecision("WAITING_MANUAL_CONFIRM");
        }
        updateGroupState(groupFiles, key, "WAITING_ARRIVAL", "WAITING_REQUIRED_FILES", now, requiredFiles, missingFiles);
        return new ArrivalGroupDecision("WAITING_ARRIVAL");
    }

    private void updateGroupState(List<Map<String, Object>> groupFiles,
                                  ArrivalGroupKey key,
                                  String arrivalState,
                                  String reason,
                                  Instant now,
                                  Set<String> requiredFiles,
                                  Set<String> missingFiles) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fileGroupCode", key.fileGroupCode());
        metadata.put("waitFileGroupMode", key.waitFileGroupMode());
        metadata.put("requiredFileSet", key.requiredFileSet());
        metadata.put("arrivalTimeoutAction", key.arrivalTimeoutAction());
        metadata.put("arrivalState", arrivalState);
        metadata.put("arrivalReason", reason);
        metadata.put("arrivalCheckedAt", now.toString());
        metadata.put("groupArrivedCount", groupFiles.size());
        metadata.put("groupRequiredCount", requiredFiles.size());
        metadata.put("groupMissingFileSet", String.join(",", missingFiles));
        if ("TRIGGERED".equals(arrivalState)) {
            metadata.put("arrivalTriggeredAt", now.toString());
        }
        if ("TIMEOUT".equals(arrivalState)) {
            metadata.put("arrivalTimedOutAt", now.toString());
        }
        for (Map<String, Object> file : groupFiles) {
            Long fileId = toLong(file.get("id"));
            String tenantId = text(file.get("tenant_id"));
            if (fileId == null || tenantId == null) {
                continue;
            }
            fileGovernanceRepository.updateFileMetadata(tenantId, fileId, metadata);
            fileGovernanceRepository.appendAudit(
                    tenantId,
                    fileId,
                    "ARRIVAL_GROUP_" + arrivalState,
                    "TRIGGERED".equals(arrivalState) ? "SUCCESS" : "PENDING",
                    "SYSTEM",
                    "file-governance-scheduler",
                    "arrival-group-" + key.fileGroupCode(),
                    metadata
            );
        }
        log.info("file arrival group updated: tenantId={}, fileGroupCode={}, state={}, reason={}, arrivedCount={}, requiredCount={}, missingCount={}",
                key.tenantId(), key.fileGroupCode(), arrivalState, reason, groupFiles.size(), requiredFiles.size(), missingFiles.size());
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
            return null;
        }
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

    private record ArrivalGroupKey(String tenantId,
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
            if (tenantId == null || tenantId.isBlank() || fileGroupCode == null || fileGroupCode.isBlank()) {
                return null;
            }
            return new ArrivalGroupKey(
                    tenantId,
                    fileGroupCode,
                    defaultString(textValue(candidate.get("wait_file_group_mode")), "ALL_OF"),
                    defaultString(textValue(candidate.get("required_file_set")), ""),
                    defaultString(textValue(candidate.get("arrival_timeout_action")), "MANUAL_CONFIRM")
            );
        }

        private static String textValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }

        private static String defaultString(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    private record ArrivalGroupDecision(String state) {
    }
}
