package io.github.pinpols.batch.worker.exports.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.enums.FileAuditOperationType;
import io.github.pinpols.batch.common.enums.FileChecksumType;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.enums.OperationResult;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.logging.AuditLogConstants;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.plugin.ExportDataContext;
import io.github.pinpols.batch.common.plugin.ExportDataPlugin;
import io.github.pinpols.batch.common.plugin.IdempotencyCapability;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.EncodingUtils;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.FileAuditParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.core.support.TaskExecutionClient;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportStage;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import io.github.pinpols.batch.worker.exports.plugin.ExportDataPluginRegistry;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 导出注册阶段：在平台创建 file_record，并将文件与 pipeline 实例绑定，触发插件 onRegistered 回调。 */
@Component
@SuppressWarnings("java:S2259")
public class RegisterStep implements ExportStageStep {

  private static final String ERROR_CODE_REGISTER_INVALID = "EXPORT_REGISTER_INVALID";

  private static final String KEY_OBJECT_NAME = PipelineRuntimeKeys.OBJECT_NAME;
  private static final String KEY_TOTAL_AMOUNT = "totalAmount";

  private static final Set<String> RESERVED_METADATA_KEYS = Set.of(
      PipelineRuntimeKeys.RECORD_COUNT,
      KEY_TOTAL_AMOUNT,
      "templateCode",
      KEY_OBJECT_NAME,
      PipelineRuntimeKeys.EXPORT_SNAPSHOT);

  private static final ObjectMapper ERROR_OBJECT_MAPPER = JsonUtils.newDefaultMapper();

  private final PlatformFileRecordRepository fileRecords;
  private final PlatformPipelineRunRepository pipelineRuns;
  private final PlatformFileAuditRepository fileAudits;
  private final ExportDataPluginRegistry exportDataPluginRegistry;
  private final S3StorageProperties s3StorageProperties;
  private final TaskExecutionClient taskExecutionClient;

  public RegisterStep(
      PlatformFileRecordRepository fileRecords,
      PlatformPipelineRunRepository pipelineRuns,
      PlatformFileAuditRepository fileAudits,
      ExportDataPluginRegistry exportDataPluginRegistry,
      S3StorageProperties s3StorageProperties,
      TaskExecutionClient taskExecutionClient) {
    this.fileRecords = fileRecords;
    this.pipelineRuns = pipelineRuns;
    this.fileAudits = fileAudits;
    this.exportDataPluginRegistry = exportDataPluginRegistry;
    this.s3StorageProperties = s3StorageProperties;
    this.taskExecutionClient = taskExecutionClient;
  }

  @Override
  public ExportStage stage() {
    return ExportStage.REGISTER;
  }

