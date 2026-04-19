package com.example.batch.worker.imports.runtime;

import com.example.batch.common.config.MinioStorageProperties;
import com.example.batch.common.utils.MinioBucketSupport;
import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.FileRecordParam;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.ImportScannerProperties;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * MinIO 入库扫描器：定时轮询对象存储 bucket，将新到达的文件自动登记为 {@code file_record}（status=RECEIVED），
 * 为后续 Import pipeline 提供触发点。扫描器只负责"发现并登记"，不直接调度任务。
 *
 * <p><b>去重与稳定性</b>：
 * <ul>
 *   <li>已存在 {@code storage_path} 的 file_record 不重复登记（idempotent）。
 *   <li>稳定性窗口（{@code stabilityWindowSeconds}）：size+etag 在窗口期内保持不变才视为上传完成，
 *       防止扫描到正在写入的临时文件。
 *   <li>{@code requireDoneFile=true} 时，须同名 {@code .done} 标记文件存在方可登记
 *       （适用于原子性要求高的文件到达协议）。
 * </ul>
 *
 * <p><b>到达组（arrival group）</b>：若配置了 {@code fileGroupCode + requiredFileSet}，
 * 登记时写入到达组元数据并打 {@code ARRIVAL_REGISTER} 审计，用于等待一批文件全部到齐后再统一触发。
 *
 * <p>ShedLock（{@code import_ingress_scan}）保证多节点部署时只有一个实例执行扫描，避免重复登记竞争。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportIngressScanner {

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final ImportWorkerConfiguration workerConfiguration;
  private final ImportScannerProperties scannerProperties;
  private final MinioStorageProperties minioStorageProperties;
  private final MinioClient minioClient;
  private final Map<String, ObservedObjectState> observedObjects = new ConcurrentHashMap<>();

  /** 扫描器只负责“安全发现 + 登记”，不绕过 Trigger/Orchestrator 直接起任务。 */
  @Scheduled(fixedDelayString = "${batch.worker.import.scanner.poll-interval-millis:30000}")
  @SchedulerLock(name = "import_ingress_scan", lockAtMostFor = "PT2M", lockAtLeastFor = "PT20S")
  public void scheduledScan() {
    scan();
  }

  /** 无锁入口，供测试和手动调用使用。调度逻辑留在 {@link #scheduledScan()} 中，直接调用时始终执行扫描逻辑。 */
  public void scan() {
    if (!scannerProperties.isEnabled() || !StringUtils.hasText(workerConfiguration.tenantId())) {
      return;
    }
    if (!ensureBucket()) {
      return;
    }
    Map<String, ObjectSnapshot> snapshots = listSnapshots();
    Set<String> currentObjects = new HashSet<>(snapshots.keySet());
    for (Map.Entry<String, ObjectSnapshot> entry : snapshots.entrySet()) {
      tryRegister(entry.getValue(), currentObjects);
    }
    observedObjects.keySet().removeIf(existing -> !currentObjects.contains(existing));
  }

  private void tryRegister(ObjectSnapshot snapshot, Set<String> currentObjects) {
    if (snapshot == null || snapshot.objectName().endsWith(".done")) {
      return;
    }
    if (scannerProperties.isRequireDoneFile()
        && !currentObjects.contains(resolveDoneMarker(snapshot.objectName()))) {
      return;
    }
    if (!isStable(snapshot)) {
      return;
    }
    if (runtimeRepository.existsFileRecordByStoragePath(
        workerConfiguration.tenantId(),
        minioStorageProperties.getBucket(),
        snapshot.objectName())) {
      return;
    }
    String fileName =
        snapshot.objectName().contains("/")
            ? snapshot.objectName().substring(snapshot.objectName().lastIndexOf('/') + 1)
            : snapshot.objectName();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("scanner", "minio-import");
    metadata.put("doneRequired", scannerProperties.isRequireDoneFile());
    metadata.put("stabilityWindowSeconds", scannerProperties.getStabilityWindowSeconds());
    metadata.put("etag", snapshot.etag());
    metadata.put("lastModified", snapshot.lastModified());
    metadata.put("detectedAt", Instant.now().toString());
    if (scannerProperties.getArrival().isEnabled()
        && StringUtils.hasText(scannerProperties.getArrival().getFileGroupCode())
        && StringUtils.hasText(scannerProperties.getArrival().getRequiredFileSet())) {
      metadata.put("fileGroupCode", scannerProperties.getArrival().getFileGroupCode());
      metadata.put("waitFileGroupMode", scannerProperties.getArrival().getWaitFileGroupMode());
      metadata.put("requiredFileSet", scannerProperties.getArrival().getRequiredFileSet());
      metadata.put(
          "arrivalTimeoutAction", scannerProperties.getArrival().getArrivalTimeoutAction());
      metadata.put(
          "expectedArrivalTime",
          Instant.now()
              .plusSeconds(scannerProperties.getArrival().getExpectedArrivalDelaySeconds())
              .toString());
      metadata.put(
          "latestTolerableTime",
          Instant.now()
              .plusSeconds(scannerProperties.getArrival().getLatestTolerableDelaySeconds())
              .toString());
      metadata.put("arrivalState", "WAITING_ARRIVAL");
      metadata.put("triggerOnComplete", scannerProperties.getArrival().isTriggerOnComplete());
      metadata.put("allowEmptyRun", scannerProperties.getArrival().isAllowEmptyRun());
      metadata.put("allowSkipBizDate", scannerProperties.getArrival().isAllowSkipBizDate());
      metadata.put("notifyManual", scannerProperties.getArrival().isNotifyManual());
      metadata.put("notifyChannels", scannerProperties.getArrival().getNotifyChannels());
    }
    Long fileId =
        runtimeRepository.createFileRecord(
            FileRecordParam.builder()
                .tenantId(workerConfiguration.tenantId())
                .fileCode(null)
                .bizType(scannerProperties.getDefaultBizType())
                .fileCategory("INPUT")
                .fileName(fileName)
                .originalFileName(fileName)
                .fileFormatType(resolveFileFormatType(fileName))
                .charset(StandardCharsets.UTF_8.name())
                .fileSizeBytes(snapshot.size())
                .checksumType("NONE")
                .checksumValue(null)
                .storageType("S3")
                .storagePath(snapshot.objectName())
                .storageBucket(minioStorageProperties.getBucket())
                .fileVersion(null)
                .bizDate(LocalDate.now())
                .sourceType(scannerProperties.getSourceType())
                .sourceRef(snapshot.objectName())
                .fileStatus("RECEIVED")
                .traceId("import-scan-" + sanitizeTrace(fileName))
                .metadata(metadata)
                .build());
    if (scannerProperties.getArrival().isEnabled()
        && StringUtils.hasText(scannerProperties.getArrival().getFileGroupCode())
        && StringUtils.hasText(scannerProperties.getArrival().getRequiredFileSet())) {
      runtimeRepository.appendAudit(
          FileAuditParam.builder()
              .fileId(fileId)
              .tenantId(workerConfiguration.tenantId())
              .operationType("ARRIVAL_REGISTER")
              .operationResult("SUCCESS")
              .operatorType("SYSTEM")
              .operatorId("import-ingress-scanner")
              .traceId("arrival-" + sanitizeTrace(fileName))
              .evidenceRef(snapshot.objectName())
              .detailSummary(
                  Map.of(
                      "fileGroupCode", scannerProperties.getArrival().getFileGroupCode(),
                      "requiredFileSet", scannerProperties.getArrival().getRequiredFileSet(),
                      "arrivalState", "WAITING_ARRIVAL"))
              .build());
    }
    runtimeRepository.appendAudit(
        FileAuditParam.builder()
            .fileId(fileId)
            .tenantId(workerConfiguration.tenantId())
            .operationType("RECEIVE_SCAN")
            .operationResult("SUCCESS")
            .operatorType("SYSTEM")
            .operatorId("import-ingress-scanner")
            .traceId("import-scan-" + sanitizeTrace(fileName))
            .evidenceRef(snapshot.objectName())
            .detailSummary(metadata)
            .build());
    log.info(
        "import file registered by scanner: tenantId={}, fileId={}, objectName={}",
        workerConfiguration.tenantId(),
        fileId,
        snapshot.objectName());
  }

  private boolean isStable(ObjectSnapshot snapshot) {
    if (scannerProperties.getStabilityWindowSeconds() <= 0) {
      return true;
    }
    ObservedObjectState existing = observedObjects.get(snapshot.objectName());
    Instant now = Instant.now();
    if (existing == null
        || existing.size() != snapshot.size()
        || !Objects.equals(existing.etag(), snapshot.etag())) {
      observedObjects.put(
          snapshot.objectName(), new ObservedObjectState(snapshot.size(), snapshot.etag(), now));
      return false;
    }
    return existing
        .firstObservedAt()
        .plusSeconds(scannerProperties.getStabilityWindowSeconds())
        .isBefore(now);
  }

  private Map<String, ObjectSnapshot> listSnapshots() {
    Map<String, ObjectSnapshot> snapshots = new HashMap<>();
    int count = 0;
    try {
      Iterable<Result<Item>> objects =
          minioClient.listObjects(
              ListObjectsArgs.builder()
                  .bucket(minioStorageProperties.getBucket())
                  .prefix(scannerProperties.getPrefix())
                  .recursive(true)
                  .build());
      for (Result<Item> result : objects) {
        if (count >= scannerProperties.getBatchSize()) {
          break;
        }
        Item item = result.get();
        snapshots.put(
            item.objectName(),
            new ObjectSnapshot(
                item.objectName(),
                item.size(),
                item.etag(),
                item.lastModified() == null ? null : item.lastModified().toInstant()));
        count++;
      }
      return snapshots;
    } catch (Exception exception) {
      throw new IllegalStateException("failed to scan minio ingress objects", exception);
    }
  }

  private String resolveDoneMarker(String objectName) {
    int dotIndex = objectName.lastIndexOf('.');
    if (dotIndex > objectName.lastIndexOf('/')) {
      return objectName.substring(0, dotIndex) + ".done";
    }
    return objectName + ".done";
  }

  private String resolveFileFormatType(String fileName) {
    String lower = fileName == null ? "" : fileName.toLowerCase();
    Map<String, String> formatMap =
        Map.ofEntries(
            Map.entry(".csv", "DELIMITED"),
            Map.entry(".xlsx", "EXCEL"),
            Map.entry(".xls", "EXCEL"),
            Map.entry(".xml", "XML"),
            Map.entry(".json", "JSON"));
    return formatMap.entrySet().stream()
        .filter(e -> lower.endsWith(e.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse("BINARY");
  }

  private String sanitizeTrace(String fileName) {
    return fileName == null ? "object" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private boolean ensureBucket() {
    return MinioBucketSupport.ensureBucket(
        minioClient, minioStorageProperties.getBucket(), log, "import scanner");
  }

  private record ObjectSnapshot(String objectName, long size, String etag, Instant lastModified) {}

  private record ObservedObjectState(long size, String etag, Instant firstObservedAt) {}
}
