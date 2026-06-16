package com.example.batch.worker.imports.runtime;

import com.example.batch.common.config.S3StorageProperties;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.ObjectListing;
import com.example.batch.common.storage.ObjectSummary;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.FileAuditParam;
import com.example.batch.worker.core.infrastructure.FileRecordParam;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.config.ImportScannerProperties;
import com.example.batch.worker.imports.config.ImportWorkerConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * MinIO 入库扫描器：定时轮询对象存储 bucket，将新到达的文件自动登记为 {@code file_record}（status=RECEIVED）， 为后续 Import
 * pipeline 提供触发点。扫描器只负责"发现并登记"，不直接调度任务。
 *
 * <p><b>去重与稳定性</b>：
 *
 * <ul>
 *   <li>已存在 {@code storage_path} 的 file_record 不重复登记（idempotent）。
 *   <li>稳定性窗口（{@code stabilityWindowSeconds}）：size+etag 在窗口期内保持不变才视为上传完成， 防止扫描到正在写入的临时文件。
 *   <li>{@code requireDoneFile=true} 时，须同名 {@code .done} 标记文件存在方可登记 （适用于原子性要求高的文件到达协议）。
 * </ul>
 *
 * <p><b>到达组（arrival group）</b>：若配置了 {@code fileGroupCode + requiredFileSet}， 登记时写入到达组元数据并打 {@code
 * ARRIVAL_REGISTER} 审计，用于等待一批文件全部到齐后再统一触发。
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
  private final S3StorageProperties s3StorageProperties;
  private final BatchObjectStore objectStore;
  private final Map<String, ObservedObjectState> observedObjects = new ConcurrentHashMap<>();

  /** 扫描器只负责“安全发现 + 登记”，不绕过 Trigger/Orchestrator 直接起任务。 */
  @Scheduled(fixedDelayString = "${batch.worker.import.scanner.poll-interval-millis:30000}")
  @SchedulerLock(name = "import_ingress_scan", lockAtMostFor = "PT2M", lockAtLeastFor = "PT20S")
  public void scheduledScan() {
    scan();
  }

  /** 无锁入口，供测试和手动调用使用。调度逻辑留在 {@link #scheduledScan()} 中，直接调用时始终执行扫描逻辑。 */
  public void scan() {
    if (!scannerProperties.isEnabled()) {
      return;
    }
    Map<String, ObjectSnapshot> snapshots = listSnapshots();
    Set<String> currentObjects = new HashSet<>(snapshots.keySet());
    for (Map.Entry<String, ObjectSnapshot> entry : snapshots.entrySet()) {
      tryRegister(entry.getValue(), currentObjects);
    }
    observedObjects.keySet().removeIf(existing -> !currentObjects.contains(existing));
  }

  /**
   * 从对象路径解析租户。约定路径布局 {@code <prefix><tenantId>/<fileName>}（例如 {@code
   * ingress/ta/customer-20260529.csv}）。 解析失败返回空字符串，由调用方决定 fallback：worker config 的 tenantId
   * 仍然作为兼容入口 （旧的"按 worker tenant 投放"语义不破坏），不带 tenant 段的对象按 worker 自身 tenantId 登记。
   */
  private String resolveTenantFromObjectName(String objectName) {
    String prefix = scannerProperties.getPrefix();
    if (objectName == null) {
      return "";
    }
    String key =
        Texts.hasText(prefix) && objectName.startsWith(prefix)
            ? objectName.substring(prefix.length())
            : objectName;
    int slash = key.indexOf('/');
    if (slash <= 0) {
      return "";
    }
    return key.substring(0, slash);
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
    // 多租户:从 prefix 解析 tenant;解析不到时 fallback 到 worker 自身 tenantId(兼容旧布局)
    String resolvedTenant = resolveTenantFromObjectName(snapshot.objectName());
    if (!Texts.hasText(resolvedTenant)) {
      resolvedTenant = workerConfiguration.tenantId();
    }
    if (!Texts.hasText(resolvedTenant)) {
      log.warn(
          "import ingress scanner skipped object without tenant prefix nor fallback tenantId:"
              + " objectName={}",
          snapshot.objectName());
      return;
    }
    if (runtimeRepository.existsFileRecordByStoragePath(
        resolvedTenant, s3StorageProperties.getBucket(), snapshot.objectName())) {
      return;
    }
    String fileName =
        snapshot.objectName().contains("/")
            ? snapshot.objectName().substring(snapshot.objectName().lastIndexOf('/') + 1)
            : snapshot.objectName();
    LocalDate bizDate = resolveScannerBizDate(snapshot.objectName());
    if (bizDate == null) {
      return;
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("scanner", "objectStore-import");
    metadata.put("doneRequired", scannerProperties.isRequireDoneFile());
    metadata.put("stabilityWindowSeconds", scannerProperties.getStabilityWindowSeconds());
    metadata.put("etag", snapshot.etag());
    metadata.put("lastModified", snapshot.lastModified());
    metadata.put("detectedAt", BatchDateTimeSupport.utcNow().toString());
    if (scannerProperties.getArrival().isEnabled()
        && Texts.hasText(scannerProperties.getArrival().getFileGroupCode())
        && Texts.hasText(scannerProperties.getArrival().getRequiredFileSet())) {
      metadata.put("fileGroupCode", scannerProperties.getArrival().getFileGroupCode());
      metadata.put("waitFileGroupMode", scannerProperties.getArrival().getWaitFileGroupMode());
      metadata.put("requiredFileSet", scannerProperties.getArrival().getRequiredFileSet());
      metadata.put(
          "arrivalTimeoutAction", scannerProperties.getArrival().getArrivalTimeoutAction());
      metadata.put(
          "expectedArrivalTime",
          BatchDateTimeSupport.utcNow()
              .plusSeconds(scannerProperties.getArrival().getExpectedArrivalDelaySeconds())
              .toString());
      metadata.put(
          "latestTolerableTime",
          BatchDateTimeSupport.utcNow()
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
                .tenantId(resolvedTenant)
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
                .storageBucket(s3StorageProperties.getBucket())
                .fileVersion(null)
                .bizDate(bizDate)
                .sourceType(scannerProperties.getSourceType())
                .sourceRef(snapshot.objectName())
                .fileStatus("RECEIVED")
                .traceId("import-scan-" + sanitizeTrace(fileName))
                .metadata(metadata)
                .build());
    if (scannerProperties.getArrival().isEnabled()
        && Texts.hasText(scannerProperties.getArrival().getFileGroupCode())
        && Texts.hasText(scannerProperties.getArrival().getRequiredFileSet())) {
      runtimeRepository.appendAudit(
          FileAuditParam.builder()
              .fileId(fileId)
              .tenantId(resolvedTenant)
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
            .tenantId(resolvedTenant)
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
        resolvedTenant,
        fileId,
        snapshot.objectName());
  }

  private boolean isStable(ObjectSnapshot snapshot) {
    if (scannerProperties.getStabilityWindowSeconds() <= 0) {
      return true;
    }
    Instant now = BatchDateTimeSupport.utcNow();
    // P2：用 ConcurrentHashMap.compute 原子化 check-and-update，即便 ShedLock
    // 异常过期时也不会有 get+put 之间的 TOCTOU；返回更新后的 state，稳定性判断在外部做
    ObservedObjectState state =
        observedObjects.compute(
            snapshot.objectName(),
            (k, existing) -> {
              if (existing == null
                  || existing.size() != snapshot.size()
                  || !Objects.equals(existing.etag(), snapshot.etag())) {
                return new ObservedObjectState(snapshot.size(), snapshot.etag(), now);
              }
              return existing;
            });
    // 刚被替换（或首次观察）→ 还未稳定；否则看 firstObservedAt + stabilityWindow 是否已过
    if (state.firstObservedAt().equals(now)) {
      return false;
    }
    return state
        .firstObservedAt()
        .plusSeconds(scannerProperties.getStabilityWindowSeconds())
        .isBefore(now);
  }

  private LocalDate resolveScannerBizDate(String objectName) {
    LocalDate fromName = parseBizDateFromObjectName(objectName);
    if (fromName != null) {
      return fromName;
    }
    if (!Texts.hasText(scannerProperties.getDefaultBizDate())) {
      log.warn(
          "import ingress scanner skipped object without bizDatePattern match and no"
              + " defaultBizDate: objectName={}, bizDatePattern={}",
          objectName,
          scannerProperties.getBizDatePattern());
      return null;
    }
    try {
      return LocalDate.parse(scannerProperties.getDefaultBizDate().trim());
    } catch (Exception invalid) {
      SwallowedExceptionLogger.warn(ImportIngressScanner.class, "catch:Exception", invalid);
      log.warn(
          "import ingress scanner skipped object with invalid defaultBizDate: objectName={},"
              + " defaultBizDate={}",
          objectName,
          scannerProperties.getDefaultBizDate());
      return null;
    }
  }

  private LocalDate parseBizDateFromObjectName(String objectName) {
    String pattern = scannerProperties.getBizDatePattern();
    if (!Texts.hasText(pattern) || objectName == null) {
      return null;
    }
    try {
      Matcher matcher = Pattern.compile(pattern.trim()).matcher(objectName);
      if (!matcher.find()) {
        return null;
      }
      String token = extractBizDateToken(matcher);
      if (token == null || token.isBlank()) {
        return null;
      }
      return LocalDate.parse(token, DateTimeFormatter.BASIC_ISO_DATE);
    } catch (PatternSyntaxException invalidPattern) {
      SwallowedExceptionLogger.warn(
          ImportIngressScanner.class, "catch:PatternSyntaxException", invalidPattern);
      log.warn(
          "import ingress scanner bizDatePattern is not valid regex: pattern={}, objectName={}",
          pattern,
          objectName);
      return null;
    } catch (Exception invalid) {
      SwallowedExceptionLogger.warn(ImportIngressScanner.class, "catch:Exception", invalid);
      log.warn(
          "import ingress scanner failed to parse bizDate from objectName: pattern={},"
              + " objectName={}",
          pattern,
          objectName);
      return null;
    }
  }

  private String extractBizDateToken(Matcher matcher) {
    try {
      String named = matcher.group("bizDate");
      if (named != null) {
        return named;
      }
    } catch (IllegalArgumentException namedGroupAbsent) {
      SwallowedExceptionLogger.info(
          ImportIngressScanner.class, "catch:IllegalArgumentException", namedGroupAbsent);
    }
    return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
  }

  private Map<String, ObjectSnapshot> listSnapshots() {
    Map<String, ObjectSnapshot> snapshots = new HashMap<>();
    int batchSize = scannerProperties.getBatchSize();
    String bucket = s3StorageProperties.getBucket();
    String prefix = scannerProperties.getPrefix();
    try {
      String marker = null;
      // 循环翻页累计，直到取满 batchSize（截断语义同旧实现）或末页（nextMarker==null）。
      while (snapshots.size() < batchSize) {
        ObjectListing listing = objectStore.list(bucket, prefix, marker, batchSize);
        for (ObjectSummary summary : listing.objects()) {
          if (snapshots.size() >= batchSize) {
            break;
          }
          snapshots.put(
              summary.key(),
              new ObjectSnapshot(
                  summary.key(), summary.size(), summary.etag(), summary.lastModified()));
        }
        marker = listing.nextMarker();
        if (marker == null) {
          break;
        }
      }
      return snapshots;
    } catch (Exception exception) {
      throw new IllegalStateException("failed to scan objectStore ingress objects", exception);
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
    // 仅 .xlsx(OOXML)映射 EXCEL;旧二进制 .xls(OLE2/HSSF)不再假映射成 EXCEL ——
    // ExcelFormatParser 走纯 OOXML 的 OPCPackage.open(),喂 OLE2 字节必崩当坏文件。
    // .xls 落 BINARY,真正解析时由 ExcelFormatParser/上游格式路由给出明确报错(转 .xlsx 提示),
    // 而非在 PARSE 阶段静默产出坏数据。详见 ExcelFormatParser 的 OLE2 fail-fast。
    Map<String, String> formatMap =
        Map.ofEntries(
            Map.entry(".csv", "DELIMITED"),
            Map.entry(".xlsx", "EXCEL"),
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

  private record ObjectSnapshot(String objectName, long size, String etag, Instant lastModified) {}

  private record ObservedObjectState(long size, String etag, Instant firstObservedAt) {}
}