  @Override
  public ExportStageResult execute(ExportJobContext context) {
    // ADR-026: 演练模式不创建 file_record / 不调 plugin.onRegistered（避免污染平台 file 视图）。
    Map<String, Object> dryRunAttributes =
        EmptyChecks.isNull(context) ? null : context.getAttributes();
    boolean dryRun = DryRunGuard.fromAttributes(dryRunAttributes).isDryRun();
    if (dryRun) {
      return ExportStageResult.success(stage());
    }
    if (context == null || context.getAttributes().get(KEY_OBJECT_NAME) == null) {
      return ExportStageResult.failure(
          stage(),
          ERROR_CODE_REGISTER_INVALID,
          "error.export.register.invalid",
          new Object[] {"objectName missing"},
          "objectName missing",
          ERROR_OBJECT_MAPPER);
    }
    Map<String, Object> attrs = context.getAttributes();
    Object payload = attrs.get(ExportRuntimeKeys.EXPORT_PAYLOAD);
    Object batchObject = attrs.get(ExportRuntimeKeys.EXPORT_BATCH);
    if (!(payload instanceof ExportPayload exportPayload)
        || !(batchObject instanceof Map<?, ?> batch)) {
      return ExportStageResult.failure(
          stage(),
          ERROR_CODE_REGISTER_INVALID,
          "error.export.register.invalid",
          new Object[] {"export context missing"},
          "export context missing",
          ERROR_OBJECT_MAPPER);
    }
    String objectName = String.valueOf(attrs.get(KEY_OBJECT_NAME));
    String fileName = String.valueOf(attrs.get(PipelineRuntimeKeys.FILE_NAME));
    Object configuredFileFormatType = attrs.getOrDefault(
        PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, FileTemplateFormat.JSON.code());
    String fileFormatType = String.valueOf(configuredFileFormatType);
    String bucket = s3StorageProperties.getBucket();
    String expectedChecksum = nullableText(attrs.get(PipelineRuntimeKeys.CHECKSUM_VALUE));
    // 相同路径的 file_record 已存在时进行幂等复用（STORE → REGISTER 重试场景）
    if (fileRecords.existsFileRecordByStoragePath(context.getTenantId(), bucket, objectName)) {
      Map<String, Object> existing =
          fileRecords.loadFileRecordByStoragePath(context.getTenantId(), bucket, objectName);
      return reuseMatchingArtifact(context, batch, existing, expectedChecksum);
    }

    Map<String, Object> metadata = buildFileMetadata(context, exportPayload, objectName, attrs);
    Long fileSizeBytes =
        PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.FILE_SIZE_BYTES));
    String checksumType = String.valueOf(
        attrs.getOrDefault(PipelineRuntimeKeys.CHECKSUM_TYPE, FileChecksumType.SHA_256.code()));
    FileRecordParam.FileRecordParamBuilder fileRecordBuilder = FileRecordParam.builder()
        .tenantId(context.getTenantId())
        .fileCode(exportPayload.fileCode());
    String businessType =
        Texts.hasText(exportPayload.bizType()) ? exportPayload.bizType() : context.getJobCode();
    fileRecordBuilder
        .bizType(businessType)
        .fileCategory("OUTPUT")
        .fileName(fileName)
        .originalFileName(fileName)
        .fileFormatType(fileFormatType)
        .charset(exportCharset(attrs))
        .fileSizeBytes(EmptyChecks.isNull(fileSizeBytes) ? 0L : fileSizeBytes)
        .checksumType(checksumType);
    String checksumValue = nullableText(attrs.get(PipelineRuntimeKeys.CHECKSUM_VALUE));
    fileRecordBuilder
        .checksumValue(checksumValue)
        .storageType("S3")
        .storagePath(objectName)
        .storageBucket(bucket)
        .fileVersion(null);
    String payloadBizDate = exportPayload.bizDate();
    String contextBizDate = context.getBizDate();
    LocalDate fileBizDate = parseBizDate(payloadBizDate, contextBizDate);
    fileRecordBuilder
        .bizDate(fileBizDate)
        .sourceType("GENERATED")
        .sourceRef(exportPayload.batchNo())
        .fileStatus(FileStatus.GENERATED.code());
    String fileRecordTraceId = String.valueOf(attrs.get(PipelineRuntimeKeys.TRACE_ID));
    FileRecordParam fileRecordParam =
        fileRecordBuilder.traceId(fileRecordTraceId).metadata(metadata).build();
    Long fileId = fileRecords.createFileRecord(fileRecordParam);
    Map<String, Object> fileRecord = fileRecords.loadFileRecord(context.getTenantId(), fileId);
    attrs.put(PipelineRuntimeKeys.FILE_ID, fileId);
    attrs.put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
    context.setFileId(String.valueOf(fileId));
    String tenantId = context.getTenantId();
    Object pipelineInstanceIdValue = attrs.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID);
    Long pipelineInstanceId = PlatformRuntimeValues.toLong(pipelineInstanceIdValue);
    pipelineRuns.bindFileToPipelineInstance(tenantId, pipelineInstanceId, fileId);
    Long batchId = PlatformRuntimeValues.toLong(batch.get("id"));
    Integer exportVersion =
        fileRecord.get("file_generation_no") instanceof Number number ? number.intValue() : 1;
    String traceId = String.valueOf(attrs.get(PipelineRuntimeKeys.TRACE_ID));
    ExportDataPlugin exportPlugin = resolvePlugin(context);
    ExportStageResult callbackFailure = invokeRegistrationCallback(
        context, exportPlugin, exportPayload, batchId, exportVersion, traceId);
    if (EmptyChecks.isNotNull(callbackFailure)) {
      return callbackFailure;
    }
    return ExportStageResult.success(stage());
  }

  private ExportStageResult reuseMatchingArtifact(
      ExportJobContext context,
      Map<?, ?> batch,
      Map<String, Object> existing,
      String expectedChecksum) {
    if (!ExportArtifactIdentity.belongsToJob(context, existing)) {
      String reason = "registered artifact belongs to another or unknown Job instance";
      return ExportStageResult.failure(
          stage(),
          "EXPORT_REGISTER_ARTIFACT_CONFLICT",
          "error.export.store.artifact_conflict",
          new Object[] {reason},
          reason,
          ERROR_OBJECT_MAPPER);
    }
    String existingChecksum = nullableText(existing.get("checksum_value"));
    boolean existingFileRecordHasChecksumConflict = Texts.hasText(expectedChecksum)
        && Texts.hasText(existingChecksum)
        && !expectedChecksum.equalsIgnoreCase(existingChecksum);
    if (existingFileRecordHasChecksumConflict) {
      return ExportStageResult.failure(
          stage(),
          "EXPORT_REGISTER_CHECKSUM_CONFLICT",
          "error.export.register.checksum_conflict",
          new Object[0],
          "existing file_record checksum does not match expected checksum",
          ERROR_OBJECT_MAPPER);
    }
    return reuseExistingFileRecord(context, batch, existing);
  }

  /** 用户元数据合并后再固定产物所属实例，避免它覆盖平台维护的血缘身份。 */
  private Map<String, Object> buildFileMetadata(
      ExportJobContext context,
      ExportPayload exportPayload,
      String objectName,
      Map<String, Object> attrs) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(PipelineRuntimeKeys.RECORD_COUNT, attrs.get(PipelineRuntimeKeys.RECORD_COUNT));
    metadata.put(KEY_TOTAL_AMOUNT, attrs.get(KEY_TOTAL_AMOUNT));
    metadata.put("templateCode", exportPayload.templateCode());
    metadata.put(KEY_OBJECT_NAME, objectName);
    mergeSecurityMetadata(metadata, attrs);
    if (EmptyChecks.isNotNull(attrs.get(PipelineRuntimeKeys.EXPORT_SNAPSHOT))) {
      metadata.put(
          PipelineRuntimeKeys.EXPORT_SNAPSHOT, attrs.get(PipelineRuntimeKeys.EXPORT_SNAPSHOT));
    }
    // 编码资产登记：最终目标编码（file_record.charset 列）+ 换行符 / BOM 策略（metadata），
    // 与 GENERATE 实际写出的字节一致（设计 §9.4 编码规则）。
    if (EmptyChecks.isNotNull(attrs.get(ExportRuntimeKeys.EXPORT_LINE_SEPARATOR))) {
      metadata.put(
          ExportRuntimeKeys.EXPORT_LINE_SEPARATOR,
          String.valueOf(attrs.get(ExportRuntimeKeys.EXPORT_LINE_SEPARATOR)));
    }
    if (EmptyChecks.isNotNull(attrs.get(ExportRuntimeKeys.EXPORT_WITH_BOM))) {
      metadata.put(ExportRuntimeKeys.EXPORT_WITH_BOM, attrs.get(ExportRuntimeKeys.EXPORT_WITH_BOM));
    }
    mergeUserMetadata(metadata, exportPayload.metadata());
    Long jobInstanceId = ExportArtifactIdentity.jobInstanceId(context);
    if (EmptyChecks.isNull(jobInstanceId)) {
      metadata.remove(PipelineRuntimeKeys.JOB_INSTANCE_ID);
    } else {
      metadata.put(PipelineRuntimeKeys.JOB_INSTANCE_ID, jobInstanceId);
    }
    return metadata;
  }

  /** 登记 GENERATE 实际使用的目标编码；未显式下发（旧实例重放等）回退 UTF-8。 */
  private String exportCharset(Map<String, Object> attrs) {
    String raw = nullableText(attrs.get(ExportRuntimeKeys.EXPORT_CHARSET));
    return raw == null ? StandardCharsets.UTF_8.name() : EncodingUtils.normalize(raw);
  }

  private ExportStageResult reuseExistingFileRecord(
      ExportJobContext context, Map<?, ?> batch, Map<String, Object> existing) {
    Long fileId = PlatformRuntimeValues.toLong(existing.get("id"));
    if (fileId == null) {
      return ExportStageResult.failure(
          stage(),
          "EXPORT_REGISTER_REUSE_INVALID",
          "error.export.register.reuse_invalid",
          new Object[0],
          "existing file id missing",
          ERROR_OBJECT_MAPPER);
    }
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
    context.getAttributes().put(PipelineRuntimeKeys.FILE_RECORD, existing);
    context.setFileId(String.valueOf(fileId));
    String tenantId = context.getTenantId();
    Map<String, Object> attributes = context.getAttributes();
    Object pipelineInstanceIdValue = attributes.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID);
    Long pipelineInstanceId = PlatformRuntimeValues.toLong(pipelineInstanceIdValue);
    pipelineRuns.bindFileToPipelineInstance(tenantId, pipelineInstanceId, fileId);
    Long batchId = PlatformRuntimeValues.toLong(batch.get("id"));
    Integer exportVersion =
        existing.get("file_generation_no") instanceof Number number ? number.intValue() : 1;
    String traceId = String.valueOf(context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID));
    ExportPayload exportPayload2 =
        (ExportPayload) context.getAttributes().get(ExportRuntimeKeys.EXPORT_PAYLOAD);
    ExportDataPlugin exportPlugin = resolvePlugin(context);
    ExportStageResult callbackFailure = invokeRegistrationCallback(
        context, exportPlugin, exportPayload2, batchId, exportVersion, traceId);
    if (EmptyChecks.isNotNull(callbackFailure)) {
      return callbackFailure;
    }
    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("reason", "STORE_TO_REGISTER_RETRY");
    audit.put(KEY_OBJECT_NAME, context.getAttributes().get(KEY_OBJECT_NAME));
    Object auditTraceId = context.getAttributes().get(PipelineRuntimeKeys.TRACE_ID);
    String auditTraceIdText = String.valueOf(auditTraceId);
    Object auditObjectName = context.getAttributes().get(KEY_OBJECT_NAME);
    String auditObjectNameText = String.valueOf(auditObjectName);
    fileAudits.appendAudit(FileAuditParam.builder()
        .fileId(fileId)
        .tenantId(context.getTenantId())
        .operationType(FileAuditOperationType.EXPORT_REGISTER.code())
        .operationResult(OperationResult.SUCCESS.code())
        .operatorType(AuditLogConstants.OPERATOR_TYPE_SYSTEM)
        .operatorId(context.getWorkerId())
        .traceId(auditTraceIdText)
        .evidenceRef(auditObjectNameText)
        .detailSummary(audit)
        .build());
    return ExportStageResult.success(stage());
  }

  private ExportStageResult invokeRegistrationCallback(
      ExportJobContext context,
      ExportDataPlugin plugin,
      ExportPayload payload,
      Long batchId,
      int exportVersion,
      String traceId) {
    if (!isReplaySafe(plugin.registrationCallbackIdempotencyCapability())
        && !markNonIdempotentExecutionStarted(context)) {
      return ExportStageResult.failure(
          stage(),
          TaskErrorCodes.EXTERNAL_OUTCOME_UNKNOWN,
          "error.export.register.failed",
          new Object[] {"non-idempotent plugin callback execution fence was not confirmed"},
          "non-idempotent plugin callback execution fence was not confirmed",
          ERROR_OBJECT_MAPPER);
    }
    ExportDataContext dataContext = buildDataContext(context, payload);
    plugin.onRegistered(dataContext, batchId, exportVersion, traceId);
    return null;
  }

  private boolean markNonIdempotentExecutionStarted(ExportJobContext context) {
    Map<String, Object> attributes = context.getAttributes();
    Long taskId = PlatformRuntimeValues.toLong(attributes.get(PipelineRuntimeKeys.TASK_ID));
    Object invocationValue = attributes.get(PipelineRuntimeKeys.PARTITION_INVOCATION_ID);
    if (EmptyChecks.isNull(taskExecutionClient)) {
      return false;
    }
    if (EmptyChecks.isNull(taskId)) {
      return false;
    }
    if (!Texts.hasText(context.getWorkerId())) {
      return false;
    }
    if (!(invocationValue instanceof String invocationId)) {
      return false;
    }
    if (!Texts.hasText(invocationId)) {
      return false;
    }
    try {
      return taskExecutionClient.markNonIdempotentExecutionStarted(
          context.getTenantId(), taskId, context.getWorkerId(), invocationId);
    } catch (RuntimeException exception) {
      SwallowedExceptionLogger.warn(
          RegisterStep.class, "catch:nonIdempotentPluginExecutionFence", exception);
      return false;
    }
  }

  private static boolean isReplaySafe(IdempotencyCapability capability) {
    return capability == IdempotencyCapability.IDEMPOTENT_BY_UNIQUE_CONSTRAINT
        || capability == IdempotencyCapability.IDEMPOTENT_BY_PLUGIN_LOGIC;
  }

  private LocalDate parseBizDate(String payloadBizDate, String fallbackBizDate) {
    String bizDate = Texts.hasText(payloadBizDate) ? payloadBizDate : fallbackBizDate;
    if (!Texts.hasText(bizDate)) {
      return null;
    }
    try {
      return LocalDate.parse(bizDate);
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(RegisterStep.class, "catch:Exception", ignored);

      return null;
    }
  }

  private String nullableText(Object value) {
    if (value == null) {
      return null;
    }
    String text = String.valueOf(value);
    return Texts.hasText(text) && !"null".equalsIgnoreCase(text) ? text : null;
  }

  private void mergeUserMetadata(Map<String, Object> target, Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      if (RESERVED_METADATA_KEYS.contains(entry.getKey())) {
        continue;
      }
      target.put(entry.getKey(), entry.getValue());
    }
  }

  private void mergeSecurityMetadata(Map<String, Object> target, Map<String, Object> attributes) {
    if (attributes == null || attributes.isEmpty()) {
      return;
    }
    putIfPresent(target, attributes, "contentEncryptionEnabled");
    putIfPresent(target, attributes, "encryptionKeyRef");
    putIfPresent(target, attributes, "encryptionObjectVersion");
    putIfPresent(target, attributes, "downloadRequiresApproval");
  }

  private void putIfPresent(Map<String, Object> target, Map<String, Object> source, String key) {
    Object value = source.get(key);
    if (value != null) {
      target.put(key, value);
    }
  }

  private ExportDataPlugin resolvePlugin(ExportJobContext context) {
    String exportDataRef =
        nullableText(context.getAttributes().get(ExportRuntimeKeys.EXPORT_DATA_REF));
    return exportDataPluginRegistry.require(exportDataRef);
  }

  private ExportDataContext buildDataContext(ExportJobContext context, ExportPayload payload) {
    Object tc = context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG);
    Map<String, Object> templateConfig = new LinkedHashMap<>();
    if (tc instanceof Map<?, ?> m) {
      m.forEach((k, v) -> templateConfig.put(String.valueOf(k), v));
    }
    Object snap = context.getAttributes().get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    if (snap instanceof Map<?, ?> m) {
      m.forEach((k, v) -> snapshot.put(String.valueOf(k), v));
    }
    return new ExportDataContext(
        context.getTenantId(),
        context.getJobCode(),
        payload == null ? null : payload.batchNo(),
        payload == null ? null : payload.templateCode(),
        templateConfig,
        snapshot);
  }
}
