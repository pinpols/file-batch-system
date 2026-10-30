package io.github.pinpols.batch.orchestrator.application.service.governance;

import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.RunMode;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.enums.TaskStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.storage.ObjectNotFoundException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.FileStateMachine;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.orchestrator.application.engine.OutboxEventKeyGenerator;
import io.github.pinpols.batch.orchestrator.application.engine.TaskDispatchOutboxService;
import io.github.pinpols.batch.orchestrator.application.engine.command.TaskDispatchEventCommand;
import io.github.pinpols.batch.orchestrator.application.service.batchday.FileGroupExpectationService;
import io.github.pinpols.batch.orchestrator.config.FileGovernanceProperties;
import io.github.pinpols.batch.orchestrator.domain.command.ArrivalGroupGovernanceCommand;
import io.github.pinpols.batch.orchestrator.domain.command.FileGovernanceCommand;
import io.github.pinpols.batch.orchestrator.domain.command.FileUploadSessionCommand;
import io.github.pinpols.batch.orchestrator.domain.entity.JobInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobTaskEntity;
import io.github.pinpols.batch.orchestrator.domain.query.FileGroupUploadTarget;
import io.github.pinpols.batch.orchestrator.domain.query.JobTaskQuery;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews.DispatchRecordRow;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews.FileRecordRow;
import io.github.pinpols.batch.orchestrator.domain.view.FileGovernanceViews.TemplateSecurityRow;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceArrivalViews;
import io.github.pinpols.batch.orchestrator.infrastructure.file.FileGovernanceRepository;
import io.github.pinpols.batch.orchestrator.infrastructure.file.S3GovernanceStorage;
import io.github.pinpols.batch.orchestrator.mapper.JobInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobPartitionMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobStepInstanceMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobTaskMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
  private final JobStepInstanceMapper jobStepInstanceMapper;
  private final JobPartitionMapper jobPartitionMapper;
  private final JobInstanceMapper jobInstanceMapper;
  private final TaskDispatchOutboxService taskDispatchOutboxService;
  private final FileGovernanceProperties fileGovernanceProperties;
  private final S3GovernanceStorage s3GovernanceStorage;
  private final BatchSecurityProperties batchSecurityProperties;
  private final FileGovernanceCommitService fileGovernanceCommitService;
  private final FileGroupExpectationService fileGroupExpectationService;

  @Override
  @Transactional
  public String archiveFile(FileGovernanceCommand command) {
    return changeFileStatus(command, FileStatus.ARCHIVED.code(), "ARCHIVE");
  }

  @Override
  @Transactional
  public String deleteFile(FileGovernanceCommand command) {
    return changeFileStatus(command, FileStatus.DELETED.code(), "DELETE");
  }

  /**
   * 生成下载链接，按文件安全策略分两条路径：
   *
   * <ul>
   *   <li>{@code content_encryption_enabled=true}（且非 bypass-mode）：不能直接暴露 S3 presign URL，
   *       因为原始对象是加密字节；改返回 {@code /api/console/files/{id}/download} 走 console 代理， 由 console 侧解密 +
   *       审计后再吐给前端。
   *   <li>普通文件：直连对象存储生成有 TTL 的 presign URL（下限 60s）。
   * </ul>
   *
   * <p>若模板配置 {@code download_requires_approval}，则请求必须带 {@code approvalId}，否则 400。 所有成功路径都写 {@code
   * PRESIGN_DOWNLOAD} 审计。
   */
  @Override
  public String presignFileDownload(FileGovernanceCommand command) {
    validateCommand(command);
    FileRecordRow loadedFileRecord =
        fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
    FileGovernanceViews.FileRecordView fileRecord =
        FileGovernanceViews.fileRecord(loadedFileRecord);
    if (!fileRecord.present()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    TemplateSecurityRow loadedTemplateSecurity =
        fileGovernanceRepository.loadTemplateSecurityForFile(command.tenantId(), command.fileId());
    FileGovernanceViews.TemplateSecurityView security =
        FileGovernanceViews.templateSecurity(loadedTemplateSecurity);
    boolean downloadApprovalIsRequiredButMissing =
        requiresDownloadApproval(security) && !Texts.hasText(command.approvalId());
    if (downloadApprovalIsRequiredButMissing) {
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.approval.id_required_for_download");
    }
    boolean encryptedDownloadMustUseConsoleProxy =
        security.contentEncryptionEnabled() && !batchSecurityProperties.isBypassMode();
    if (encryptedDownloadMustUseConsoleProxy) {
      String consolePath =
          "/api/console/files/" + command.fileId() + "/download?tenantId=" + command.tenantId();
      if (Texts.hasText(command.approvalId())) {
        consolePath += "&approvalId=" + command.approvalId();
      }
      Map<String, Object> auditDetail = new LinkedHashMap<>();
      auditDetail.put("storageBucket", fileRecord.storageBucket());
      auditDetail.put("storagePath", fileRecord.storagePath());
      auditDetail.put("approvalId", command.approvalId());
      auditDetail.put("contentEncryptionEnabled", true);
      auditDetail.put("encryptionKeyRef", security.encryptionKeyRef());
      String operatorType = resolveOperatorType(command.operatorId());
      FileGovernanceRepository.FileAuditCommand auditCommand =
          new FileGovernanceRepository.FileAuditCommand(
              command.tenantId(),
              command.fileId(),
              "PRESIGN_DOWNLOAD",
              STATUS_SUCCESS,
              new FileGovernanceRepository.FileAuditActor(operatorType, command.operatorId()),
              command.traceId(),
              auditDetail);
      fileGovernanceCommitService.appendAudit(auditCommand);
      return consolePath;
    }
    String storagePath = fileRecord.storagePath();
    String storageBucket = fileRecord.storageBucket();
    if (EmptyChecks.isBlank(storagePath)) {
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
    auditDetail.put("downloadRequiresApproval", security.downloadRequiresApproval());
    auditDetail.put("contentEncryptionEnabled", security.contentEncryptionEnabled());
    auditDetail.put("encryptionKeyRef", security.encryptionKeyRef());
    auditDetail.put("maskingRuleSet", security.maskingRuleSet());
    if (security.previewMaskingEnabled()) {
      auditDetail.put(
          "previewMaskingNote",
          "template enables preview masking; avoid exposing raw object bytes in UI");
    }
    String operatorType = resolveOperatorType(command.operatorId());
    FileGovernanceRepository.FileAuditCommand auditCommand =
        new FileGovernanceRepository.FileAuditCommand(
            command.tenantId(),
            command.fileId(),
            "PRESIGN_DOWNLOAD",
            STATUS_SUCCESS,
            new FileGovernanceRepository.FileAuditActor(operatorType, command.operatorId()),
            command.traceId(),
            auditDetail);
    fileGovernanceCommitService.appendAudit(auditCommand);
    return presignedUrl;
  }

  @Override
  @Transactional
  public FileUploadSessionResponse createUploadSession(FileUploadSessionCommand command) {
    validateUploadSessionCommand(command);
    Instant now = BatchDateTimeSupport.utcNow();
    String fileName = safeFileName(command.fileName());
    Map<String, Object> uploadGroupMetadata = uploadGroupMetadata(command, fileName);
    String storagePath = "uploads/"
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
    metadata.putAll(uploadGroupMetadata);
    FileGovernanceRepository.FileIdentity fileIdentity = new FileGovernanceRepository.FileIdentity(
        command.tenantId(), "INPUT", fileName, fileFormatType(fileName));
    FileGovernanceRepository.FileStorage fileStorage =
        new FileGovernanceRepository.FileStorage("S3", storagePath, storageBucket);
    FileGovernanceRepository.ReconciledFileRecordCommand recordCommand =
        new FileGovernanceRepository.ReconciledFileRecordCommand(
            fileIdentity,
            0L,
            fileStorage,
            "UPLOAD",
            FileStatus.RECEIVED.code(),
            command.traceId(),
            metadata);
    Long fileId = EmptyChecks.isEmpty(uploadGroupMetadata)
        ? fileGovernanceRepository.createReconciledFileRecord(recordCommand)
        : fileGovernanceRepository.createReconciledFileRecord(recordCommand, command.bizDate());
    if (EmptyChecks.isNull(fileId)) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          ResultCode.STATE_CONFLICT.detailKey(),
          "upload storage path already exists");
    }
    String operatorType = resolveOperatorType(command.operatorId());
    FileGovernanceRepository.FileAuditCommand auditCommand =
        new FileGovernanceRepository.FileAuditCommand(
            command.tenantId(),
            fileId,
            "UPLOAD_SESSION_CREATED",
            STATUS_SUCCESS,
            new FileGovernanceRepository.FileAuditActor(operatorType, command.operatorId()),
            command.traceId(),
            metadata);
    fileGovernanceRepository.appendAudit(auditCommand);
    String uploadStatus = FileStatus.RECEIVED.code();
    String fileIdText = String.valueOf(fileId);
    String contentUrl = "/api/console/files/" + fileIdText + "/content?tenantId="
        + safeUrlQuery(command.tenantId());
    return new FileUploadSessionResponse(
        fileId,
        uploadStatus,
        "APP_MANAGED",
        "PUT",
        "file",
        contentUrl,
        storageBucket,
        storagePath,
        fileName);
  }

  @Override
  public String confirmFileArrival(FileGovernanceCommand command) {
    validateCommand(command);
    FileRecordRow loadedFileRecord =
        fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
    FileGovernanceViews.FileRecordView fileRecord =
        FileGovernanceViews.fileRecord(loadedFileRecord);
    if (!fileRecord.present()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    String storagePath = fileRecord.storagePath();
    if (!Texts.hasText(storagePath)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.file.storage_path_missing");
    }
    String storageBucket = fileRecord.storageBucket();
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
    String operatorType = resolveOperatorType(command.operatorId());
    FileGovernanceRepository.FileAuditCommand auditCommand =
        new FileGovernanceRepository.FileAuditCommand(
            command.tenantId(),
            command.fileId(),
            "CONFIRM_ARRIVAL",
            STATUS_SUCCESS,
            new FileGovernanceRepository.FileAuditActor(operatorType, command.operatorId()),
            command.traceId(),
            metadata);
    fileGovernanceCommitService.confirmArrival(
        command.tenantId(), command.fileId(), fileSizeBytes, metadata, auditCommand);
    return "ARRIVAL_CONFIRMED";
  }

  /**
   * 手动重派文件：同事务内复位 dispatch_record / partition（→ READY）/ task（→ READY），然后以 {@code
   * RunMode.COMPENSATE} 打标写 outbox 派发事件。COMPENSATE 标记让 worker 走补偿分支——避免被当作首次 执行重复上报失败导致死信。{@code
   * eventKey} 包含复位前任务版本，区分不同失败尝试并阻止同次并发复位。
   */
  @Override
  @Transactional
  public String redispatchFile(FileGovernanceCommand command) {
    validateCommand(command);
    FileRecordRow loadedFileRecord =
        fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
    FileGovernanceViews.FileRecordView fileRecord =
        FileGovernanceViews.fileRecord(loadedFileRecord);
    if (!fileRecord.present()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    DispatchRecordRow loadedDispatchRecord = fileGovernanceRepository.loadLatestDispatchRecord(
        command.tenantId(), command.fileId(), command.channelCode());
    FileGovernanceViews.DispatchRecordView dispatchRecord =
        FileGovernanceViews.dispatchRecord(loadedDispatchRecord);
    if (!dispatchRecord.present()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.dispatch.record_not_found");
    }
    Long pipelineInstanceId = dispatchRecord.pipelineInstanceId();
    Long relatedJobInstanceId =
        fileGovernanceRepository.loadRelatedJobInstanceId(pipelineInstanceId);
    if (EmptyChecks.isNull(relatedJobInstanceId)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.dispatch.pipeline_unbound");
    }
    JobInstanceEntity loadedJobInstance =
        jobInstanceMapper.selectById(command.tenantId(), relatedJobInstanceId);
    JobInstanceEntity jobInstance =
        Guard.requireFound(loadedJobInstance, "dispatch job instance not found");
    JobTaskEntity task = resolveDispatchTask(
        command.tenantId(), jobInstance.getId(), command.fileId(), dispatchRecord.channelCode());
    if (TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN.equals(task.getErrorCode())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.task.retry_unknown_outcome_requires_resolution");
    }
    if (!TaskStatus.FAILED.code().equals(task.getTaskStatus())) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT,
          "error.dispatch.redispatch_requires_failed_task",
          task.getTaskStatus());
    }
    if (!matchesRedispatchTarget(task, command.fileId(), dispatchRecord.channelCode())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.dispatch.redispatch_task_ambiguous");
    }
    JobPartitionEntity loadedPartition =
        jobPartitionMapper.selectById(command.tenantId(), task.getJobPartitionId());
    JobPartitionEntity partition =
        Guard.requireFound(loadedPartition, "dispatch partition not found");

    if (fileGovernanceRepository.resetDispatchRecordForRedispatch(
            command.tenantId(), dispatchRecord.id())
        != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, ResultCode.STATE_CONFLICT.detailKey());
    }
    if (jobPartitionMapper.resetForDispatch(
            command.tenantId(),
            partition.getId(),
            PartitionStatus.READY.code(),
            partition.getVersion())
        != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, ResultCode.STATE_CONFLICT.detailKey());
    }
    if (jobTaskMapper.resetForRetry(
            command.tenantId(), task.getId(), TaskStatus.READY.code(), task.getVersion())
        != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, ResultCode.STATE_CONFLICT.detailKey());
    }
    String redispatchEventKey = OutboxEventKeyGenerator.forFileRedispatch(
        command.tenantId(), task.getId(), task.getVersion());
    TaskDispatchEventCommand dispatchCommand = TaskDispatchEventCommand.builder()
        .jobInstance(jobInstance)
        .task(task)
        .partition(partition)
        .traceId(command.traceId())
        .eventKey(redispatchEventKey)
        .runModeOverride(RunMode.COMPENSATE)
        .build();
    taskDispatchOutboxService.writeDispatchEvent(dispatchCommand);
    String operatorType = resolveOperatorType(command.operatorId());
    FileGovernanceRepository.FileAuditCommand auditCommand =
        new FileGovernanceRepository.FileAuditCommand(
            command.tenantId(),
            command.fileId(),
            "REDISPATCH",
            STATUS_SUCCESS,
            new FileGovernanceRepository.FileAuditActor(operatorType, command.operatorId()),
            command.traceId(),
            buildRedispatchDetail(dispatchRecord, task, partition, command));
    fileGovernanceRepository.appendAudit(auditCommand);
    return "REDISPATCH_ACCEPTED";
  }

  @Override
  @Transactional
  public String operateArrivalGroup(ArrivalGroupGovernanceCommand command) {
    validateArrivalGroupCommand(command);
    List<FileGovernanceArrivalViews.ArrivalGroupFileView> groupFiles =
        Texts.hasText(command.bizDate())
            ? fileGovernanceRepository.selectArrivalGroupFiles(
                command.tenantId(), command.fileGroupCode(), command.bizDate())
            : fileGovernanceRepository.selectArrivalGroupFiles(
                command.tenantId(), command.fileGroupCode());
    if (EmptyChecks.isEmpty(groupFiles)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.arrival_group.not_found");
    }
    FileGovernanceArrivalViews.ArrivalGroupFileView firstGroupFile = groupFiles.get(0);
    rejectAmbiguousArrivalGroupOperation(command, groupFiles);
    Instant now = BatchDateTimeSupport.utcNow();
    String requestedAction = command.action();
    String trimmedAction = requestedAction.trim();
    String action = trimmedAction.toUpperCase();
    String nextState =
        switch (action) {
          case "CONTINUE_WAITING" -> "WAITING_ARRIVAL";
          case "SKIP_BATCH" -> "TIMEOUT";
          case "EMPTY_RUN", "TRIGGER_NOW" -> "TRIGGERED";
          default ->
            throw BizException.of(
                ResultCode.INVALID_ARGUMENT,
                ResultCode.INVALID_ARGUMENT.detailKey(),
                "unsupported arrival action: " + command.action());
        };
    if ("EMPTY_RUN".equals(action) && !firstGroupFile.allowEmptyRun()) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.arrival_group.empty_run_not_allowed");
    }
    if ("SKIP_BATCH".equals(action) && !firstGroupFile.allowSkipBizDate()) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.arrival_group.skip_batch_not_allowed");
    }
    long extensionSeconds =
        EmptyChecks.isNull(command.extendWaitSeconds()) || command.extendWaitSeconds() <= 0
            ? fileGovernanceProperties.getArrival().getManualWaitExtensionSeconds()
            : command.extendWaitSeconds();
    String latestTolerableTime;
    if ("CONTINUE_WAITING".equals(action)) {
      long boundedExtensionSeconds = Math.max(1L, extensionSeconds);
      Instant latestTolerableInstant = now.plusSeconds(boundedExtensionSeconds);
      latestTolerableTime = latestTolerableInstant.toString();
    } else {
      latestTolerableTime = firstGroupFile.latestTolerableTime();
    }
    for (FileGovernanceArrivalViews.ArrivalGroupFileView groupFile : groupFiles) {
      Long fileId = groupFile.fileId();
      if (EmptyChecks.isNull(fileId)) {
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
      String operatorType = resolveOperatorType(command.operatorId());
      FileGovernanceRepository.FileAuditCommand auditCommand =
          new FileGovernanceRepository.FileAuditCommand(
              command.tenantId(),
              fileId,
              "ARRIVAL_MANUAL_" + action,
              STATUS_SUCCESS,
              new FileGovernanceRepository.FileAuditActor(operatorType, command.operatorId()),
              command.traceId(),
              metadata);
      fileGovernanceRepository.appendAudit(auditCommand);
    }
    return nextState;
  }

  private void rejectAmbiguousArrivalGroupOperation(
      ArrivalGroupGovernanceCommand command,
      List<FileGovernanceArrivalViews.ArrivalGroupFileView> groupFiles) {
    if (Texts.hasText(command.bizDate())) {
      return;
    }
    LinkedHashSet<String> bizDates = new LinkedHashSet<>();
    for (FileGovernanceArrivalViews.ArrivalGroupFileView groupFile : groupFiles) {
      String bizDate = groupFile.bizDate();
      bizDates.add(Texts.hasText(bizDate) ? bizDate : "__MISSING_BIZ_DATE__");
      if (bizDates.size() > 1) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT,
            "error.common.invalid_argument_detail",
            "bizDate is required when arrival group spans multiple business dates");
      }
    }
  }

  private boolean requiresDownloadApproval(FileGovernanceViews.TemplateSecurityView security) {
    if (batchSecurityProperties.isBypassMode()) {
      return false;
    }
    return security.downloadRequiresApproval() || security.contentEncryptionEnabled();
  }

  /** 文件治理只允许在运行态安静时改状态，避免和 pipeline/dispatch 并发写冲突。 */
  private String changeFileStatus(
      FileGovernanceCommand command, String nextStatus, String operationType) {
    validateCommand(command);
    FileRecordRow loadedFileRecord =
        fileGovernanceRepository.loadFileRecord(command.tenantId(), command.fileId());
    FileGovernanceViews.FileRecordView fileRecord =
        FileGovernanceViews.fileRecord(loadedFileRecord);
    if (!fileRecord.present()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.file.record_not_found");
    }
    String currentStatus = fileRecord.fileStatus();
    try {
      assertNoActiveRuntime(command);
      // 状态机校验：业务规则属于应用层职责（已从 Repository 上移）
      FileStateMachine.assertTransition(currentStatus, nextStatus);
      String tenantId = command.tenantId();
      Long fileId = command.fileId();
      String auditReason = Objects.requireNonNullElse(command.reason(), "");
      String auditOperatorId = Objects.requireNonNullElse(command.operatorId(), "");
      Map<String, Object> updateMetadata = Map.of(
          "governanceOperation", operationType,
          "reason", auditReason,
          "operatorId", auditOperatorId);
      int updated = fileGovernanceRepository.updateFileStatus(
          tenantId, fileId, currentStatus, nextStatus, updateMetadata);
      if (updated <= 0) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT,
            ResultCode.STATE_CONFLICT.detailKey(),
            "file status changed concurrently, expected " + currentStatus);
      }
      String operatorType = resolveOperatorType(command.operatorId());
      String auditActorId = command.operatorId();
      String traceId = command.traceId();
      FileGovernanceRepository.OperationDetailView auditDetail =
          fileGovernanceRepository.operationDetail(
              currentStatus, nextStatus, command.operatorId(), command.reason());
      FileGovernanceRepository.FileAuditCommand auditCommand =
          new FileGovernanceRepository.FileAuditCommand(
              command.tenantId(),
              command.fileId(),
              operationType,
              STATUS_SUCCESS,
              new FileGovernanceRepository.FileAuditActor(operatorType, auditActorId),
              traceId,
              auditDetail);
      fileGovernanceRepository.appendAudit(auditCommand);
      return nextStatus;
    } catch (RuntimeException exception) {
      String operatorType = resolveOperatorType(command.operatorId());
      String auditActorId = command.operatorId();
      String traceId = command.traceId();
      String auditCurrentStatus = Objects.requireNonNullElse(currentStatus, "");
      String auditNextStatus = Objects.requireNonNullElse(nextStatus, "");
      String auditReason = Objects.requireNonNullElse(command.reason(), "");
      String errorMessage = Objects.requireNonNullElse(
          exception.getMessage(), exception.getClass().getSimpleName());
      Map<String, Object> auditDetail = Map.of(
          "currentStatus", auditCurrentStatus,
          "nextStatus", auditNextStatus,
          "reason", auditReason,
          "errorMessage", errorMessage);
      FileGovernanceRepository.FileAuditCommand auditCommand =
          new FileGovernanceRepository.FileAuditCommand(
              command.tenantId(),
              command.fileId(),
              operationType,
              "FAILED",
              new FileGovernanceRepository.FileAuditActor(operatorType, auditActorId),
              traceId,
              auditDetail);
      fileGovernanceRepository.appendAudit(auditCommand);
      throw exception;
    }
  }

  private boolean matchesRedispatchTarget(JobTaskEntity task, Long fileId, String channelCode) {
    RedispatchTarget target = parseRedispatchTarget(task);
    return EmptyChecks.isNotNull(target)
        && Objects.equals(fileId, target.fileId())
        && Objects.equals(channelCode, target.channelCode());
  }

  private RedispatchTarget parseRedispatchTarget(JobTaskEntity task) {
    if (!Texts.hasText(task.getTaskPayload())) {
      return null;
    }
    try {
      Object value = JsonUtils.fromJson(task.getTaskPayload(), Object.class);
      if (!(value instanceof Map<?, ?> payload)) {
        return null;
      }
      // Worker 优先使用文件束绑定，避免普通字段遮盖实际投递目标。
      Object bundleFile = payload.get("bundleSourceFileId");
      Object bundleChannel = payload.get("bundleTargetRef");
      Object payloadFile = EmptyChecks.isNotNull(bundleFile) ? bundleFile : payload.get("fileId");
      Object payloadChannel =
          EmptyChecks.isNotNull(bundleChannel) ? bundleChannel : payload.get("channelCode");
      if (EmptyChecks.isNull(payloadFile)
          || EmptyChecks.isNull(payloadChannel)
          || !Texts.hasText(payloadChannel.toString())) {
        return null;
      }
      Long fileId = Long.valueOf(payloadFile.toString());
      return new RedispatchTarget(fileId, payloadChannel.toString());
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private record RedispatchTarget(Long fileId, String channelCode) {}

  private JobTaskEntity resolveDispatchTask(
      String tenantId, Long jobInstanceId, Long fileId, String channelCode) {
    JobTaskQuery instanceTaskQuery = JobTaskQuery.ofJobInstance(tenantId, jobInstanceId);
    List<JobTaskEntity> tasks = jobTaskMapper.selectByQuery(instanceTaskQuery).stream()
        .filter(task -> "DISPATCH".equalsIgnoreCase(task.getTaskType()))
        .toList();
    if (EmptyChecks.isEmpty(tasks)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.dispatch.task_not_found");
    }
    List<JobStepInstanceEntity> steps =
        jobStepInstanceMapper.selectByJobInstanceId(tenantId, jobInstanceId);
    Map<Long, Long> filesByTask = new LinkedHashMap<>();
    for (JobStepInstanceEntity step : steps) {
      filesByTask.put(step.getJobTaskId(), step.getRelatedFileId());
    }
    if (tasks.size() == 1) {
      JobTaskEntity onlyTask = tasks.getFirst();
      Long recordedFileId = filesByTask.get(onlyTask.getId());
      if (!Objects.equals(recordedFileId, fileId)) {
        throw BizException.of(
            ResultCode.STATE_CONFLICT, "error.dispatch.redispatch_task_ambiguous");
      }
      return onlyTask;
    }
    if (tasks.stream().anyMatch(task -> EmptyChecks.isNull(filesByTask.get(task.getId())))) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.dispatch.redispatch_task_ambiguous");
    }
    List<JobTaskEntity> fileTasks = tasks.stream()
        .filter(task -> Objects.equals(fileId, filesByTask.get(task.getId())))
        .toList();
    if (fileTasks.stream().anyMatch(task -> {
      RedispatchTarget target = parseRedispatchTarget(task);
      return EmptyChecks.isNull(target) || !Objects.equals(fileId, target.fileId());
    })) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.dispatch.redispatch_task_ambiguous");
    }
    List<JobTaskEntity> matching = fileTasks.stream()
        .filter(task -> matchesRedispatchTarget(task, fileId, channelCode))
        .toList();
    if (matching.size() != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.dispatch.redispatch_task_ambiguous");
    }
    return matching.getFirst();
  }

  private Map<String, Object> buildRedispatchDetail(
      FileGovernanceViews.DispatchRecordView dispatchRecord,
      JobTaskEntity task,
      JobPartitionEntity partition,
      FileGovernanceCommand command) {
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("dispatchRecordId", dispatchRecord.id());
    detail.put("channelCode", dispatchRecord.channelCode());
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
    Guard.require(EmptyChecks.isNotNull(command), "file governance command is required");
    if (!Texts.hasText(command.tenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (EmptyChecks.isNull(command.fileId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.file.id_required");
    }
  }

  private void validateArrivalGroupCommand(ArrivalGroupGovernanceCommand command) {
    Guard.require(EmptyChecks.isNotNull(command), "arrival group command is required");
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
    Guard.require(EmptyChecks.isNotNull(command), "file upload session command is required");
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

  private Map<String, Object> uploadGroupMetadata(
      FileUploadSessionCommand command, String fileName) {
    boolean bindingRequested = Texts.hasText(command.calendarCode())
        || EmptyChecks.isNotNull(command.workflowDefinitionId())
        || Texts.hasText(command.fileGroupCode())
        || EmptyChecks.isNotNull(command.bizDate())
        || EmptyChecks.isNotEmpty(command.requiredFileNames())
        || Texts.hasText(command.bundleTemplateCode());
    if (!bindingRequested) {
      return Map.of();
    }
    if (!Texts.hasText(command.calendarCode())
        || EmptyChecks.isNull(command.workflowDefinitionId())
        || !Texts.hasText(command.fileGroupCode())
        || EmptyChecks.isNull(command.bizDate())
        || EmptyChecks.isEmpty(command.requiredFileNames())
        || command.requiredFileNames().size() > 100
        || !Texts.hasText(command.bundleTemplateCode())
        || command.bundleTemplateCode().trim().length() > 128) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "manual bundle upload requires an enabled target, business date, required files and template");
    }
    FileGroupUploadTarget target = fileGroupExpectationService.requireEnabledUploadTarget(
        command.tenantId(),
        command.calendarCode(),
        command.workflowDefinitionId(),
        command.fileGroupCode());
    LinkedHashSet<String> requiredFileNames = new LinkedHashSet<>();
    for (String requiredFileName : command.requiredFileNames()) {
      if (!Texts.hasText(requiredFileName)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            ResultCode.INVALID_ARGUMENT.detailKey(),
            "required file names must not contain blank values");
      }
      String normalized = safeFileName(requiredFileName);
      if (normalized.contains(",") || !requiredFileNames.add(normalized)) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            ResultCode.INVALID_ARGUMENT.detailKey(),
            "required file names must be unique and comma-free after normalization");
      }
    }
    if (!requiredFileNames.contains(fileName)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT,
          ResultCode.INVALID_ARGUMENT.detailKey(),
          "uploaded file must be included in required file names");
    }
    List<String> canonicalRequiredFiles = requiredFileNames.stream().sorted().toList();
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("calendarCode", target.calendarCode());
    metadata.put("fileGroupCode", target.fileGroupCode());
    metadata.put("bizDate", command.bizDate().toString());
    metadata.put("requiredFileSet", String.join(",", canonicalRequiredFiles));
    metadata.put("bundleWorkflowCode", target.workflowCode());
    metadata.put("bundleTemplateCode", command.bundleTemplateCode().trim());
    return metadata;
  }

  private String resolveOperatorType(String operatorId) {
    return Texts.hasText(operatorId) ? "USER" : "API";
  }

  private String safeFileName(String fileName) {
    String fileNameWithFallback = Nullables.coalesce(fileName, "");
    String cleaned = fileNameWithFallback.trim();
    cleaned = cleaned.replace('\\', '/');
    int lastSlash = cleaned.lastIndexOf('/');
    if (lastSlash >= 0) {
      cleaned = cleaned.substring(lastSlash + 1);
    }
    cleaned = cleaned.replaceAll("[^A-Za-z0-9._-]", "_");
    cleaned = cleaned.replaceAll("_+", "_");
    boolean storagePathSegmentIsInvalid =
        !Texts.hasText(cleaned) || ".".equals(cleaned) || "..".equals(cleaned);
    if (storagePathSegmentIsInvalid) {
      return "upload.bin";
    }
    return cleaned.length() <= 128 ? cleaned : cleaned.substring(cleaned.length() - 128);
  }

  private String safeKeySegment(String value) {
    String valueWithFallback = Nullables.coalesce(value, "");
    String cleaned = valueWithFallback.replaceAll("[^A-Za-z0-9._-]", "_");
    return Texts.hasText(cleaned) ? cleaned : "tenant";
  }

  private String safeUrlQuery(String value) {
    return EmptyChecks.isNull(value) ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String fileFormatType(String fileName) {
    String ext = "";
    int dot = fileName.lastIndexOf('.');
    if (dot >= 0 && dot < fileName.length() - 1) {
      String extension = fileName.substring(dot + 1);
      ext = extension.toLowerCase(Locale.ROOT);
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
