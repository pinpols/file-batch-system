package com.example.batch.orchestrator.infrastructure.file;

import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.infrastructure.file.MinioGovernanceStorage.StorageObjectView;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FileGovernanceScheduler {

    private final FileGovernanceRepository fileGovernanceRepository;
    private final MinioGovernanceStorage minioGovernanceStorage;
    private final FileGovernanceProperties properties;
    private final AtomicLong arrivalDelayViolations = new AtomicLong();
    private final AtomicLong arrivalDelayMaxSeconds = new AtomicLong();
    private final AtomicLong processingDelayViolations = new AtomicLong();
    private final AtomicLong processingDelayMaxSeconds = new AtomicLong();

    public FileGovernanceScheduler(FileGovernanceRepository fileGovernanceRepository,
                                   MinioGovernanceStorage minioGovernanceStorage,
                                   FileGovernanceProperties properties,
                                   MeterRegistry meterRegistry) {
        this.fileGovernanceRepository = fileGovernanceRepository;
        this.minioGovernanceStorage = minioGovernanceStorage;
        this.properties = properties;
        meterRegistry.gauge("batch.file.arrival.delay.violations", arrivalDelayViolations);
        meterRegistry.gauge("batch.file.arrival.delay.max.seconds", arrivalDelayMaxSeconds);
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
}
