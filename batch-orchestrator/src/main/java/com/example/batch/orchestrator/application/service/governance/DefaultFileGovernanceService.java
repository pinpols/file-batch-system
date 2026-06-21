package com.example.batch.orchestrator.application.service.governance;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.RunMode;
import com.example.batch.common.enums.TaskStatus;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.storage.ObjectNotFoundException;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.FileStateMachine;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.Texts;
import com.example.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import com.example.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import com.example.batch.orchestrator.config.FileGovernanceProperties;
import com.example.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileGovernanceCommand;
import com.example.batch.orchestrator.domain.command.FileUploadSessionCommand;
import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.entity.JobTaskEntity;
import com.example.batch.orchestrator.domain.query.JobTaskQuery;
import com.example.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import com.example.batch.orchestrator.infrastructure.file.S3GovernanceStorage;
import com.example.batch.orchestrator.mapper.JobInstanceMapper;
import com.example.batch.orchestrator.mapper.JobPartitionMapper;
import com.example.batch.orchestrator.mapper.JobTaskMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件治理统一入口：archive / delete / presign / redispatch / 到达组（arrival group）5 类操作。
 *
 * <p>公共约束：
 *
 * <ul>
 *   <li><b>安静期要求</b>：改状态类操作（archive / delete）先调 {@link #assertNoActiveRuntime} 确认无活跃 pipeline /
 *       pending dispatch，避免与运行中 pipeline 并发写导致状态漂移。
 *   <li><b>统一审计</b>：所有操作——包括失败路径——都写 {@code file_audit_log}，成功/失败都可追溯。
 *   <li><b>presign 安全分路</b>：content_encryption_enabled 的文件不直接 presign S3，改走 console 代理
 *       URL，让解密与下载审计都经过 console 层。
 *   <li><b>redispatch 语义</b>：3 表（dispatch_record / partition / task）同事务复位后，用 {@code
 *       RunMode.COMPENSATE} 打标再 outbox 派发，worker 据此区分是补偿重派还是首次执行。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class DefaultFileGovernanceService implements FileGovernanceService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final DateTimeFormatter OBJECT_KEY_DATE =
      DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC);

  private final FileGovernanceRepository fileGovernanceRepository;
  private final JobTaskMapper jobTaskMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final FileGovernanceProperties fileGovernanceProperties;
  private final S3GovernanceStorage s3GovernanceStorage;
  private final BatchSecurityProperties batchSecurityProperties;

  @Override
  @Transactional
  public String archiveFile(FileGovernanceCommand command) {
    return changeFileStatus(command, "ARCHIVED", "ARCHIVE");
  }

  @Override
  @Transactional
  public String deleteFile(FileGovernanceCommand command) {
    return changeFileStatus(command, "DELETED", "DELETE");
  }

  /**
   * 生成下载链接，按文件安全策略分两条路径：
   *
   * <ul>
   *   <li>{@code content_encryption_enabled=true}（且非 bypass-mode）：不能直接暴露 S3 presign URL，
   *       因为原始对象是加密字节；改返回 {@code /api/console/files/{id}/download} 走 console 代理， 由 console 侧解密 +
   *       审计后再吐给前端。
   *   <li>普通文件：直连 MinIO 生成有 TTL 的 presign URL（下限 60s）。
   * </ul>
   *
   * <p>若模板配置 {@code download_requires_approval}，则请求必须带 {@code approvalId}，否则 400。 所有成功路径都写 {@code
   * PRESIGN_DOWNLOAD} 审计。
   */
  @Override
  @Transactional
  public String presignFileDownload(FileGovernanceCommand command) {
    validateCommand(command);
    Map<String, Object> fileRecord =
        fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
    if (fileRecord.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    Map<String, Object> security =
        fileGovernanceRepository.loadTemplateSecurityForFile(command.tenantId(), command.fileId());
    if (requiresDownloadApproval(security) && !Texts.hasText(command.approvalId())) {
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.approval.id_required_for_download");
    }
    if (truthy(security.get("content_encryption_enabled"))
        && !batchSecurityProperties.isBypassMode()) {
      String consolePath =
          "/api/console/files/" + command.fileId() + "/download?tenantId=" + command.tenantId();
      if (Texts.hasText(command.approvalId())) {
        consolePath += "&approvalId=" + command.approvalId();
      }
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storageBucket", fileRecord.get("storage_bucket"));
      auditDetail.put("storagePath", fileRecord.get("storage_path"));
      auditDetail.put("approvalId", command.approvalId());
      auditDetail.put("contentEncryptionEnabled", true);
      auditDetail.put("encryptionKeyRef", security.get("encryption_key_ref"));
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              command.tenantId(),
              command.fileId(),
              "PRESIGN_DOWNLOAD",
              STATUS_SUCCESS,
              new FileGovernanceRepository.FileAuditActor(
                  resolveOperatorType(command.operatorId()), command.operatorId()),
              command.traceId(),
              auditDetail));
      return consolePath;
    }
    String storagePath = stringValue(fileRecord.get("storage_path"));
    String storageBucket = stringValue(fileRecord.get("storage_bucket"));
    if (storagePath == null || storagePath.isBlank()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.file.storage_path_missing");
    }
    int expirySeconds =
        Math.max(60, fileGovernanceProperties.getAccess().getPresignExpirySeconds());
    String presignedUrl =
        s3GovernanceStorage.createPresignedDownloadUrl(storageBucket, storagePath, expirySeconds);
    Map<String, Object> auditDetail = new LinkedHashMap<>();
    auditDetail.put("storageBucket", storageBucket);
    auditDetail.put("storagePath", storagePath);
    auditDetail.put("expirySeconds", expirySeconds);
    auditDetail.put("approvalId", command.approvalId());
    auditDetail.put("downloadRequiresApproval", truthy(security.get("download_requires_approval")));
    auditDetail.put("contentEncryptionEnabled", truthy(security.get("content_encryption_enabled")));
    auditDetail.put("encryptionKeyRef", security.get("encryption_key_ref"));
    auditDetail.put("maskingRuleSet", security.get("masking_rule_set"));
    if (truthy(security.get("preview_masking_enabled"))) {
      auditDetail.put(
          "previewMaskingNote",
          "template enables preview masking; avoid exposing raw object bytes in UI");
    }
    fileGovernanceRepository.appendAudit(
        new FileGovernanceRepository.FileAuditCommand(
            command.tenantId(),
            command.fileId(),
            "PRESIGN_DOWNLOAD",
            STATUS_SUCCESS,
            new FileGovernanceRepository.FileAuditActor(
                resolveOperatorType(command.operatorId()), command.operatorId()),
            command.traceId(),
            auditDetail));
    return presignedUrl;
  }

  @Override
  @Transactional
  public Map<String, Object> createUploadSession(FileUploadSessionCommand command) {
    validateUploadSessionCommand(command);
    Instant now = BatchDateTimeSupport.utcNow();
    String fileName = safeFileName(command.fileName());
    String storagePath =
        "uploads/"
            + safeKeySegment(command.tenantId())
            + "/"
            + OBJECT_KEY_DATE.format(now)
            + "/"
            + UUID.randomUUID()
            + "-"
            + fileName;
    String storageBucket = s3GovernanceStorage.defaultBucket();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("channelCode", command.channelCode());
    metadata.put("arrivalState", "WAITING_ARRIVAL");
    metadata.put("uploadMode", "APP_MANAGED");
    metadata.put("storageBackend", "BatchObjectStore");
    metadata.put("uploadSessionCreatedAt", now.toString());
    metadata.put("uploadSessionOperatorId", command.operatorId());
    Long fileId =
        fileGovernanceRepository.createReconciledFileRecord(
            new FileGovernanceRepository.ReconciledFileRecordCommand(
                new FileGovernanceRepository.FileIdentity(
                    command.tenantId(), "INPUT", fileName, fileFormatType(fileName)),
                0L,
                new FileGovernanceRepository.FileStorage("S3", storagePath, storageBucket),
                "UPLOAD",
                "RECEIVED",
                command.traceId(),
                metadata));
    if (fileId == null) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.common.state_conflict_detail",
          "upload storage path already exists");
    }
    fileGovernanceRepository.appendAudit(
        new FileGovernanceRepository.FileAuditCommand(
            command.tenantId(),
            fileId,
            "UPLOAD_SESSION_CREATED",
            STATUS_SUCCESS,
            new FileGovernanceRepository.FileAuditActor(
                resolveOperatorType(command.operatorId()), command.operatorId()),
            command.traceId(),
            metadata));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("fileId", fileId);
    response.put("status", "RECEIVED");
    response.put("uploadMode", "APP_MANAGED");
    response.put("uploadMethod", "PUT");
    response.put("contentField", "file");
    response.put(
        "uploadUrl",
        "/api/console/files/" + fileId + "/content?tenantId=" + safeUrlQuery(command.tenantId()));
    response.put("storageBucket", storageBucket);
    response.put("storagePath", storagePath);
    response.put("fileName", fileName);
    return response;
  }

  @Override
  @Transactional
  public String confirmFileArrival(FileGovernanceCommand command) {
    validateCommand(command);
    Map<String, Object> fileRecord =
        fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
    if (fileRecord.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    String storagePath = stringValue(fileRecord.get("storage_path"));
    if (!Texts.hasText(storagePath)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.file.storage_path_missing");
    }
    String storageBucket = stringValue(fileRecord.get("storage_bucket"));
    long fileSizeBytes;
    try {
      fileSizeBytes = s3GovernanceStorage.objectSize(storageBucket, storagePath);
    } catch (ObjectNotFoundException exception) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.content_not_found");
    }
    Instant now = BatchDateTimeSupport.utcNow();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("arrivalConfirmedAt", now.toString());
    metadata.put("arrivalReason", "MANUAL_FILE_CONFIRM");
    metadata.put("arrivalConfirmedBy", command.operatorId());
    metadata.put("uploadedSizeBytes", fileSizeBytes);
    fileGovernanceRepository.markFileArrivalConfirmed(
        command.tenantId(), command.fileId(), fileSizeBytes, metadata);
    fileGovernanceRepository.appendAudit(
        new FileGovernanceRepository.FileAuditCommand(
            command.tenantId(),
            command.fileId(),
            "CONFIRM_ARRIVAL",
            STATUS_SUCCESS,
            new FileGovernanceRepository.FileAuditActor(
                resolveOperatorType(command.operatorId()), command.operatorId()),
            command.traceId(),
            metadata));
    return "ARRIVAL_CONFIRMED";
  }

  /**
   * 手动重派文件：同事务内复位 dispatch_record / partition（→ READY）/ task（→ READY），然后以 {@code
   * RunMode.COMPENSATE} 打标写 outbox 派发事件。COMPENSATE 标记让 worker 走补偿分支——避免被当作首次 执行重复上报失败导致死信。{@code
   * eventKey} 用 {@code manual-redispatch:{taskId}}，保证同一 task 并发重派幂等。
   */
  @Override
  @Transactional
  public String redispatchFile(FileGovernanceCommand command) {
    validateCommand(command);
    Map<String, Object> fileRecord =
        fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
    if (fileRecord.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    Map<String, Object> dispatchRecord =
        fileGovernanceRepository.loadLatestDispatchRecord(
            command.tenantId(), command.fileId(), command.channelCode());
    if (dispatchRecord.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.dispatch.record_not_found");
    }
    Long pipelineInstanceId = toLong(dispatchRecord.get("pipeline_instance_id"));
    Long relatedJobInstanceId =
        fileGovernanceRepository.loadRelatedJobInstanceId(pipelineInstanceId);
    if (relatedJobInstanceId == null) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.dispatch.pipeline_unbound");
    }
    JobInstanceEntity jobInstance =
        Guard.requireFound(
            jobInstanceMapper.selectById(command.tenantId(), relatedJobInstanceId),
            "dispatch job instance not found");
    JobTaskEntity task = resolveDispatchTask(command.tenantId(), jobInstance.getId());
    JobPartitionEntity partition =
        Guard.requireFound(
            jobPartitionMapper.selectById(command.tenantId(), task.getJobPartitionId()),
            "dispatch partition not found");

    fileGovernanceRepository.resetDispatchRecordForRedispatch(
        command.tenantId(), toLong(dispatchRecord.get("id")));
    jobPartitionMapper.resetForDispatch(
        command.tenantId(),
        partition.getId(),
        PartitionStatus.READY.code(),
        partition.getVersion());
    jobTaskMapper.resetForRetry(
        command.tenantId(), task.getId(), TaskStatus.READY.code(), task.getVersion());
    taskDispatchOutboxService.writeDispatchEvent(
        jobInstance,
        task,
        partition,
        command.traceId(),
        OutboxEventKeyGenerator.forFileRedispatch(command.tenantId(), task.getId()),
        RunMode.COMPENSATE);
    fileGovernanceRepository.appendAudit(
        new FileGovernanceRepository.FileAuditCommand(
            command.tenantId(),
            command.fileId(),
            "REDISPATCH",
            STATUS_SUCCESS,
            new FileGovernanceRepository.FileAuditActor(
                resolveOperatorType(command.operatorId()), command.operatorId()),
            command.traceId(),
            buildRedispatchDetail(dispatchRecord, task, partition, command)));
    return "REDISPATCH_ACCEPTED";
  }

  @Override
  @Transactional
  public String operateArrivalGroup(ArrivalGroupGovernanceCommand command) {
    validateArrivalGroupCommand(command);
    List<Map<String, Object>> groupFiles =
        Texts.hasText(command.bizDate())
            ? fileGovernanceRepository.selectArrivalGroupFiles(
                command.tenantId(), command.fileGroupCode(), command.bizDate())
            : fileGovernanceRepository.selectArrivalGroupFiles(
                command.tenantId(), command.fileGroupCode());
    if (groupFiles.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.arrival_group.not_found");
    }
    rejectAmbiguousArrivalGroupOperation(command, groupFiles);
    Instant now = BatchDateTimeSupport.utcNow();
    String action = command.action().trim().toUpperCase();
    String nextState =
        switch (action) {
          case "CONTINUE_WAITING" -> "WAITING_ARRIVAL";
          case "SKIP_BATCH" -> "TIMEOUT";
          case "EMPTY_RUN", "TRIGGER_NOW" -> "TRIGGERED";
          default ->
              throw BizException.of(
                  ResultCode.INVALID_ARGUMENT,
                  "error.common.invalid_argument_detail",
                  "unsupported arrival action: " + command.action());
        };
    if ("EMPTY_RUN".equals(action) && !toBoolean(groupFiles.get(0).get("allow_empty_run"))) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.arrival_group.empty_run_not_allowed");
    }
    if ("SKIP_BATCH".equals(action) && !toBoolean(groupFiles.get(0).get("allow_skip_biz_date"))) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.arrival_group.skip_batch_not_allowed");
    }
    long extensionSeconds =
        command.extendWaitSeconds() == null || command.extendWaitSeconds() <= 0
            ? fileGovernanceProperties.getArrival().getManualWaitExtensionSeconds()
            : command.extendWaitSeconds();
    String latestTolerableTime =
        "CONTINUE_WAITING".equals(action)
            ? now.plusSeconds(Math.max(1L, extensionSeconds)).toString()
            : stringValue(groupFiles.get(0).get("latest_tolerable_time"));
    for (Map<String, Object> groupFile : groupFiles) {
      Long fileId = toLong(groupFile.get("id"));
      if (fileId == null) {
        continue;
      }
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("arrivalState", nextState);
      metadata.put("arrivalReason", "MANUAL_" + action);
      metadata.put("arrivalCheckedAt", now.toString());
      metadata.put("manualArrivalAction", action);
      metadata.put("manualArrivalActionAt", now.toString());
      metadata.put("manualArrivalOperatorId", command.operatorId());
      metadata.put("manualArrivalReason", command.reason());
      if ("CONTINUE_WAITING".equals(action)) {
        metadata.put("latestTolerableTime", latestTolerableTime);
      }
      if ("TRIGGERED".equals(nextState)) {
        metadata.put("arrivalTriggeredAt", now.toString());
      }
      if ("TIMEOUT".equals(nextState)) {
        metadata.put("arrivalTimedOutAt", now.toString());
      }
      fileGovernanceRepository.updateFileMetadata(command.tenantId(), fileId, metadata);
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              command.tenantId(),
              fileId,
              "ARRIVAL_MANUAL_" + action,
              STATUS_SUCCESS,
              new FileGovernanceRepository.FileAuditActor(
                  resolveOperatorType(command.operatorId()), command.operatorId()),
              command.traceId(),
              metadata));
    }
    return nextState;
  }

  private void rejectAmbiguousArrivalGroupOperation(
      ArrivalGroupGovernanceCommand command, List<Map<String, Object>> groupFiles) {
    if (Texts.hasText(command.bizDate())) {
      return;
    }
    LinkedHashSet<String> bizDates = new LinkedHashSet<>();
    for (Map<String, Object> groupFile : groupFiles) {
      String bizDate = stringValue(groupFile.get("biz_date"));
      bizDates.add(Texts.hasText(bizDate) ? bizDate : "__MISSING_BIZ_DATE__");
      if (bizDates.size() > 1) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT,
            "error.common.invalid_argument_detail",
            "bizDate is required when arrival group spans multiple business dates");
      }
    }
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private boolean truthy(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    if (value == null) {
      return false;
    }
    return "true".equalsIgnoreCase(String.valueOf(value));
  }

  private boolean requiresDownloadApproval(Map<String, Object> security) {
    if (batchSecurityProperties.isBypassMode()) {
      return false;
    }
    if (security == null || security.isEmpty()) {
      return false;
    }
    return truthy(security.get("download_requires_approval"))
        || truthy(security.get("content_encryption_enabled"));
  }

  /** 文件治理只允许在运行态安静时改状态，避免和 pipeline/dispatch 并发写冲突。 */
  private String changeFileStatus(
      FileGovernanceCommand command, String nextStatus, String operationType) {
    validateCommand(command);
    Map<String, Object> fileRecord =
        fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
    if (fileRecord.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    String currentStatus = stringValue(fileRecord.get("file_status"));
    try {
      assertNoActiveRuntime(command);
      // 状态机校验：业务规则属于应用层职责（已从 Repository 上移）
      FileStateMachine.assertTransition(currentStatus, nextStatus);
      int updated =
          fileGovernanceRepository.updateFileStatus(
              command.tenantId(),
              command.fileId(),
              currentStatus,
              nextStatus,
              Map.of(
                  "governanceOperation", operationType,
                  "reason", command.reason(),
                  "operatorId", command.operatorId()));
      if (updated <= 0) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT,
            "error.common.state_conflict_detail",
            "file status changed concurrently, expected " + currentStatus);
      }
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              command.tenantId(),
              command.fileId(),
              operationType,
              STATUS_SUCCESS,
              new FileGovernanceRepository.FileAuditActor(
                  resolveOperatorType(command.operatorId()), command.operatorId()),
              command.traceId(),
              fileGovernanceRepository.operationDetail(
                  currentStatus, nextStatus, command.operatorId(), command.reason())));
      return nextStatus;
    } catch (RuntimeException exception) {
      fileGovernanceRepository.appendAudit(
          new FileGovernanceRepository.FileAuditCommand(
              command.tenantId(),
              command.fileId(),
              operationType,
              "FAILED",
              new FileGovernanceRepository.FileAuditActor(
                  resolveOperatorType(command.operatorId()), command.operatorId()),
              command.traceId(),
              Map.of(
                  "currentStatus",
                  currentStatus,
                  "nextStatus",
                  nextStatus,
                  "reason",
                  command.reason(),
                  "errorMessage",
                  exception.getMessage())));
      throw exception;
    }
  }

  private JobTaskEntity resolveDispatchTask(String tenantId, Long jobInstanceId) {
    List<JobTaskEntity> tasks =
        jobTaskMapper.selectByQuery(JobTaskQuery.ofJobInstance(tenantId, jobInstanceId));
    return tasks.stream()
        .filter(task -> "DISPATCH".equalsIgnoreCase(task.getTaskType()))
        .sorted(
            Comparator.comparing(
                JobTaskEntity::getTaskSeq, Comparator.nullsLast(Integer::compareTo)))
        .findFirst()
        .orElseThrow(() -> BizException.of(ResultCode.NOT_FOUND, "error.dispatch.task_not_found"));
  }

  private Map<String, Object> buildRedispatchDetail(
      Map<String, Object> dispatchRecord,
      JobTaskEntity task,
      JobPartitionEntity partition,
      FileGovernanceCommand command) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("dispatchRecordId", dispatchRecord.get("id"));
    detail.put("channelCode", dispatchRecord.get("channel_code"));
    detail.put("taskId", task.getId());
    detail.put("partitionId", partition.getId());
    detail.put("reason", command.reason());
    return detail;
  }

  private void assertNoActiveRuntime(FileGovernanceCommand command) {
    long activePipelines =
        fileGovernanceRepository.countActivePipelineInstances(command.tenantId(), command.fileId());
    if (activePipelines > 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.file.has_active_pipelines");
    }
    long pendingDispatches =
        fileGovernanceRepository.countPendingDispatchRecords(command.tenantId(), command.fileId());
    if (pendingDispatches > 0) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.file.has_pending_dispatches");
    }
  }

  private void validateCommand(FileGovernanceCommand command) {
    Guard.require(command != null, "file governance command is required");
    if (!Texts.hasText(command.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (command.fileId() == null) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.file.id_required");
    }
  }

  private void validateArrivalGroupCommand(ArrivalGroupGovernanceCommand command) {
    Guard.require(command != null, "arrival group command is required");
    if (!Texts.hasText(command.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (!Texts.hasText(command.fileGroupCode())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.file.group_code_required");
    }
    if (!Texts.hasText(command.action())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.action_required");
    }
  }

  private void validateUploadSessionCommand(FileUploadSessionCommand command) {
    Guard.require(command != null, "file upload session command is required");
    if (!Texts.hasText(command.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (!Texts.hasText(command.channelCode())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.channel_code_required");
    }
    if (!Texts.hasText(command.fileName())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.file.name_required");
    }
  }

  private String resolveOperatorType(String operatorId) {
    return Texts.hasText(operatorId) ? "USER" : "API";
  }

  private Long toLong(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Number number) {
      return number.longValue();
    }
    String text = String.valueOf(value);
    return text.isBlank() ? null : Long.valueOf(text);
  }

  private boolean toBoolean(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value != null && Boolean.parseBoolean(String.valueOf(value));
  }

  private String safeFileName(String fileName) {
    String cleaned = fileName == null ? "" : fileName.trim();
    cleaned = cleaned.replace('\\', '/');
    int lastSlash = cleaned.lastIndexOf('/');
    if (lastSlash >= 0) {
      cleaned = cleaned.substring(lastSlash + 1);
    }
    cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
    cleaned = cleaned.replaceAll("_+", "_");
    if (!Texts.hasText(cleaned) || ".".equals(cleaned) || "..".equals(cleaned)) {
      return "upload.bin";
    }
    return cleaned.length() <= 128 ? cleaned : cleaned.substring(cleaned.length() - 128);
  }

  private String safeKeySegment(String value) {
    String cleaned = value == null ? "" : value.replaceAll("[^A-Za-z0-9._-]", "_");
    return Texts.hasText(cleaned) ? cleaned : "tenant";
  }

  private String safeUrlQuery(String value) {
    return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String fileFormatType(String fileName) {
    String ext = "";
    int dot = fileName.lastIndexOf('.');
    if (dot >= 0 && dot < fileName.length() - 1) {
      ext = fileName.substring(dot + 1).toLowerCase();
    }
    return switch (ext) {
      case "csv", "txt", "tsv" -> "DELIMITED";
      case "xlsx", "xls" -> "EXCEL";
      case "json" -> "JSON";
      case "xml" -> "XML";
      default -> "BINARY";
    };
  }
}
