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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
  private final ObjectMapper objectMapper;
  private final Map<String, ObservedObjectState> observedObjects = new ConcurrentHashMap<>();

  /** sidecar manifest(.chk JSON)上限,防异常大对象拖垮扫描;manifest 本应是 KB 级小文件。 */
  private static final long MAX_MANIFEST_BYTES = 64L * 1024;

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
    List<BatchManifest> batchManifests = collectBatchManifests(snapshots);
    for (Map.Entry<String, ObjectSnapshot> entry : snapshots.entrySet()) {
      tryRegister(entry.getValue(), currentObjects, batchManifests);
    }
    observedObjects.keySet().removeIf(existing -> !currentObjects.contains(existing));
  }

  /** 本轮扫描里识别并解析所有批次清单对象(按后缀);未开启或无清单返回空表。 */
  private List<BatchManifest> collectBatchManifests(Map<String, ObjectSnapshot> snapshots) {
    if (!scannerProperties.isBatchManifestEnabled()) {
      return List.of();
    }
    String suffix = scannerProperties.getBatchManifestSuffix();
    List<BatchManifest> result = new ArrayList<>();
    for (String objectName : snapshots.keySet()) {
      if (objectName.endsWith(suffix)) {
        BatchManifest manifest = readBatchManifest(objectName);
        if (manifest != null) {
          result.add(manifest);
        }
      }
    }
    return result;
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

  private void tryRegister(
      ObjectSnapshot snapshot, Set<String> currentObjects, List<BatchManifest> batchManifests) {
    if (snapshot == null || snapshot.objectName().endsWith(scannerProperties.getDoneFileSuffix())) {
      return;
    }
    if (scannerProperties.isBatchManifestEnabled()
        && snapshot.objectName().endsWith(scannerProperties.getBatchManifestSuffix())) {
      return; // 批次清单对象本身不当数据文件登记
    }
    SidecarManifest manifest = null;
    if (scannerProperties.isRequireDoneFile()) {
      String marker = resolveDoneMarker(snapshot.objectName());
      if (!currentObjects.contains(marker)) {
        return;
      }
      if (isManifestMode()) {
        manifest = readAndVerifyManifest(marker, snapshot);
        if (manifest == null) {
          return; // manifest 缺失 / 解析失败 / size 不符 → 视为未完整,不登记(下轮重试或人工介入)
        }
      }
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
      // ADR-040 Phase2:数据文件先到、清单后到 —— 对已登记成员回填 required_file_set(幂等)
      backfillBatchManifestArrival(snapshot, resolvedTenant, batchManifests);
      return;
    }
    String fileName = baseName(snapshot.objectName());
    LocalDate bizDate = resolveScannerBizDate(snapshot.objectName());
    if (bizDate == null) {
      return;
    }
    // ADR-040 Phase1:命中批次清单(成员名 ∈ manifest.requiredFiles)→ 用清单的 group + requiredFiles
    // 覆盖静态配置,实现动态成组;未命中则沿用静态 arrival 配置。
    String effectiveGroupCode = scannerProperties.getArrival().getFileGroupCode();
    String effectiveRequiredFileSet = scannerProperties.getArrival().getRequiredFileSet();
    BatchManifest matchedBatch = matchBatchManifest(batchManifests, resolvedTenant, fileName);
    if (matchedBatch != null) {
      effectiveGroupCode = matchedBatch.fileGroupCode();
      effectiveRequiredFileSet = String.join(",", matchedBatch.requiredFiles());
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("scanner", "objectStore-import");
    metadata.put("doneRequired", scannerProperties.isRequireDoneFile());
    metadata.put("stabilityWindowSeconds", scannerProperties.getStabilityWindowSeconds());
    metadata.put("etag", snapshot.etag());
    metadata.put("lastModified", snapshot.lastModified());
    metadata.put("detectedAt", BatchDateTimeSupport.utcNow().toString());
    if (scannerProperties.getArrival().isEnabled()
        && Texts.hasText(effectiveGroupCode)
        && Texts.hasText(effectiveRequiredFileSet)) {
      putArrivalMetadata(metadata, effectiveGroupCode, effectiveRequiredFileSet);
    }
    if (manifest != null) {
      metadata.put("manifestSchemaVersion", manifest.schemaVersion());
      if (manifest.recordCount() != null) {
        metadata.put("expectedRecordCount", manifest.recordCount());
      }
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
                .checksumType(
                    manifest != null && Texts.hasText(manifest.checksumType())
                        ? manifest.checksumType()
                        : "NONE")
                .checksumValue(manifest == null ? null : manifest.checksumValue())
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
        && Texts.hasText(effectiveGroupCode)
        && Texts.hasText(effectiveRequiredFileSet)) {
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
                      "fileGroupCode", effectiveGroupCode,
                      "requiredFileSet", effectiveRequiredFileSet,
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

  /** 标记名:APPEND_FULL_NAME=全名+后缀;REPLACE_EXTENSION=去末段扩展名+后缀。 */
  private String resolveDoneMarker(String objectName) {
    String suffix = scannerProperties.getDoneFileSuffix();
    if (!"REPLACE_EXTENSION".equalsIgnoreCase(scannerProperties.getDoneFileNaming())) {
      return objectName + suffix;
    }
    int dotIndex = objectName.lastIndexOf('.');
    if (dotIndex > objectName.lastIndexOf('/')) {
      return objectName.substring(0, dotIndex) + suffix;
    }
    return objectName + suffix;
  }

  private boolean isManifestMode() {
    return "MANIFEST".equalsIgnoreCase(scannerProperties.getDoneFileFormat());
  }

  /** 读 .chk JSON manifest:解析 + 免下载 size 校验;失败/不符返回 null。 */
  private SidecarManifest readAndVerifyManifest(String markerName, ObjectSnapshot snapshot) {
    try (InputStream in = objectStore.get(s3StorageProperties.getBucket(), markerName)) {
      byte[] bytes = in.readNBytes((int) MAX_MANIFEST_BYTES + 1);
      if (bytes.length > MAX_MANIFEST_BYTES) {
        log.warn("sidecar manifest too large, skip register: marker={}", markerName);
        return null;
      }
      SidecarManifest manifest = objectMapper.readValue(bytes, SidecarManifest.class);
      if (manifest.sizeBytes() != null && manifest.sizeBytes() != snapshot.size()) {
        log.warn(
            "manifest size mismatch, skip register: object={}, manifestSize={}, actualSize={}",
            snapshot.objectName(),
            manifest.sizeBytes(),
            snapshot.size());
        return null;
      }
      return manifest;
    } catch (Exception ex) {
      log.warn(
          "failed to read/parse sidecar manifest, skip register: marker={}, error={}",
          markerName,
          ex.getMessage());
      return null;
    }
  }

  /** 写入到达组 metadata(到达组 SLA + 凑齐判定所需);group/requiredFileSet 已按批次清单或静态配置解析。 */
  private void putArrivalMetadata(
      Map<String, Object> metadata, String groupCode, String requiredFileSet) {
    metadata.put("fileGroupCode", groupCode);
    metadata.put("waitFileGroupMode", scannerProperties.getArrival().getWaitFileGroupMode());
    metadata.put("requiredFileSet", requiredFileSet);
    metadata.put("arrivalTimeoutAction", scannerProperties.getArrival().getArrivalTimeoutAction());
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

  private String baseName(String objectName) {
    return objectName.contains("/")
        ? objectName.substring(objectName.lastIndexOf('/') + 1)
        : objectName;
  }

  /**
   * ADR-040 Phase2 回填:数据文件先到、批次清单后到时,对已登记成员补 required_file_set + 到达组 metadata。 幂等:已带
   * requiredFileSet 的记录跳过,避免每轮 tick 抖 updated_at。
   */
  private void backfillBatchManifestArrival(
      ObjectSnapshot snapshot, String tenantId, List<BatchManifest> batchManifests) {
    if (!scannerProperties.isBatchManifestEnabled() || batchManifests.isEmpty()) {
      return;
    }
    BatchManifest matched =
        matchBatchManifest(batchManifests, tenantId, baseName(snapshot.objectName()));
    if (matched == null) {
      return;
    }
    Map<String, Object> record =
        runtimeRepository.loadFileRecordByStoragePath(
            tenantId, s3StorageProperties.getBucket(), snapshot.objectName());
    if (record == null || record.isEmpty() || hasRequiredFileSet(record)) {
      return;
    }
    Long fileId = runtimeRepository.toLong(record.get("id"));
    if (fileId == null) {
      return;
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    putArrivalMetadata(metadata, matched.fileGroupCode(), String.join(",", matched.requiredFiles()));
    runtimeRepository.updateFileMetadata(fileId, metadata);
    log.info(
        "batch manifest backfilled arrival group for registered file: tenantId={}, fileId={},"
            + " fileGroupCode={}",
        tenantId,
        fileId,
        matched.fileGroupCode());
  }

  /** 已登记记录的 metadata_json 是否已含非空 requiredFileSet(回填幂等判据)。 */
  private boolean hasRequiredFileSet(Map<String, Object> record) {
    Object metadataJson = record.get("metadata_json");
    if (metadataJson == null) {
      return false;
    }
    try {
      Map<String, Object> meta =
          objectMapper.readValue(String.valueOf(metadataJson), new TypeReference<>() {});
      Object value = meta.get("requiredFileSet");
      return value != null && Texts.hasText(String.valueOf(value));
    } catch (Exception ex) {
      return false;
    }
  }

  /** 命中规则:成员文件名 ∈ 某清单 requiredFiles 且租户匹配;返回首个命中清单,无则 null。 */
  private BatchManifest matchBatchManifest(
      List<BatchManifest> manifests, String tenantId, String fileName) {
    for (BatchManifest m : manifests) {
      if (m.requiredFiles() == null
          || m.requiredFiles().isEmpty()
          || !Texts.hasText(m.fileGroupCode())) {
        continue;
      }
      if (Texts.hasText(m.tenantId()) && !m.tenantId().equals(tenantId)) {
        continue;
      }
      if (m.requiredFiles().contains(fileName)) {
        return m;
      }
    }
    return null;
  }

  /** 读批次清单 JSON;失败返回 null(本轮不参与动态成组,下轮重试)。 */
  private BatchManifest readBatchManifest(String objectName) {
    try (InputStream in = objectStore.get(s3StorageProperties.getBucket(), objectName)) {
      byte[] bytes = in.readNBytes((int) MAX_MANIFEST_BYTES + 1);
      if (bytes.length > MAX_MANIFEST_BYTES) {
        log.warn("batch manifest too large, skip: object={}", objectName);
        return null;
      }
      return objectMapper.readValue(bytes, BatchManifest.class);
    } catch (Exception ex) {
      log.warn(
          "failed to read/parse batch manifest: object={}, error={}", objectName, ex.getMessage());
      return null;
    }
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
