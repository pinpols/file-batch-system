package io.github.pinpols.batch.worker.imports.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.enums.FileAuditOperationType;
import io.github.pinpols.batch.common.enums.FileIntegrityState;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.enums.OperationResult;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.common.storage.ObjectListing;
import io.github.pinpols.batch.common.storage.ObjectSummary;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipIdentity;
import io.github.pinpols.batch.common.storage.ingress.ImportIngressOwnershipRepository;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.common.validation.ValidResourceCode;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam.FileAuditParamBuilder;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam.FileRecordParamBuilder;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.core.param.PlatformReconciledFileAdoptionParam;
import io.github.pinpols.batch.worker.imports.config.ImportScannerProperties;
import io.github.pinpols.batch.worker.imports.config.ImportWorkerConfiguration;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Lease;
import io.github.pinpols.batch.worker.imports.runtime.ImportIngressScanRepository.Observed;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 对象存储入库扫描器：定时轮询 bucket，将新到达的文件自动登记为 {@code file_record}（status=RECEIVED）， 为后续 Import
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
 * <p>定时和事件扫描通过数据库共享租约协调同一物理存储范围，并在提交扫描结果前校验租约。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportIngressScanner {

  private final PlatformFileRecordRepository fileRecords;
  private final PlatformFileAuditRepository fileAudits;
  private final ImportWorkerConfiguration workerConfiguration;
  private final ImportScannerProperties scannerProperties;
  private final S3StorageProperties s3StorageProperties;
  private final BatchObjectStore objectStore;
  private final ObjectMapper objectMapper;
  private final ImportIngressScanRepository scanRepository;
  private final ImportIngressOwnershipIdentity ownershipIdentity;

  /** sidecar manifest(.chk JSON)上限,防异常大对象拖垮扫描;manifest 本应是 KB 级小文件。 */
  private static final String KEY_REQUIRED_FILE_SET = "requiredFileSet";

  private static final long MAX_MANIFEST_BYTES = 64L * 1024;
  private final AtomicBoolean ownershipInactiveLogged = new AtomicBoolean();

  /** 扫描器只负责“安全发现 + 登记”，不绕过 Trigger/Orchestrator 直接起任务。 */
  @Scheduled(fixedDelayString = "${batch.worker.import.scanner.poll-interval-millis:30000}")
  public void scheduledScan() {
    scan();
  }

  /** 扫描入口供定时、事件及测试调用；扫描关闭或未取得租约时返回对应状态。 */
  public ScanResult scan() {
    if (!scannerProperties.isEnabled()) {
      return ScanResult.DISABLED;
    }
    int batchSize = scannerProperties.getBatchSize();
    if (batchSize <= 0) {
      throw new IllegalStateException("导入扫描批次大小必须大于零");
    }
    String scope = scanScope();
    String storageId = scanStorageIdentity();
    String prefix = Objects.requireNonNullElse(scannerProperties.getPrefix(), "");
    if (!scannerProperties
            .getManifestBindings()
            .isEmpty() // empty-check: allow - 保留集合引用为 null 时抛错的原语义
        || !scannerProperties
            .getChannelBindings()
            .isEmpty()) { // empty-check: allow - 保留集合引用为 null 时抛错的原语义
      throw new IllegalStateException("入站静态清单或渠道绑定已停用，请先迁移至共享入口归属声明");
    }
    Optional<ImportIngressOwnershipRepository.Scope> declared =
        scanRepository.findActiveOwnership(scope);
    if (!declared.isPresent()) {
      if (ownershipInactiveLogged.compareAndSet(false, true)) {
        log.warn("入站扫描范围尚无 ACTIVE 归属声明: scopeId={}", scope);
      }
      return ScanResult.OWNERSHIP_INACTIVE;
    }
    ownershipInactiveLogged.set(false);
    EffectiveBindings bindings = validateOwnership(declared.get(), scope, prefix);
    List<ImportIngressOwnershipRepository.Scope> nestedScopes =
        scanRepository.listProtectedOwnership(storageId).stream()
            .filter(other -> other.objectPrefix().length() > prefix.length())
            .filter(other -> other.objectPrefix().startsWith(prefix))
            .toList();
    Optional<Lease> lease = scanRepository.claim(
        storageId,
        scope,
        s3StorageProperties.getBucket(),
        prefix,
        scanConfigurationDigest(bindings),
        scannerProperties.getPollIntervalMillis());
    if (!lease.isPresent()) {
      return ScanResult.LEASE_UNAVAILABLE;
    }
    Lease acquired = lease.get();
    try {
      scanCycle(acquired, batchSize, bindings, nestedScopes);
      return ScanResult.STARTED;
    } finally {
      scanRepository.release(acquired);
    }
  }

  public enum ScanResult {
    STARTED,
    DISABLED,
    OWNERSHIP_INACTIVE,
    LEASE_UNAVAILABLE
  }

  private void scanCycle(
      Lease initial,
      int batchSize,
      EffectiveBindings bindings,
      List<ImportIngressOwnershipRepository.Scope> nestedScopes) {
    Lease current = initial;
    if ("DISCOVER".equals(current.phase())) {
      current = discoverPage(current, batchSize, nestedScopes);
    }
    if (EmptyChecks.isNotNull(current) && "INDEX".equals(current.phase())) {
      current = indexPage(current, batchSize, nestedScopes);
    }
    if (EmptyChecks.isNotNull(current) && "APPLY".equals(current.phase())) {
      current = applyPage(current, batchSize, bindings, nestedScopes);
    }
    if (EmptyChecks.isNotNull(current) && "CLEAN".equals(current.phase())) {
      cleanPage(current, batchSize);
    }
  }

  private void cleanPage(Lease cleanLease, int batchSize) {
    scanRepository.fenced(cleanLease, () -> {
      int deleted = scanRepository.cleanOldObjects(cleanLease, batchSize);
      if (deleted < batchSize) {
        Lease next = cleanLease.toBuilder()
            .phase("DISCOVER")
            .cycle(cleanLease.cycle() + 1)
            .listMarker(null)
            .afterObjectId(0)
            .revisitMarker(null)
            .revisitBudget(1)
            .revisitRemaining(1)
            .nextLane("FORWARD")
            .cleanupRequired(false)
            .build();
        scanRepository.saveProgress(next);
      }
    });
  }

  private Lease discoverPage(
      Lease lease, int batchSize, List<ImportIngressOwnershipRepository.Scope> nestedScopes) {
    boolean revisit = "REVISIT".equals(lease.nextLane());
    String previousMarker = revisit ? lease.revisitMarker() : lease.listMarker();
    ObjectListing listing;
    try {
      listing = objectStore.list(
          s3StorageProperties.getBucket(),
          scannerProperties.getPrefix(),
          previousMarker,
          batchSize);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("failed to scan objectStore ingress objects", ex);
    }
    String marker = listing.nextMarker();
    if (EmptyChecks.isNotNull(marker)
        && (listing.objects().isEmpty() // empty-check: allow - 保留列表为 null 时抛错的原语义
            || marker.equals(previousMarker))) {
      throw new IllegalStateException("对象存储分页未推进，无法继续入站扫描");
    }
    Lease.LeaseBuilder builder =
        lease.toBuilder().phase("INDEX").afterObjectId(0).windowId(lease.windowId() + 1);
    if (revisit) {
      recordRevisitLane(builder, lease, marker);
    } else {
      recordForwardLane(builder, marker);
    }
    Lease next = builder.build();
    boolean updated = scanRepository.fenced(lease, () -> {
      for (ObjectSummary object : listing.objects()) {
        if (isOwnedByNestedScope(nestedScopes, object.key())) {
          continue;
        }
        String suffix = scannerProperties.getBatchManifestSuffix();
        boolean manifest = EmptyChecks.isNotBlank(suffix) && object.key().endsWith(suffix);
        scanRepository.observe(next, sha256(object.key()), object, manifest);
      }
      scanRepository.saveProgress(next);
    });
    return updated ? next : null;
  }

  private Lease indexPage(
      Lease lease, int batchSize, List<ImportIngressOwnershipRepository.Scope> nestedScopes) {
    List<Observed> page = scanRepository.manifestPage(lease, batchSize + 1);
    Lease current = lease;
    int count = Math.min(page.size(), batchSize);
    for (int i = 0; i < count; i++) {
      Observed observed = page.get(i);
      if (isOwnedByNestedScope(nestedScopes, observed.key())) {
        Lease objectLease = current;
        boolean updated = scanRepository.fenced(objectLease, () -> {
          scanRepository.removeObject(objectLease, observed.id());
          scanRepository.saveProgress(
              objectLease.toBuilder().afterObjectId(observed.id()).build());
        });
        if (!updated) {
          return null;
        }
        current = current.toBuilder().afterObjectId(observed.id()).build();
        continue;
      }
      ObjectSummary latest = findExactObject(observed.key());
      BatchManifest manifest = null;
      String readFailure = null;
      boolean shouldRead = sameVersion(observed, latest)
          && stable(observed)
          && scannerProperties.isBatchManifestEnabled();
      if (shouldRead) {
        ManifestRead result = readBatchManifest(observed.key());
        readFailure = result.reason();
        manifest = EmptyChecks.isNull(result.manifest())
            ? null
            : normalizeManifest(result.manifest(), observed.key(), null);
      }
      BatchManifest parsed = manifest;
      String invalidReason =
          EmptyChecks.isNull(readFailure) ? "MANIFEST_IDENTITY_INVALID" : readFailure;
      ObjectSummary verified =
          EmptyChecks.isNull(parsed) ? latest : findExactObject(observed.key());
      Lease objectLease = current;
      AtomicBoolean invalidChanged = new AtomicBoolean();
      IndexDecision decision =
          new IndexDecision(verified, shouldRead, parsed, invalidReason, invalidChanged);
      boolean updated = scanRepository.fenced(objectLease, () -> {
        persistIndexedObject(objectLease, observed, decision);
        scanRepository.saveProgress(
            objectLease.toBuilder().afterObjectId(observed.id()).build());
      });
      if (!updated) {
        return null;
      }
      if (invalidChanged.get()) {
        log.warn(
            "批次清单暂不可用，等待处理: objectDigest={}, reason={}", sha256(observed.key()), invalidReason);
      }
      current = current.toBuilder().afterObjectId(observed.id()).build();
    }
    if (page.size() > batchSize) {
      return null;
    }
    Lease completed = current;
    Lease next = completed.toBuilder().phase("APPLY").afterObjectId(0).build();
    boolean updated = scanRepository.fenced(completed, () -> scanRepository.saveProgress(next));
    return updated ? next : null;
  }

  private void persistIndexedObject(Lease objectLease, Observed observed, IndexDecision decision) {
    if (EmptyChecks.isNull(decision.verified())) {
      scanRepository.removeObject(objectLease, observed.id());
      return;
    }
    if (!sameVersion(observed, decision.verified())) {
      ImportIngressScanRepository repository = scanRepository;
      String objectDigest = sha256(decision.verified().key());
      repository.observe(objectLease, objectDigest, decision.verified(), true);
      return;
    }
    if (decision.shouldRead()) {
      String reason = EmptyChecks.isNull(decision.parsed()) ? decision.invalidReason() : null;
      AtomicBoolean invalidChanged = decision.invalidChanged();
      boolean waitReasonChanged =
          scanRepository.setManifestWaitReason(objectLease, observed.id(), reason);
      invalidChanged.set(waitReasonChanged && EmptyChecks.isNotNull(reason));
    }
    BatchManifest parsed = decision.parsed();
    boolean exportTrigger = EmptyChecks.isNotNull(parsed)
        && scannerProperties.getArrival().isEnabled()
        && parsed.isManifestOnlyExport()
        && Texts.hasText(parsed.fileGroupCode());
    if (exportTrigger) {
      registerExportTrigger(snapshot(observed), parsed);
    }
  }

  private Lease applyPage(
      Lease lease,
      int batchSize,
      EffectiveBindings bindings,
      List<ImportIngressOwnershipRepository.Scope> nestedScopes) {
    Lease current = lease;
    if (current.applyUpperId() == 0) {
      long upper = scanRepository.maxObjectId(current);
      Lease initialized =
          current.toBuilder().applyUpperId(upper).applyAfterId(0).build();
      if (!scanRepository.fenced(current, () -> scanRepository.saveProgress(initialized))) {
        return null;
      }
      current = initialized;
    }
    List<Observed> page = scanRepository.applyPage(current, batchSize + 1);
    int count = Math.min(page.size(), batchSize);
    for (int i = 0; i < count; i++) {
      Observed observed = page.get(i);
      Lease objectLease = current;
      boolean updated;
      if (isOwnedByNestedScope(nestedScopes, observed.key())) {
        updated = scanRepository.fenced(objectLease, () -> {
          scanRepository.removeObject(objectLease, observed.id());
          scanRepository.saveProgress(
              objectLease.toBuilder().applyAfterId(observed.id()).build());
        });
      } else {
        updated = applyObject(objectLease, observed, bindings);
      }
      if (!updated) {
        return null;
      }
      current = current.toBuilder().applyAfterId(observed.id()).build();
    }
    Lease completed = current;
    boolean sweepComplete = page.size() <= batchSize;
    String nextPhase = nextPhaseAfterApply(completed.cleanupRequired(), sweepComplete);
    Lease.LeaseBuilder nextBuilder = completed.toBuilder().phase(nextPhase).afterObjectId(0);
    if (sweepComplete) {
      nextBuilder.applyAfterId(0).applyUpperId(0);
    }
    Lease next = nextBuilder.build();
    boolean updated = scanRepository.fenced(completed, () -> scanRepository.saveProgress(next));
    return updated ? next : null;
  }

  private static void recordRevisitLane(Lease.LeaseBuilder builder, Lease lease, String marker) {
    boolean revisitComplete = EmptyChecks.isNull(marker) || lease.revisitRemaining() <= 1;
    if (revisitComplete) {
      long nextBudget =
          lease.revisitBudget() > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : lease.revisitBudget() * 2;
      builder.revisitMarker(null).revisitBudget(nextBudget).revisitRemaining(nextBudget);
    } else {
      builder.revisitMarker(marker).revisitRemaining(lease.revisitRemaining() - 1);
    }
    builder.nextLane("FORWARD");
  }

  private static void recordForwardLane(Lease.LeaseBuilder builder, String marker) {
    boolean listingFinished = EmptyChecks.isNull(marker);
    builder
        .listMarker(marker)
        .cleanupRequired(listingFinished)
        .nextLane(listingFinished ? "FORWARD" : "REVISIT");
    if (listingFinished) {
      builder.applyAfterId(0).applyUpperId(0);
    }
  }

  private static String nextPhaseAfterApply(boolean cleanupRequired, boolean sweepComplete) {
    if (!cleanupRequired) {
      return "DISCOVER";
    }
    if (sweepComplete) {
      return "CLEAN";
    }
    return "APPLY";
  }

  /** 更具体的入口拥有其数据目录和权威清单，退休后仍保持保护。 */
  private boolean isOwnedByNestedScope(
      List<ImportIngressOwnershipRepository.Scope> nestedScopes, String objectKey) {
    return nestedScopes.stream()
        .anyMatch(scope -> scope.protects(s3StorageProperties.getBucket(), objectKey));
  }

  private boolean applyObject(Lease lease, Observed observed, EffectiveBindings bindings) {
    PreparedObject prepared = prepareObject(lease, observed, bindings);
    boolean committed = scanRepository.fenced(
        lease, () -> commitPreparedObject(lease, observed, prepared, bindings));
    if (committed && EmptyChecks.isNotNull(prepared.changedWaitReason().get())) {
      log.warn(
          "导入扫描等待权威批次清单: tenantId={}, bizDate={}, objectDigest={}, reason={}",
          prepared.resolvedTenant(),
          prepared.fileDate(),
          sha256(observed.key()),
          prepared.changedWaitReason().get());
    }
    return committed;
  }

  /** 登记前按原顺序完成观察。存在性查询仍放在短路链内，提交阶段只消费这里的结果。 */
  private PreparedObject prepareObject(Lease lease, Observed observed, EffectiveBindings bindings) {
    String manifestSuffix = scannerProperties.getBatchManifestSuffix();
    boolean batchManifestObject =
        EmptyChecks.isNotBlank(manifestSuffix) && observed.key().endsWith(manifestSuffix);
    boolean protocolObject =
        observed.key().endsWith(scannerProperties.getDoneFileSuffix()) || batchManifestObject;
    ObjectSummary latest = findExactObject(observed.key());
    boolean unchanged = EmptyChecks.isNotNull(latest) && sameVersion(observed, latest);
    boolean eligible = !protocolObject && unchanged && stable(observed);
    String tenant = resolveTenantFromObjectName(observed.key());
    if (!Texts.hasText(tenant)) {
      tenant = workerConfiguration.tenantId();
    }
    String resolvedTenant = tenant;
    LocalDate fileDate = eligible ? resolveScannerBizDate(observed.key()) : null;
    ManifestResolution resolution = eligible && scannerProperties.isBatchManifestEnabled()
        ? resolveAuthoritativeManifest(lease, resolvedTenant, fileDate, observed.key(), bindings)
        : new ManifestResolution(null, null, null, null, null);
    BatchManifest batchManifest = resolution.manifest();
    String marker =
        scannerProperties.isRequireDoneFile() ? resolveDoneMarker(observed.key()) : null;
    boolean done = !scannerProperties.isRequireDoneFile()
        || (eligible && objectStore.exists(s3StorageProperties.getBucket(), marker));
    boolean alreadyRegistered = eligible
        && done
        && Texts.hasText(resolvedTenant)
        && fileRecords.existsFileRecordByStoragePath(
            resolvedTenant, s3StorageProperties.getBucket(), observed.key());
    Map<String, Object> existingRecord = alreadyRegistered
        ? fileRecords.loadFileRecordByStoragePath(
            resolvedTenant, s3StorageProperties.getBucket(), observed.key())
        : Map.of();
    boolean reconciled = isReconciledFile(existingRecord);
    boolean scannerAccepted = reconciled && isScannerAcceptedFile(existingRecord);
    boolean manifestReady =
        !scannerProperties.isBatchManifestEnabled() || EmptyChecks.isNotNull(batchManifest);
    boolean sidecarRequired = eligible
        && done
        && manifestReady
        && (!alreadyRegistered || (reconciled && !scannerAccepted))
        && isManifestMode()
        && scannerProperties.isRequireDoneFile();
    SidecarManifest sidecar =
        sidecarRequired ? readAndVerifyManifest(marker, snapshot(observed), lease) : null;
    ObjectSummary verified = eligible && done ? findExactObject(observed.key()) : latest;
    boolean verifiedVersion = EmptyChecks.isNotNull(verified) && sameVersion(observed, verified);
    ObjectSummary verifiedAuthority = EmptyChecks.isNull(resolution.manifest())
        ? resolution.snapshot()
        : findExactObject(resolution.key());
    boolean verifiedManifest = EmptyChecks.isNull(resolution.manifest())
        || sameVersion(resolution.snapshot(), verifiedAuthority);
    boolean verifiedDone = done
        && (!scannerProperties.isRequireDoneFile()
            || objectStore.exists(s3StorageProperties.getBucket(), marker));
    return new PreparedObject(
        batchManifestObject,
        resolvedTenant,
        fileDate,
        resolution,
        eligible,
        verified,
        verifiedVersion,
        verifiedAuthority,
        verifiedManifest,
        verifiedDone,
        batchManifest,
        sidecar,
        new AtomicReference<>());
  }

  private void commitPreparedObject(
      Lease lease, Observed observed, PreparedObject prepared, EffectiveBindings bindings) {
    ManifestResolution resolution = prepared.resolution();
    ObjectSummary verifiedAuthority = prepared.verifiedAuthority();
    boolean authorityDisappeared = EmptyChecks.isNotNull(resolution.key())
        && EmptyChecks.isNull(verifiedAuthority)
        && EmptyChecks.isNotNull(resolution.previousId());
    if (authorityDisappeared) {
      scanRepository.removeObject(lease, resolution.previousId());
    } else if (EmptyChecks.isNotNull(verifiedAuthority)) {
      ImportIngressScanRepository repository = scanRepository;
      String authorityDigest = sha256(verifiedAuthority.key());
      repository.observe(lease, authorityDigest, verifiedAuthority, true);
    }
    if (prepared.eligible() && scannerProperties.isBatchManifestEnabled()) {
      String reason = prepared.verifiedManifest() ? resolution.waitReason() : "MANIFEST_CHANGED";
      boolean changed = scanRepository.setManifestWaitReason(lease, observed.id(), reason);
      if (changed && EmptyChecks.isNotNull(reason)) {
        prepared.changedWaitReason().set(reason);
      }
    }
    ObjectSummary verified = prepared.verified();
    if (EmptyChecks.isNull(verified)) {
      scanRepository.removeObject(lease, observed.id());
    } else if (!prepared.verifiedVersion()) {
      ImportIngressScanRepository repository = scanRepository;
      String objectDigest = sha256(verified.key());
      repository.observe(lease, objectDigest, verified, prepared.batchManifestObject());
    } else {
      boolean readyToRegister = prepared.eligible()
          && prepared.verifiedDone()
          && prepared.verifiedManifest()
          && prepared.verifiedVersion()
          && (!scannerProperties.isBatchManifestEnabled()
              || EmptyChecks.isNotNull(prepared.batchManifest()));
      if (readyToRegister) {
        List<BatchManifest> manifests = EmptyChecks.isNull(prepared.batchManifest())
            ? List.of()
            : List.of(prepared.batchManifest());
        tryRegisterPrepared(snapshot(observed), manifests, prepared.sidecar(), bindings);
      }
    }
    scanRepository.saveProgress(lease.toBuilder().applyAfterId(observed.id()).build());
  }

  private ManifestResolution resolveAuthoritativeManifest(
      Lease lease,
      String tenant,
      LocalDate bizDate,
      String objectName,
      EffectiveBindings bindings) {
    if (!Texts.hasText(tenant) || EmptyChecks.isNull(bizDate)) {
      return new ManifestResolution(null, "IDENTITY_UNRESOLVED", null, null, null);
    }
    Optional<ImportScannerProperties.ManifestBinding> binding = bindings.manifests().stream()
        .filter(rule ->
            tenant.equals(rule.getTenantId()) && objectName.startsWith(rule.getObjectPrefix()))
        .max(Comparator.comparingInt(rule -> rule.getObjectPrefix().length()));
    if (!binding.isPresent()) {
      return new ManifestResolution(null, "BINDING_MISSING", null, null, null);
    }
    ImportScannerProperties.ManifestBinding rule = binding.get();
    String key = rule.getManifestKeyTemplate()
        .replace("{bizDate}", bizDate.toString())
        .replace("{bizDateCompact}", bizDate.format(DateTimeFormatter.BASIC_ISO_DATE));
    ObjectSummary latest = findExactObject(key);
    Optional<Observed> previous = scanRepository.findObject(lease, sha256(key));
    if (previous.isPresent() && !key.equals(previous.get().key())) {
      throw new IllegalStateException("扫描清单摘要冲突，无法安全匹配对象");
    }
    Long previousId = previous.map(Observed::id).orElse(null);
    if (EmptyChecks.isNull(latest)) {
      return new ManifestResolution(null, "MANIFEST_MISSING", key, null, previousId);
    }
    if (scannerProperties.getStabilityWindowSeconds() > 0
        && (!previous.isPresent()
            || !sameVersion(previous.get(), latest)
            || !stable(previous.get()))) {
      return new ManifestResolution(null, "MANIFEST_UNSTABLE", key, latest, previousId);
    }
    ManifestRead result = readBatchManifest(key);
    if (EmptyChecks.isNull(result.manifest())) {
      return new ManifestResolution(null, result.reason(), key, latest, previousId);
    }
    BatchManifest manifest = normalizeManifest(result.manifest(), key, tenant);
    boolean matches = EmptyChecks.isNotNull(manifest)
        && tenant.equals(manifest.tenantId())
        && bizDate.toString().equals(manifest.bizDate())
        && rule.getFileGroupCode().equals(manifest.fileGroupCode())
        && EmptyChecks.isNotNull(manifest.requiredFiles())
        && manifest.requiredFiles().contains(baseName(objectName));
    return matches
        ? new ManifestResolution(manifest, null, key, latest, previousId)
        : new ManifestResolution(null, "MANIFEST_MISMATCH", key, latest, previousId);
  }

  private boolean sameVersion(ObjectSummary first, ObjectSummary second) {
    return EmptyChecks.isNotNull(first)
        && EmptyChecks.isNotNull(second)
        && first.size() == second.size()
        && Objects.equals(first.etag(), second.etag());
  }

  private record IndexDecision(
      ObjectSummary verified,
      boolean shouldRead,
      BatchManifest parsed,
      String invalidReason,
      AtomicBoolean invalidChanged) {}

  /** 一次对象登记前的观察结果。存在性与清单读取已在准备阶段按原短路完成。 */
  private record PreparedObject(
      boolean batchManifestObject,
      String resolvedTenant,
      LocalDate fileDate,
      ManifestResolution resolution,
      boolean eligible,
      ObjectSummary verified,
      boolean verifiedVersion,
      ObjectSummary verifiedAuthority,
      boolean verifiedManifest,
      boolean verifiedDone,
      BatchManifest batchManifest,
      SidecarManifest sidecar,
      AtomicReference<String> changedWaitReason) {}

  private record ManifestResolution(
      BatchManifest manifest,
      String waitReason,
      String key,
      ObjectSummary snapshot,
      Long previousId) {}

  private BatchManifest normalizeManifest(
      BatchManifest manifest, String objectName, String trustedTenant) {
    boolean manifestOnlyExport = !Texts.hasText(trustedTenant) && manifest.isManifestOnlyExport();
    String tenant =
        Texts.hasText(trustedTenant) ? trustedTenant : resolveTenantFromObjectName(objectName);
    if (!Texts.hasText(tenant)) {
      tenant = manifestOnlyExport && Texts.hasText(manifest.tenantId())
          ? manifest.tenantId()
          : workerConfiguration.tenantId();
    }
    if (!Texts.hasText(tenant)
        || (Texts.hasText(manifest.tenantId()) && !tenant.equals(manifest.tenantId()))) {
      return null;
    }
    LocalDate date;
    if (Texts.hasText(manifest.bizDate())) {
      date = parseManifestBizDate(manifest.bizDate());
      if (EmptyChecks.isNull(date) && !manifestOnlyExport) {
        return null;
      }
    } else {
      date = null;
    }
    if (EmptyChecks.isNull(date)) {
      date = resolveScannerBizDate(objectName);
    }
    if (EmptyChecks.isNull(date)) {
      return null;
    }
    ObjectNode normalized = objectMapper.valueToTree(manifest);
    normalized.put("tenantId", tenant);
    normalized.put("bizDate", date.toString());
    return objectMapper.convertValue(normalized, BatchManifest.class);
  }

  private ObjectSummary findExactObject(String key) {
    ObjectListing listing = objectStore.list(s3StorageProperties.getBucket(), key, null, 1);
    return listing.objects().stream()
        .filter(object -> key.equals(object.key()))
        .findFirst()
        .orElse(null);
  }

  private boolean stable(Observed object) {
    long seconds = scannerProperties.getStabilityWindowSeconds();
    return seconds <= 0
        || object.firstObservedAt().plusSeconds(seconds).isBefore(object.databaseNow());
  }

  private boolean sameVersion(Observed observed, ObjectSummary latest) {
    return EmptyChecks.isNotNull(latest)
        && observed.size() == latest.size()
        && Objects.equals(observed.etag(), latest.etag());
  }

  private ObjectSnapshot snapshot(Observed observed) {
    return new ObjectSnapshot(
        observed.key(), observed.size(), observed.etag(), observed.lastModified());
  }

  String scanScope() {
    return ImportIngressOwnershipIdentity.scopeId(
        scanStorageIdentity(), scannerProperties.getPrefix(), objectMapper);
  }

  String scanStorageIdentity() {
    return ownershipIdentity.storageId(s3StorageProperties, objectMapper);
  }

  String scanConfigurationDigest() {
    String scopeId = scanScope();
    String prefix = Objects.requireNonNullElse(scannerProperties.getPrefix(), "");
    ImportIngressOwnershipRepository.Scope scope = scanRepository
        .findActiveOwnership(scopeId)
        .orElseThrow(() -> new IllegalStateException("入站扫描范围尚无 ACTIVE 归属声明"));
    return scanConfigurationDigest(validateOwnership(scope, scopeId, prefix));
  }

  private String scanConfigurationDigest(EffectiveBindings effective) {
    Map<String, Object> rules = new TreeMap<>();
    rules.put("fallbackTenant", workerConfiguration.tenantId());
    rules.put("requireDoneFile", scannerProperties.isRequireDoneFile());
    rules.put("doneFileSuffix", scannerProperties.getDoneFileSuffix());
    rules.put("doneFileNaming", scannerProperties.getDoneFileNaming());
    rules.put("doneFileFormat", scannerProperties.getDoneFileFormat());
    rules.put("batchManifestEnabled", scannerProperties.isBatchManifestEnabled());
    rules.put("batchManifestSuffix", scannerProperties.getBatchManifestSuffix());
    rules.put("stabilityWindowSeconds", scannerProperties.getStabilityWindowSeconds());
    rules.put("sourceType", scannerProperties.getSourceType());
    rules.put("defaultBizType", scannerProperties.getDefaultBizType());
    rules.put("bizDatePattern", scannerProperties.getBizDatePattern());
    rules.put("defaultBizDate", scannerProperties.getDefaultBizDate());
    ImportScannerProperties.Arrival arrival = scannerProperties.getArrival();
    Map<String, Object> arrivalRules = new TreeMap<>();
    arrivalRules.put("enabled", arrival.isEnabled());
    arrivalRules.put("fileGroupCode", arrival.getFileGroupCode());
    arrivalRules.put("waitFileGroupMode", arrival.getWaitFileGroupMode());
    arrivalRules.put(KEY_REQUIRED_FILE_SET, arrival.getRequiredFileSet());
    arrivalRules.put("arrivalTimeoutAction", arrival.getArrivalTimeoutAction());
    arrivalRules.put("expectedArrivalDelaySeconds", arrival.getExpectedArrivalDelaySeconds());
    arrivalRules.put("latestTolerableDelaySeconds", arrival.getLatestTolerableDelaySeconds());
    arrivalRules.put("triggerOnComplete", arrival.isTriggerOnComplete());
    arrivalRules.put("allowEmptyRun", arrival.isAllowEmptyRun());
    arrivalRules.put("allowSkipBizDate", arrival.isAllowSkipBizDate());
    arrivalRules.put("notifyManual", arrival.isNotifyManual());
    arrivalRules.put("notifyChannels", arrival.getNotifyChannels());
    rules.put("arrival", arrivalRules);
    List<ImportScannerProperties.ChannelBinding> bindings = effective.channels().stream()
        .sorted(Comparator.comparing(ImportScannerProperties.ChannelBinding::getTenantId)
            .thenComparing(ImportScannerProperties.ChannelBinding::getObjectPrefix)
            .thenComparing(ImportScannerProperties.ChannelBinding::getChannelCode))
        .toList();
    rules.put("channelBindings", bindings);
    List<ImportScannerProperties.ManifestBinding> manifestBindings = effective.manifests().stream()
        .sorted(Comparator.comparing(ImportScannerProperties.ManifestBinding::getTenantId)
            .thenComparing(ImportScannerProperties.ManifestBinding::getObjectPrefix)
            .thenComparing(ImportScannerProperties.ManifestBinding::getFileGroupCode)
            .thenComparing(ImportScannerProperties.ManifestBinding::getManifestKeyTemplate))
        .toList();
    rules.put("manifestBindings", manifestBindings);
    return sha256(writeJson(rules));
  }

  private record EffectiveBindings(
      List<ImportScannerProperties.ManifestBinding> manifests,
      List<ImportScannerProperties.ChannelBinding> channels) {}

  /** 共享声明使用原配置相同规则校验；无声明时不得用本地绑定回退。 */
  private EffectiveBindings validateOwnership(
      ImportIngressOwnershipRepository.Scope scope, String expectedScope, String prefix) {
    if (!expectedScope.equals(scope.scopeId())
        || !scanStorageIdentity().equals(scope.storageId())
        || !Objects.equals(s3StorageProperties.getBucket(), scope.bucketName())
        || !prefix.equals(scope.objectPrefix())
        || !"ACTIVE".equals(scope.state())) {
      throw new IllegalStateException("入站归属声明与当前存储、桶或扫描前缀不一致");
    }
    List<ImportScannerProperties.ManifestBinding> manifests = new ArrayList<>();
    List<ImportScannerProperties.ChannelBinding> channels = new ArrayList<>();
    Set<List<String>> seen = new HashSet<>();
    for (ImportIngressOwnershipRepository.Binding binding : scope.bindings()) {
      String tenant = binding.tenantId();
      String directory = binding.objectPrefix();
      if (!binding.enabled()
          || !Texts.hasText(tenant)
          || !Texts.hasText(directory)
          || !directory.matches(".+/")
          || !directory.startsWith(prefix)
          || !seen.add(List.of(binding.type(), tenant, directory))) {
        throw new IllegalStateException("入站归属声明中的租户、目录或绑定重复无效");
      }
      if ("MANIFEST".equals(binding.type())) {
        ImportScannerProperties.ManifestBinding value =
            new ImportScannerProperties.ManifestBinding();
        value.setTenantId(tenant);
        value.setObjectPrefix(directory);
        value.setFileGroupCode(binding.fileGroupCode());
        value.setManifestKeyTemplate(binding.manifestKeyTemplate());
        if (!validResourceCode(value.getFileGroupCode())
            || !value.isManifestKeyTemplateValid()
            || !Texts.hasText(scannerProperties.getBatchManifestSuffix())
            || !value
                .getManifestKeyTemplate()
                .endsWith(scannerProperties.getBatchManifestSuffix())) {
          throw new IllegalStateException("入站归属声明中的权威清单绑定无效");
        }
        manifests.add(value);
      } else if ("CHANNEL".equals(binding.type())) {
        if (!validResourceCode(binding.channelCode())) {
          throw new IllegalStateException("入站归属声明中的渠道编码无效");
        }
        ImportScannerProperties.ChannelBinding value = new ImportScannerProperties.ChannelBinding();
        value.setTenantId(tenant);
        value.setObjectPrefix(directory);
        value.setChannelCode(binding.channelCode());
        channels.add(value);
      } else {
        throw new IllegalStateException("入站归属声明中的绑定类型无效");
      }
    }
    return new EffectiveBindings(List.copyOf(manifests), List.copyOf(channels));
  }

  private boolean validResourceCode(String value) {
    return EmptyChecks.isNotNull(value) && value.matches(ValidResourceCode.REGEX);
  }

  private String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("无法计算扫描身份摘要", ex);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (IOException ex) {
      throw new IllegalStateException("无法编码入站扫描状态", ex);
    }
  }

  private void registerExportTrigger(ObjectSnapshot snapshot, BatchManifest manifest) {
    String resolvedTenant = resolveTenantFromObjectName(snapshot.objectName());
    if (!Texts.hasText(resolvedTenant)) {
      resolvedTenant =
          Texts.hasText(manifest.tenantId()) ? manifest.tenantId() : workerConfiguration.tenantId();
    }
    if (!Texts.hasText(resolvedTenant)) {
      return;
    }
    // 幂等:trigger 记录以清单对象路径为 storage_path,已登记则跳过 → 同一导出清单只发射一次
    boolean fileRecordAlreadyExists = fileRecords.existsFileRecordByStoragePath(
        resolvedTenant, s3StorageProperties.getBucket(), snapshot.objectName());
    if (fileRecordAlreadyExists) {
      return;
    }
    LocalDate bizDate = parseManifestBizDate(manifest.bizDate());
    if (bizDate == null) {
      bizDate = resolveScannerBizDate(snapshot.objectName());
    }
    if (bizDate == null) {
      return;
    }
    String fileName = baseName(snapshot.objectName());
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("scanner", "objectStore-export-trigger");
    Instant detectedAt = BatchDateTimeSupport.utcNow();
    String detectedAtText = detectedAt.toString();
    metadata.put("detectedAt", detectedAtText);
    metadata.put("bundleWorkflowCode", manifest.workflowCode());
    metadata.put("bundleExportTemplates", manifest.exportTemplateCodes());
    // 自完整到达组:requiredFileSet = 本 trigger 记录自身文件名 → 组(大小 1)立即满足条件触发
    putArrivalMetadata(metadata, manifest.fileGroupCode(), fileName);
    FileRecordParamBuilder exportTriggerFileRecordBuilder = FileRecordParam.builder()
        .tenantId(resolvedTenant)
        .fileCode(null)
        .bizType(scannerProperties.getDefaultBizType())
        .fileCategory("EXPORT_TRIGGER")
        .fileName(fileName)
        .originalFileName(fileName);
    String exportTriggerFileFormatType = resolveFileFormatType(fileName);
    exportTriggerFileRecordBuilder
        .fileFormatType(exportTriggerFileFormatType)
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
        .fileStatus(FileStatus.RECEIVED.code());
    String exportTriggerTraceId = "export-trigger-" + sanitizeTrace(fileName);
    FileRecordParam exportTriggerFileRecord = exportTriggerFileRecordBuilder
        .traceId(exportTriggerTraceId)
        .metadata(metadata)
        .build();
    fileRecords.createFileRecord(exportTriggerFileRecord);
    log.info(
        "export bundle trigger registered: tenant={}, group={}, workflowCode={}, templateCount={},"
            + " bizDate={}",
        resolvedTenant,
        manifest.fileGroupCode(),
        manifest.workflowCode(),
        manifest.exportTemplateCodes().size(),
        bizDate);
  }

  private LocalDate parseManifestBizDate(String bizDate) {
    if (!Texts.hasText(bizDate)) {
      return null;
    }
    try {
      String normalizedBizDate = bizDate.trim();
      return LocalDate.parse(normalizedBizDate);
    } catch (RuntimeException ignored) {
      return null;
    }
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
    String key = Texts.hasText(prefix) && objectName.startsWith(prefix)
        ? objectName.substring(prefix.length())
        : objectName;
    int slash = key.indexOf('/');
    if (slash <= 0) {
      return "";
    }
    return key.substring(0, slash);
  }

  private void tryRegisterPrepared(
      ObjectSnapshot snapshot,
      List<BatchManifest> batchManifests,
      SidecarManifest manifest,
      EffectiveBindings bindings) {
    if (EmptyChecks.isNull(snapshot)) {
      return;
    }
    boolean isDoneFileObject =
        snapshot.objectName().endsWith(scannerProperties.getDoneFileSuffix());
    if (isDoneFileObject) {
      return;
    }
    String manifestSuffix = scannerProperties.getBatchManifestSuffix();
    if (EmptyChecks.isNotBlank(manifestSuffix) && snapshot.objectName().endsWith(manifestSuffix)) {
      return; // 协议清单归属不随处理开关改变，关闭时也不能落入普通数据入口。
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
    if (fileRecords.existsFileRecordByStoragePath(
        resolvedTenant, s3StorageProperties.getBucket(), snapshot.objectName())) {
      Map<String, Object> fileRecord = fileRecords.loadFileRecordByStoragePath(
          resolvedTenant, s3StorageProperties.getBucket(), snapshot.objectName());
      if (isReconciledFile(fileRecord)) {
        if (scannerProperties.isRequireDoneFile()
            && isManifestMode()
            && EmptyChecks.isNull(manifest)
            && !isScannerAcceptedFile(fileRecord)) {
          return;
        }
        adoptReconciledFile(
            snapshot, resolvedTenant, manifest, batchManifests, fileRecord, bindings);
        return;
      }
      // ADR-040 Phase2:数据文件先到、清单后到 —— 对已登记成员回填 required_file_set(幂等)
      backfillBatchManifestArrival(snapshot, resolvedTenant, batchManifests);
      return;
    }
    if (scannerProperties.isRequireDoneFile() && isManifestMode() && EmptyChecks.isNull(manifest)) {
      return;
    }
    String fileName = baseName(snapshot.objectName());
    registerDiscoveredInputFile(
        snapshot, resolvedTenant, manifest, batchManifests, fileName, bindings);
  }

  private boolean isReconciledFile(Map<String, Object> fileRecord) {
    return EmptyChecks.isNotNull(fileRecord)
        && Boolean.TRUE.equals(parseRecordMetadata(fileRecord).get("reconciled"));
  }

  private boolean isScannerAcceptedFile(Map<String, Object> fileRecord) {
    return Boolean.TRUE.equals(parseRecordMetadata(fileRecord).get("scannerAccepted"));
  }

  /** 仅在扫描准入完成后补全同一对象的可信身份，不改写既有字段。 */
  private void adoptReconciledFile(
      ObjectSnapshot snapshot,
      String tenantId,
      SidecarManifest sidecar,
      List<BatchManifest> batchManifests,
      Map<String, Object> fileRecord,
      EffectiveBindings bindings) {
    Long fileId = PlatformRuntimeValues.toLong(fileRecord.get("id"));
    LocalDate bizDate = resolveScannerBizDate(snapshot.objectName());
    if (EmptyChecks.isNull(fileId) || EmptyChecks.isNull(bizDate)) {
      return;
    }
    String fileName = baseName(snapshot.objectName());
    BatchManifest matched = matchBatchManifest(batchManifests, tenantId, fileName);
    String group = EmptyChecks.isNull(matched)
        ? scannerProperties.getArrival().getFileGroupCode()
        : matched.fileGroupCode();
    String requiredFiles = EmptyChecks.isNull(matched)
        ? scannerProperties.getArrival().getRequiredFileSet()
        : String.join(",", matched.requiredFiles());
    Map<String, Object> existing = parseRecordMetadata(fileRecord);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("scannerAccepted", true);
    if (scannerProperties.getArrival().isEnabled()
        && Texts.hasText(group)
        && Texts.hasText(requiredFiles)) {
      putArrivalMetadata(metadata, group, requiredFiles);
    }
    if (EmptyChecks.isNotNull(sidecar)) {
      putVerifiedManifestMetadata(metadata, sidecar);
    }
    putBundleMetadata(metadata, matched, fileName);
    for (String key :
        List.of("fileGroupCode", "bundleWorkflowCode", "bundleTemplateCode", "bundleTargetRef")) {
      Object current = existing.get(key);
      Object proposed = metadata.get(key);
      if (EmptyChecks.isNotNull(current)
          && EmptyChecks.isNotNull(proposed)
          && !Objects.equals(current, proposed)) {
        log.warn("对账文件身份与扫描配置不一致，保留原记录: tenantId={}, fileId={}, field={}", tenantId, fileId, key);
        return;
      }
    }
    Map<String, Object> auditMetadata = new LinkedHashMap<>(metadata);
    metadata.keySet().removeAll(existing.keySet());
    String checksumType = EmptyChecks.isNull(sidecar) ? null : sidecar.checksumType();
    String checksumValue = EmptyChecks.isNull(sidecar) ? null : sidecar.checksumValue();
    String arrivalChannel = resolveArrivalChannel(tenantId, snapshot.objectName(), false, bindings);
    PlatformReconciledFileAdoptionParam adoption = new PlatformReconciledFileAdoptionParam(
        fileId,
        tenantId,
        s3StorageProperties.getBucket(),
        snapshot.objectName(),
        bizDate,
        scannerProperties.getDefaultBizType(),
        arrivalChannel,
        snapshot.size(),
        snapshot.etag(),
        checksumType,
        checksumValue,
        writeJson(metadata));
    if (fileRecords.adoptReconciledFileRecord(adoption)) {
      if (!Boolean.TRUE.equals(existing.get("scannerAccepted"))) {
        appendImportAudits(snapshot, tenantId, fileId, auditMetadata);
      }
      log.info("扫描已接管对账文件身份: tenantId={}, fileId={}", tenantId, fileId);
      if (EmptyChecks.isNull(arrivalChannel)) {
        log.warn("导入扫描对象未配置可信渠道绑定: tenantId={}", tenantId);
      }
    }
  }

  /** 准入与幂等判断完成后才生成本次元数据，并按文件登记、到达审计、扫描审计的顺序持久化。 */
  private void registerDiscoveredInputFile(
      ObjectSnapshot snapshot,
      String resolvedTenant,
      SidecarManifest manifest,
      List<BatchManifest> batchManifests,
      String fileName,
      EffectiveBindings bindings) {
    LocalDate bizDate = resolveScannerBizDate(snapshot.objectName());
    if (EmptyChecks.isNull(bizDate)) {
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
    Instant detectedAt = BatchDateTimeSupport.utcNow();
    String detectedAtText = detectedAt.toString();
    metadata.put("detectedAt", detectedAtText);
    boolean arrivalGroupCanBeRecorded = scannerProperties.getArrival().isEnabled()
        && Texts.hasText(effectiveGroupCode)
        && Texts.hasText(effectiveRequiredFileSet);
    if (arrivalGroupCanBeRecorded) {
      putArrivalMetadata(metadata, effectiveGroupCode, effectiveRequiredFileSet);
    }
    if (manifest != null) {
      putVerifiedManifestMetadata(metadata, manifest);
    }
    // ADR-046:批次清单 v2 落 metadata 供束 launch 读取——
    //   ① fileMapping 声明本文件绑定:导入束给 templateCode(目标表从模板推),分发束给 targetRef(下游渠道);
    //   ② workflowCode 声明本束满足条件后启动哪个 Workflow(到达组满足条件时 BundleArrivalLauncher 据此发起根运行)。
    if (matchedBatch != null) {
      putBundleMetadata(metadata, matchedBatch, fileName);
    }
    FileRecordParamBuilder inputFileRecordBuilder = FileRecordParam.builder()
        .tenantId(resolvedTenant)
        .arrivalChannelCode(
            resolveArrivalChannel(resolvedTenant, snapshot.objectName(), true, bindings))
        .fileCode(null)
        .bizType(scannerProperties.getDefaultBizType())
        .fileCategory("INPUT")
        .fileName(fileName)
        .originalFileName(fileName);
    String inputFileFormatType = resolveFileFormatType(fileName);
    inputFileRecordBuilder
        .fileFormatType(inputFileFormatType)
        .charset(StandardCharsets.UTF_8.name())
        .fileSizeBytes(snapshot.size());
    String inputChecksumType =
        EmptyChecks.isNotNull(manifest) && Texts.hasText(manifest.checksumType())
            ? manifest.checksumType()
            : "NONE";
    inputFileRecordBuilder
        .checksumType(inputChecksumType)
        .checksumValue(manifest == null ? null : manifest.checksumValue())
        .storageType("S3")
        .storagePath(snapshot.objectName())
        .storageBucket(s3StorageProperties.getBucket())
        .fileVersion(null)
        .bizDate(bizDate)
        .sourceType(scannerProperties.getSourceType())
        .sourceRef(snapshot.objectName())
        .fileStatus(FileStatus.RECEIVED.code());
    String inputTraceId = "import-scan-" + sanitizeTrace(fileName);
    FileRecordParam inputFileRecord =
        inputFileRecordBuilder.traceId(inputTraceId).metadata(metadata).build();
    Long fileId = fileRecords.createFileRecord(inputFileRecord);
    appendImportAudits(snapshot, resolvedTenant, fileId, metadata);
    log.info(
        "import file registered by scanner: tenantId={}, fileId={}, objectName={}",
        resolvedTenant,
        fileId,
        snapshot.objectName());
  }

  /** 统一记录到达组与扫描登记审计，并与文件身份更新处于同一事务。 */
  private void appendImportAudits(
      ObjectSnapshot snapshot, String tenantId, Long fileId, Map<String, Object> metadata) {
    String fileName = baseName(snapshot.objectName());
    String group = (String) metadata.get("fileGroupCode");
    String requiredFiles = (String) metadata.get(KEY_REQUIRED_FILE_SET);
    boolean arrivalGroupAuditCanBeRecorded = scannerProperties.getArrival().isEnabled()
        && Texts.hasText(group)
        && Texts.hasText(requiredFiles);
    if (arrivalGroupAuditCanBeRecorded) {
      FileAuditParamBuilder arrivalAuditBuilder = FileAuditParam.builder()
          .fileId(fileId)
          .tenantId(tenantId)
          .operationType(FileAuditOperationType.ARRIVAL_REGISTER.code())
          .operationResult(OperationResult.SUCCESS.code())
          .operatorType(AuditLogConstants.OPERATOR_TYPE_SYSTEM)
          .operatorId("import-ingress-scanner");
      String arrivalAuditTraceId = "arrival-" + sanitizeTrace(fileName);
      FileAuditParam arrivalAudit = arrivalAuditBuilder
          .traceId(arrivalAuditTraceId)
          .evidenceRef(snapshot.objectName())
          .detailSummary(Map.of(
              "fileGroupCode",
              group,
              KEY_REQUIRED_FILE_SET,
              requiredFiles,
              "arrivalState",
              "WAITING_ARRIVAL"))
          .build();
      fileAudits.appendAudit(arrivalAudit);
    }
    FileAuditParamBuilder scanAuditBuilder = FileAuditParam.builder()
        .fileId(fileId)
        .tenantId(tenantId)
        .operationType(FileAuditOperationType.RECEIVE_SCAN.code())
        .operationResult(OperationResult.SUCCESS.code())
        .operatorType(AuditLogConstants.OPERATOR_TYPE_SYSTEM)
        .operatorId("import-ingress-scanner");
    String scanAuditTraceId = "import-scan-" + sanitizeTrace(fileName);
    FileAuditParam scanAudit = scanAuditBuilder
        .traceId(scanAuditTraceId)
        .evidenceRef(snapshot.objectName())
        .detailSummary(metadata)
        .build();
    fileAudits.appendAudit(scanAudit);
  }

  private String resolveArrivalChannel(
      String tenant, String objectName, boolean warnIfMissing, EffectiveBindings bindings) {
    String channel = bindings.channels().stream()
        .filter(binding -> EmptyChecks.isNotNull(binding)
            && tenant.equals(binding.getTenantId())
            && EmptyChecks.isNotNull(binding.getObjectPrefix())
            && objectName.startsWith(binding.getObjectPrefix()))
        .max(Comparator.comparingInt(binding -> binding.getObjectPrefix().length()))
        .map(ImportScannerProperties.ChannelBinding::getChannelCode)
        .orElse(null);
    if (EmptyChecks.isNull(channel) && warnIfMissing) {
      log.warn("导入扫描对象未配置可信渠道绑定: tenantId={}", tenant);
    }
    return channel;
  }

  private void putVerifiedManifestMetadata(Map<String, Object> metadata, SidecarManifest manifest) {
    metadata.put("manifestSchemaVersion", manifest.schemaVersion());
    metadata.put(
        "manifestChecksumScope",
        Texts.hasText(manifest.checksumScope()) ? manifest.checksumScope() : "RAW_OBJECT");
    metadata.put("integrityState", FileIntegrityState.VERIFIED.code());
    Instant integrityVerifiedAt = BatchDateTimeSupport.utcNow();
    String integrityVerifiedAtText = integrityVerifiedAt.toString();
    metadata.put("integrityVerifiedAt", integrityVerifiedAtText);
    if (EmptyChecks.isNotNull(manifest.recordCount())) {
      metadata.put("expectedRecordCount", manifest.recordCount());
    }
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
      String defaultBizDate = scannerProperties.getDefaultBizDate();
      String normalizedDefaultBizDate = defaultBizDate.trim();
      return LocalDate.parse(normalizedDefaultBizDate);
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
    return scannerProperties.getDoneFileFormat().isManifest();
  }

  /** 读 .chk JSON manifest 并验证原始对象字节;失败/不符返回 null。 */
  private SidecarManifest readAndVerifyManifest(
      String markerName, ObjectSnapshot snapshot, Lease lease) {
    try (InputStream in = objectStore.get(s3StorageProperties.getBucket(), markerName)) {
      byte[] bytes = in.readNBytes((int) MAX_MANIFEST_BYTES + 1);
      if (bytes.length > MAX_MANIFEST_BYTES) {
        log.warn("sidecar manifest too large, skip register: marker={}", markerName);
        return null;
      }
      SidecarManifest manifest = objectMapper.readValue(bytes, SidecarManifest.class);
      if (!isSupportedManifest(manifest, snapshot)) {
        log.warn("sidecar manifest fields invalid, skip register: marker={}", markerName);
        return null;
      }
      if (manifest.sizeBytes() != snapshot.size()) {
        log.warn(
            "manifest size mismatch, skip register: object={}, manifestSize={}, actualSize={}",
            snapshot.objectName(),
            manifest.sizeBytes(),
            snapshot.size());
        return null;
      }
      if (!verifyObjectChecksum(snapshot, manifest.checksumValue(), lease)) {
        log.warn("manifest checksum mismatch, skip register: object={}", snapshot.objectName());
        return null;
      }
      return manifest;
    } catch (Exception ex) {
      log.warn(
          "failed to read/parse sidecar manifest, skip register: marker={}, error={}",
          markerName,
          SwallowedExceptionLogger.summary(ex));
      return null;
    }
  }

  private boolean isSupportedManifest(SidecarManifest manifest, ObjectSnapshot snapshot) {
    if (EmptyChecks.isNull(manifest)) {
      return false;
    }
    if (!"file-sidecar-manifest-v1".equals(manifest.schemaVersion())) {
      return false;
    }
    String snapshotFileName = baseName(snapshot.objectName());
    if (!snapshotFileName.equals(manifest.fileName())) {
      return false;
    }
    if (EmptyChecks.isNull(manifest.sizeBytes())) {
      return false;
    }
    if (manifest.sizeBytes() < 0) {
      return false;
    }
    if (!Texts.hasText(manifest.checksumType())) {
      return false;
    }
    Set<String> supportedChecksumTypes = Set.of("SHA-256", "SHA256");
    String trimmedChecksumType = manifest.checksumType().trim();
    String normalizedChecksumType = trimmedChecksumType.toUpperCase(Locale.ROOT);
    if (!supportedChecksumTypes.contains(normalizedChecksumType)) {
      return false;
    }
    if (!Texts.hasText(manifest.checksumValue())) {
      return false;
    }
    boolean checksumScopeHasText = Texts.hasText(manifest.checksumScope());
    String checksumScope = checksumScopeHasText ? manifest.checksumScope().trim() : "RAW_OBJECT";
    if (!"RAW_OBJECT".equalsIgnoreCase(checksumScope)) {
      return false;
    }
    String checksum = manifest.checksumValue().trim();
    return checksum.matches("(?i)[0-9a-f]{64}");
  }

  private boolean verifyObjectChecksum(
      ObjectSnapshot snapshot, String expectedChecksum, Lease lease)
      throws NoSuchAlgorithmException, IOException {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long bytesRead = 0;
    ImportScanLeaseKeepalive keepalive = new ImportScanLeaseKeepalive(scanRepository, lease);
    byte[] buffer = new byte[64 * 1024];
    try (InputStream data =
        objectStore.get(s3StorageProperties.getBucket(), snapshot.objectName())) {
      int read;
      while ((read = data.read(buffer)) != -1) {
        if (read > snapshot.size() - bytesRead) {
          return false;
        }
        bytesRead += read;
        digest.update(buffer, 0, read);
        keepalive.renewIfDue();
      }
    }
    if (bytesRead != snapshot.size()) {
      return false;
    }
    String actualChecksum = HexFormat.of().formatHex(digest.digest());
    String normalizedExpectedChecksum = expectedChecksum.trim();
    return actualChecksum.equalsIgnoreCase(normalizedExpectedChecksum);
  }

  /** 写入到达组 metadata(到达组 SLA + 满足条件判定所需);group/requiredFileSet 已按批次清单或静态配置解析。 */
  private void putArrivalMetadata(
      Map<String, Object> metadata, String groupCode, String requiredFileSet) {
    metadata.put("fileGroupCode", groupCode);
    metadata.put("waitFileGroupMode", scannerProperties.getArrival().getWaitFileGroupMode());
    metadata.put(KEY_REQUIRED_FILE_SET, requiredFileSet);
    metadata.put("arrivalTimeoutAction", scannerProperties.getArrival().getArrivalTimeoutAction());
    Instant expectedArrivalAt = BatchDateTimeSupport.utcNow()
        .plusSeconds(scannerProperties.getArrival().getExpectedArrivalDelaySeconds());
    String expectedArrivalTime = expectedArrivalAt.toString();
    metadata.put("expectedArrivalTime", expectedArrivalTime);
    Instant latestTolerableAt = BatchDateTimeSupport.utcNow()
        .plusSeconds(scannerProperties.getArrival().getLatestTolerableDelaySeconds());
    String latestTolerableTime = latestTolerableAt.toString();
    metadata.put("latestTolerableTime", latestTolerableTime);
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
    String fileName = baseName(snapshot.objectName());
    BatchManifest matched = matchBatchManifest(batchManifests, tenantId, fileName);
    if (matched == null) {
      return;
    }
    Map<String, Object> fileRecord = fileRecords.loadFileRecordByStoragePath(
        tenantId, s3StorageProperties.getBucket(), snapshot.objectName());
    if (EmptyChecks.isEmpty(fileRecord)) {
      return;
    }
    Long fileId = PlatformRuntimeValues.toLong(fileRecord.get("id"));
    if (fileId == null) {
      return;
    }
    Map<String, Object> existingMetadata = parseRecordMetadata(fileRecord);
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (scannerProperties.getArrival().isEnabled() && !hasRequiredFileSet(existingMetadata)) {
      String fileGroupCode = matched.fileGroupCode();
      String requiredFiles = String.join(",", matched.requiredFiles());
      putArrivalMetadata(metadata, fileGroupCode, requiredFiles);
    }
    putMissingBundleMetadata(metadata, matched, fileName, existingMetadata);
    if (metadata.isEmpty()) {
      return;
    }
    fileRecords.updateFileMetadata(fileId, metadata);
    log.info(
        "batch manifest backfilled metadata for registered file: tenantId={}, fileId={},"
            + " fileGroupCode={}",
        tenantId,
        fileId,
        matched.fileGroupCode());
  }

  /** ADR-046:把 batch-manifest v2 的 per-file 束绑定落到 file_record metadata。 */
  private void putBundleMetadata(
      Map<String, Object> metadata, BatchManifest matchedBatch, String fileName) {
    putBundleMetadata(metadata, matchedBatch, fileName, Map.of(), false);
  }

  private void putMissingBundleMetadata(
      Map<String, Object> metadata,
      BatchManifest matchedBatch,
      String fileName,
      Map<String, Object> existingMetadata) {
    putBundleMetadata(metadata, matchedBatch, fileName, existingMetadata, true);
  }

  private void putBundleMetadata(
      Map<String, Object> metadata,
      BatchManifest matchedBatch,
      String fileName,
      Map<String, Object> existingMetadata,
      boolean onlyMissingOrChanged) {
    if (matchedBatch == null) {
      return;
    }
    matchedBatch
        .templateCodeFor(fileName)
        .ifPresent(templateCode -> putMetadataValue(
            metadata, existingMetadata, "bundleTemplateCode", templateCode, onlyMissingOrChanged));
    matchedBatch
        .targetRefFor(fileName)
        .ifPresent(targetRef -> putMetadataValue(
            metadata, existingMetadata, "bundleTargetRef", targetRef, onlyMissingOrChanged));
    if (Texts.hasText(matchedBatch.workflowCode())) {
      putMetadataValue(
          metadata,
          existingMetadata,
          "bundleWorkflowCode",
          matchedBatch.workflowCode(),
          onlyMissingOrChanged);
    }
  }

  private void putMetadataValue(
      Map<String, Object> metadata,
      Map<String, Object> existingMetadata,
      String key,
      String value,
      boolean onlyMissingOrChanged) {
    boolean shouldWriteMetadataValue =
        !onlyMissingOrChanged || !Objects.equals(existingMetadata.get(key), value);
    if (shouldWriteMetadataValue) {
      metadata.put(key, value);
    }
  }

  /** 已登记记录的 metadata_json 是否已含非空 requiredFileSet(回填幂等判据)。 */
  private boolean hasRequiredFileSet(Map<String, Object> metadata) {
    Object value = metadata.get(KEY_REQUIRED_FILE_SET);
    if (EmptyChecks.isNull(value)) {
      return false;
    }
    String requiredFileSet = String.valueOf(value);
    return Texts.hasText(requiredFileSet);
  }

  private Map<String, Object> parseRecordMetadata(Map<String, Object> fileRecord) {
    Object metadataJson = fileRecord.get("metadata_json");
    if (metadataJson == null) {
      return Map.of();
    }
    try {
      Map<String, Object> parsed =
          objectMapper.readValue(String.valueOf(metadataJson), new TypeReference<>() {});
      return parsed == null ? Map.of() : parsed;
    } catch (Exception ex) {
      return Map.of();
    }
  }

  /** 命中规则:成员文件名 ∈ 某清单 requiredFiles 且租户匹配;返回首个命中清单,无则 null。 */
  private BatchManifest matchBatchManifest(
      List<BatchManifest> manifests, String tenantId, String fileName) {
    for (BatchManifest m : manifests) {
      boolean manifestFileGroupIsIncomplete = EmptyChecks.isNull(m.requiredFiles())
          || m.requiredFiles().isEmpty()
          || !Texts.hasText(m.fileGroupCode());
      if (manifestFileGroupIsIncomplete) {
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

  /** 返回可记录的低基数失败原因，调用方仅在等待状态变化时记录。 */
  private ManifestRead readBatchManifest(String objectName) {
    try (InputStream in = objectStore.get(s3StorageProperties.getBucket(), objectName)) {
      byte[] bytes = in.readNBytes((int) MAX_MANIFEST_BYTES + 1);
      if (bytes.length > MAX_MANIFEST_BYTES) {
        return new ManifestRead(null, "MANIFEST_TOO_LARGE");
      }
      BatchManifest manifest = objectMapper.readValue(bytes, BatchManifest.class);
      if (manifest.hasRetiredJobTarget()) {
        return new ManifestRead(null, "MANIFEST_RETIRED");
      }
      return new ManifestRead(manifest, null);
    } catch (JsonProcessingException ex) {
      return new ManifestRead(null, "MANIFEST_INVALID");
    } catch (Exception ex) {
      return new ManifestRead(null, "MANIFEST_READ_FAILED");
    }
  }

  private record ManifestRead(BatchManifest manifest, String reason) {}

  private String resolveFileFormatType(String fileName) {
    String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    // 仅 .xlsx(OOXML)映射 EXCEL;旧二进制 .xls(OLE2/HSSF)不再假映射成 EXCEL ——
    // ExcelFormatParser 走纯 OOXML 的 OPCPackage.open(),喂 OLE2 字节必崩当坏文件。
    // .xls 落 BINARY,真正解析时由 ExcelFormatParser/上游格式路由给出明确报错(转 .xlsx 提示),
    // 而非在 PARSE 阶段静默产出坏数据。详见 ExcelFormatParser 的 OLE2 fail-fast。
    Map<String, String> formatMap = Map.ofEntries(
        Map.entry(".csv", FileTemplateFormat.DELIMITED.code()),
        Map.entry(".xlsx", FileTemplateFormat.EXCEL.code()),
        Map.entry(".xml", FileTemplateFormat.XML.code()),
        Map.entry(".json", FileTemplateFormat.JSON.code()));
    return formatMap.entrySet().stream()
        .filter(e -> lower.endsWith(e.getKey()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(FileTemplateFormat.BINARY.code());
  }

  private String sanitizeTrace(String fileName) {
    return fileName == null ? "object" : fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
  }

  private record ObjectSnapshot(String objectName, long size, String etag, Instant lastModified) {}
}
