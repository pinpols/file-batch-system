package io.github.pinpols.batch.worker.imports.stage;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileStatus;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.service.DryRunGuard;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.EncodingUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam;
import io.github.pinpols.batch.worker.core.infrastructure.FileRecordParam.FileRecordParamBuilder;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformPipelineRunRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues;
import io.github.pinpols.batch.worker.core.support.FileTemplateConfigRequest;
import io.github.pinpols.batch.worker.core.support.FrozenFileTemplateResolver;
import io.github.pinpols.batch.worker.imports.config.WorkerImportPayloadProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportPayload;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.domain.ImportStageResult;
import io.github.pinpols.batch.worker.imports.domain.ImportWorkerType;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Import pipeline 的第一阶段：接收并登记文件记录。
 *
 * <p><b>主要职责</b>：
 *
 * <ul>
 *   <li>在任何内存分配前拒绝超过 {@code batch.worker.import.max-payload-size-mb}（默认 100MB）的 payload。
 *   <li>解析 {@link ImportPayload}（优先从上下文取，否则从 JSON rawPayload 解析；支持嵌套 {@code content} 字段回填）。
 *   <li>若上下文中尚无 fileId，创建 {@code file_record}（status=RECEIVED），写入模板安全元数据和用户元数据， 并将文件绑定到当前 pipeline
 *       实例。
 * </ul>
 *
 * <p>保留字段（{@code templateCode}、{@code taskId} 等）不允许被用户 metadata 覆盖。
 */
@Slf4j
@Component
@SuppressWarnings("java:S2259")
public class ReceiveStep implements ImportStageStep {

  private static final Set<String> RESERVED_METADATA_KEYS = Set.of(
      "templateCode",
      "sourceType",
      "headerRows",
      "footerRows",
      PipelineRuntimeKeys.TASK_ID,
      "withHeader");

  private final PlatformFileRecordRepository fileRecords;
  private final FrozenFileTemplateResolver templateResolver;
  private final PlatformPipelineRunRepository pipelineRuns;
  private final BatchSecurityProperties batchSecurityProperties;
  private final ObjectMapper objectMapper;
  private final WorkerImportPayloadProperties payloadProperties;

  private long maxPayloadSizeBytes;

  public ReceiveStep(
      PlatformFileRecordRepository fileRecords,
      FrozenFileTemplateResolver templateResolver,
      PlatformPipelineRunRepository pipelineRuns,
      BatchSecurityProperties batchSecurityProperties,
      ObjectMapper objectMapper,
      WorkerImportPayloadProperties payloadProperties) {
    this.fileRecords = fileRecords;
    this.templateResolver = templateResolver;
    this.pipelineRuns = pipelineRuns;
    this.batchSecurityProperties = batchSecurityProperties;
    this.objectMapper = objectMapper;
    this.payloadProperties = payloadProperties;
  }

  @PostConstruct
  void init() {
    int maxPayloadMb = payloadProperties.getMaxPayloadSizeMb();
    double heapRatio = payloadProperties.getPayloadHeapRatio();
    long configured = maxPayloadMb <= 0 ? Long.MAX_VALUE : (long) maxPayloadMb * 1024 * 1024;
    // 堆联动：JVM 最大堆的 payload-heap-ratio 比例，预留空间给 PREPROCESS 的 byte[] / String / decode 副本
    long heapCap =
        heapRatio > 0 ? (long) (Runtime.getRuntime().maxMemory() * heapRatio) : Long.MAX_VALUE;
    this.maxPayloadSizeBytes = Math.min(configured, heapCap);
    log.info(
        "[ReceiveStep] maxPayloadSizeBytes={} (configured={}MB, heap-cap={}MB @ ratio={})",
        maxPayloadSizeBytes,
        maxPayloadMb,
        heapCap == Long.MAX_VALUE ? -1 : heapCap / 1024 / 1024,
        heapRatio);
  }

  @Override
  public ImportStage stage() {
    return ImportStage.RECEIVE;
  }

  @Override
  public ImportStageResult execute(ImportJobContext context) {
    if (context == null
        || !Texts.hasText(context.getTenantId())
        || !Texts.hasText(context.getRawPayload())) {
      return ImportStageResult.failure(
          stage(),
          "IMPORT_RECEIVE_INVALID",
          "error.import.receive.invalid",
          new Object[0],
          "tenantId or payload is blank",
          objectMapper);
    }
    // C-5/D-4: 在任何堆内存分配前拒绝超大 payload。
    // R2-P1-11：之前用 String.length() 当 bytes 比；UTF-16 char count 与 UTF-8 字节数对 CJK/emoji 差 ~2-3×，
    // file_size_bytes 列语义错。改用 UTF-8 byte 长度做硬比较 + 持久化（rawPayload 即将进 createFileRecord，
    // 这里一次性 getBytes 也是下游本来要做的，无额外内存浪费）。
    long payloadLength = context.getRawPayload().getBytes(StandardCharsets.UTF_8).length;
    if (payloadLength > maxPayloadSizeBytes) {
      return ImportStageResult.failure(
          stage(),
          "IMPORT_RECEIVE_TOO_LARGE",
          "error.import.receive.too_large",
          new Object[] {payloadLength, maxPayloadSizeBytes},
          "payload size "
              + payloadLength
              + " bytes exceeds limit "
              + maxPayloadSizeBytes
              + " bytes",
          objectMapper);
    }
    ImportPayload importPayload = resolvePayload(context);
    Map<String, Object> attrs = context.getAttributes();
    Map<String, Object> templateConfig;
    try {
      templateConfig = resolveTemplateConfig(context, importPayload);
    } catch (BizException invalidTemplate) {
      return ImportStageResult.failure(
          stage(), TaskErrorCodes.FROZEN_FILE_TEMPLATE_INVALID, invalidTemplate, objectMapper);
    }
    Long existingFileId = PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.FILE_ID));
    if (EmptyChecks.isNull(existingFileId) && !DryRunGuard.fromAttributes(attrs).isDryRun()) {
      createAndBindReceivedFile(context, importPayload, templateConfig, payloadLength, attrs);
    } else if (EmptyChecks.isNotNull(existingFileId)) {
      // ADR-046 文件束:复用 scanner 预建的 file_record(orchestrator 把 partition.source_file_id
      // 落进 task payload 的 sourceFileId,worker-core 据此设了 FILE_ID,基类已加载 FILE_RECORD)。
      // 束的 task payload 只带 sourceFileId/templateCode,不带存储坐标——从 file_record 回填
      // storage_path/bucket/format/charset,PREPROCESS 才能定位并下载文件。
      // 仅填 payload 中为空的字段,对「payload 自带完整存储信息」的既有 existing-file 流是无副作用的幂等回填。
      importPayload =
          enrichFromFileRecord(importPayload, attrs.get(PipelineRuntimeKeys.FILE_RECORD));
    }
    if (EmptyChecks.isNotEmpty(templateConfig)) {
      attrs.put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
    }
    attrs.put(PipelineRuntimeKeys.IMPORT_PAYLOAD, importPayload);
    return ImportStageResult.success(stage());
  }

  /** 冻结模板通过后才登记新文件；回读、绑定 Pipeline 与发布上下文的先后关系保持一致。 */
  private void createAndBindReceivedFile(
      ImportJobContext context,
      ImportPayload importPayload,
      Map<String, Object> templateConfig,
      long payloadLength,
      Map<String, Object> attrs) {
    Object traceIdValue = attrs.getOrDefault(PipelineRuntimeKeys.TRACE_ID, context.getWorkerId());
    String traceId = String.valueOf(traceIdValue);
    String executionRef = resolveExecutionRef(attrs, traceId);
    String fileFormatType =
        normalizeFileFormat(importPayload.fileFormatType(), context.getRawPayload());
    String fileName = resolveFileName(importPayload, fileFormatType, traceId);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("templateCode", importPayload.templateCode());
    String metadataSourceType = defaultText(importPayload.sourceType(), "UPLOAD");
    metadata.put("sourceType", metadataSourceType);
    metadata.put("headerRows", importPayload.headerRows());
    metadata.put("footerRows", importPayload.footerRows());
    metadata.put(PipelineRuntimeKeys.TASK_ID, attrs.get(PipelineRuntimeKeys.TASK_ID));
    metadata.put("withHeader", importPayload.withHeader());
    Map<String, Object> templateSecurity = resolveTemplateSecurity(templateConfig);
    mergeSecurityMetadata(metadata, templateSecurity);
    mergeUserMetadata(metadata, importPayload.metadata());
    FileRecordParamBuilder fileRecordBuilder = FileRecordParam.builder();
    fileRecordBuilder.tenantId(context.getTenantId()).fileCode(importPayload.fileCode());
    String businessType = defaultText(importPayload.bizType(), context.getJobCode());
    fileRecordBuilder.bizType(businessType).fileCategory("INPUT").fileName(fileName);
    String originalFileName = defaultText(importPayload.originalFileName(), fileName);
    fileRecordBuilder.originalFileName(originalFileName).fileFormatType(fileFormatType);
    String charset = defaultText(importPayload.charset(), EncodingUtils.UTF_8);
    fileRecordBuilder.charset(charset).fileSizeBytes(payloadLength);
    String checksumType = defaultText(importPayload.checksumType(), "NONE");
    fileRecordBuilder.checksumType(checksumType).checksumValue(importPayload.checksumValue());
    String storageType = defaultText(importPayload.storageType(), "LOCAL");
    fileRecordBuilder.storageType(storageType);
    String configuredStoragePath = importPayload.storagePath();
    String defaultStoragePath =
        "ingress/" + context.getTenantId() + "/" + executionRef + "/" + fileName;
    String storagePath = defaultText(configuredStoragePath, defaultStoragePath);
    FileRecordParamBuilder fileRecordWithStorage = fileRecordBuilder
        .storagePath(storagePath)
        .storageBucket(importPayload.storageBucket())
        .fileVersion(null);
    String bizDateValue = context.getBizDate();
    LocalDate bizDate = parseBizDate(bizDateValue);
    String fileRecordSourceType = defaultText(importPayload.sourceType(), "UPLOAD");
    FileRecordParam fileRecord = fileRecordWithStorage
        .bizDate(bizDate)
        .sourceType(fileRecordSourceType)
        .sourceRef(importPayload.sourceRef())
        .fileStatus(FileStatus.RECEIVED.code())
        .traceId(traceId)
        .metadata(metadata)
        .build();
    Long fileId = fileRecords.createFileRecord(fileRecord);
    attrs.put(PipelineRuntimeKeys.FILE_ID, fileId);
    Map<String, Object> persistedFileRecord =
        fileRecords.loadFileRecord(context.getTenantId(), fileId);
    attrs.put(PipelineRuntimeKeys.FILE_RECORD, persistedFileRecord);
    String tenantId = context.getTenantId();
    Long pipelineInstanceId =
        PlatformRuntimeValues.toLong(attrs.get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID));
    pipelineRuns.bindFileToPipelineInstance(tenantId, pipelineInstanceId, fileId);
    context.setFileId(String.valueOf(fileId));
  }

  Map<String, Object> resolveTemplateConfig(ImportJobContext context) {
    String templateCode = resolveTemplateCode(context);
    return resolveTemplateConfig(context, templateCode);
  }

  private Map<String, Object> resolveTemplateConfig(
      ImportJobContext context, ImportPayload importPayload) {
    return resolveTemplateConfig(context, importPayload.templateCode());
  }

  private Map<String, Object> resolveTemplateConfig(ImportJobContext context, String templateCode) {
    Map<String, Object> attributes = context.getAttributes();
    FileTemplateConfigRequest templateRequest = new FileTemplateConfigRequest(
        context.getTenantId(),
        templateCode,
        ImportWorkerType.IMPORT,
        context.getRawPayload(),
        attributes);
    return templateResolver.resolve(templateRequest);
  }

  private String resolveTemplateCode(ImportJobContext context) {
    Object payloadValue = context.getAttributes().get(PipelineRuntimeKeys.IMPORT_PAYLOAD);
    if (payloadValue instanceof ImportPayload importPayload) {
      return importPayload.templateCode();
    }
    String rawPayload = context.getRawPayload();
    if (!Texts.hasText(rawPayload)) {
      return null;
    }
    try (JsonParser parser = objectMapper.getFactory().createParser(rawPayload)) {
      if (parser.nextToken() != JsonToken.START_OBJECT) {
        throw new IllegalArgumentException("Import replay payload must be an object");
      }
      String templateCode = null;
      JsonToken token;
      while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
        if (token != JsonToken.FIELD_NAME) {
          throw new IllegalArgumentException("Import replay payload contains an invalid field");
        }
        String fieldName = parser.currentName();
        JsonToken valueToken = parser.nextToken();
        if ("templateCode".equals(fieldName)) {
          if (valueToken == JsonToken.VALUE_NULL) {
            templateCode = null;
          } else if (valueToken != JsonToken.START_OBJECT && valueToken != JsonToken.START_ARRAY) {
            templateCode = parser.getText();
          } else {
            throw new IllegalArgumentException("Import templateCode must be a scalar value");
          }
        } else {
          parser.skipChildren();
        }
      }
      if (EmptyChecks.isNotNull(parser.nextToken())) {
        throw new IllegalArgumentException("Import replay payload contains trailing content");
      }
      return templateCode;
    } catch (IOException invalidPayload) {
      throw new IllegalArgumentException("Invalid Import replay payload", invalidPayload);
    }
  }

  /**
   * 用既有 {@code file_record} 的存储坐标回填 {@link ImportPayload} 中为空的字段。只覆盖空值——payload 已带的字段优先, 保证对既有
   * existing-file 导入流零影响,仅文件束(payload 不带存储信息)从 file_record 取。
   */
  @SuppressWarnings("unchecked")
  ImportPayload enrichFromFileRecord(ImportPayload payload, Object fileRecordObj) {
    if (!(fileRecordObj instanceof Map<?, ?> fileRecord) || fileRecord.isEmpty()) {
      return payload;
    }
    Map<String, Object> asMap = objectMapper.convertValue(payload, Map.class);
    backfillBlank(asMap, "storagePath", fileRecord.get("storage_path"));
    backfillBlank(asMap, "storageBucket", fileRecord.get("storage_bucket"));
    backfillBlank(asMap, "storageType", fileRecord.get("storage_type"));
    backfillBlank(asMap, "fileFormatType", fileRecord.get("file_format_type"));
    backfillBlank(asMap, "charset", fileRecord.get("charset"));
    backfillBlank(asMap, "fileName", fileRecord.get("file_name"));
    backfillBlank(asMap, "originalFileName", fileRecord.get("original_file_name"));
    backfillBlank(asMap, "fileCode", fileRecord.get("file_code"));
    backfillBlank(asMap, "bizType", fileRecord.get("biz_type"));
    backfillBlank(asMap, PipelineRuntimeKeys.CHECKSUM_TYPE, fileRecord.get("checksum_type"));
    backfillBlank(asMap, PipelineRuntimeKeys.CHECKSUM_VALUE, fileRecord.get("checksum_value"));
    return objectMapper.convertValue(asMap, ImportPayload.class);
  }

  private static void backfillBlank(Map<String, Object> map, String key, Object value) {
    if (value == null) {
      return;
    }
    Object current = map.get(key);
    boolean currentBlank = current == null || (current instanceof String s && !Texts.hasText(s));
    if (currentBlank) {
      map.put(key, value);
    }
  }

  private Map<String, Object> resolveTemplateSecurity(Map<String, Object> template) {
    if (EmptyChecks.isEmpty(template)) {
      return Map.of();
    }
    Map<String, Object> security = new LinkedHashMap<>();
    boolean contentEncryptionEnabled = !batchSecurityProperties.isBypassMode()
        && truthy(template.get("content_encryption_enabled"));
    security.put("contentEncryptionEnabled", contentEncryptionEnabled);
    security.put("encryptionKeyRef", template.get("encryption_key_ref"));
    boolean downloadRequiresApproval = truthy(template.get("download_requires_approval"));
    security.put("downloadRequiresApproval", downloadRequiresApproval);
    boolean previewMaskingEnabled = truthy(template.get("preview_masking_enabled"));
    security.put("previewMaskingEnabled", previewMaskingEnabled);
    boolean errorLineMaskingEnabled = truthy(template.get("error_line_masking_enabled"));
    security.put("errorLineMaskingEnabled", errorLineMaskingEnabled);
    boolean logMaskingEnabled = truthy(template.get("log_masking_enabled"));
    security.put("logMaskingEnabled", logMaskingEnabled);
    security.put("maskingRuleSet", template.get("masking_rule_set"));
    return security;
  }

  private ImportPayload resolvePayload(ImportJobContext context) {
    Object existing = context.getAttributes().get(PipelineRuntimeKeys.IMPORT_PAYLOAD);
    if (existing instanceof ImportPayload importPayload) {
      return importPayload;
    }
    String rawPayload = context.getRawPayload();
    if (!Texts.hasText(rawPayload) || !rawPayload.trim().startsWith("{")) {
      return ImportPayload.empty();
    }
    try {
      ImportPayload importPayload = objectMapper.readValue(rawPayload, ImportPayload.class);
      if (importPayload == null) {
        return ImportPayload.empty();
      }

      // content 可能不在顶层（例如 params/content）。当解析出来的 content 为空时做一次递归回填。
      if (!Texts.hasText(importPayload.content())
          && !Texts.hasText(importPayload.contentBase64())) {
        JsonNode root = objectMapper.readTree(rawPayload);
        String extracted = findFirstText(root, "content");
        if (Texts.hasText(extracted)) {
          String trimmed = extracted.trim();
          // 仅接受看起来像 JSON 内容（数组或对象）的提取值。
          if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return importPayload;
          }
          @SuppressWarnings("unchecked")
          Map<String, Object> asMap = objectMapper.convertValue(importPayload, Map.class);
          asMap.put("content", trimmed);
          asMap.put("contentBase64", null);
          return objectMapper.convertValue(asMap, ImportPayload.class);
        }
      }

      return importPayload;
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(ReceiveStep.class, "catch:Exception", ignored);

      return ImportPayload.empty();
    }
  }

  // R2-P1-8：JSON 嵌套深度上限——之前 findFirstText 递归无防护，恶意/异常的 1000+ 层嵌套
  // payload 会触发 StackOverflowError（Error 非 Exception，try-catch 接不住）→ worker 线程崩溃。
  // 50 层覆盖正常业务 JSON 的最深包装（payload.body.content / metadata 嵌套），溢出即返回 null 安全降级。
  private static final int MAX_FIND_DEPTH = 50;

  private String findFirstText(JsonNode node, String fieldName) {
    if (node == null || !Texts.hasText(fieldName)) {
      return null;
    }
    // 迭代 BFS：用显式 Deque 替代递归调用栈，深度由 MAX_FIND_DEPTH 硬上限
    Deque<NodeAtDepth> queue = new ArrayDeque<>();
    queue.add(new NodeAtDepth(node, 0));
    while (!queue.isEmpty()) {
      NodeAtDepth current = queue.poll();
      JsonNode n = current.node();
      int depth = current.depth();
      if (n == null || depth > MAX_FIND_DEPTH) {
        continue;
      }
      if (n.isObject()) {
        JsonNode v = n.get(fieldName);
        if (v != null && v.isTextual()) {
          return v.asText();
        }
        for (JsonNode child : n) {
          queue.add(new NodeAtDepth(child, depth + 1));
        }
      } else if (n.isArray()) {
        for (JsonNode child : n) {
          queue.add(new NodeAtDepth(child, depth + 1));
        }
      }
    }
    return null;
  }

  private record NodeAtDepth(JsonNode node, int depth) {}

  private String resolveFileName(ImportPayload payload, String fileFormatType, String traceId) {
    if (Texts.hasText(payload.fileName())) {
      return payload.fileName();
    }
    FileTemplateFormat format = DictEnum.fromCode(FileTemplateFormat.class, fileFormatType);
    if (EmptyChecks.isNull(format)) {
      return "import-" + traceId + BatchFileConstants.DAT_SUFFIX;
    }
    return "import-"
        + traceId
        + switch (format) {
          case JSON -> BatchFileConstants.JSON_SUFFIX;
          case DELIMITED -> BatchFileConstants.CSV_SUFFIX;
          case EXCEL -> BatchFileConstants.XLSX_SUFFIX;
          default -> BatchFileConstants.DAT_SUFFIX;
        };
  }

  /** 同一批量日 session 共用 traceId；加入稳定 taskId，避免并发 entry 生成相同默认存储路径。 */
  private static String resolveExecutionRef(Map<String, Object> attributes, String traceId) {
    Object taskId = attributes.get(PipelineRuntimeKeys.TASK_ID);
    return taskId == null ? traceId : traceId + '-' + taskId;
  }

  private String normalizeFileFormat(String fileFormatType, String rawPayload) {
    if (Texts.hasText(fileFormatType)) {
      return fileFormatType.toUpperCase();
    }
    if (rawPayload != null && rawPayload.trim().startsWith("{")) {
      return FileTemplateFormat.JSON.code();
    }
    if (rawPayload != null && rawPayload.contains(",")) {
      return FileTemplateFormat.DELIMITED.code();
    }
    return FileTemplateFormat.JSON.code();
  }

  private LocalDate parseBizDate(String bizDate) {
    if (!Texts.hasText(bizDate)) {
      return null;
    }
    try {
      return LocalDate.parse(bizDate);
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(ReceiveStep.class, "catch:Exception", ignored);

      return null;
    }
  }

  private String defaultText(String value, String fallback) {
    return Texts.hasText(value) ? value : fallback;
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

  private void mergeSecurityMetadata(Map<String, Object> target, Map<String, Object> security) {
    if (security == null || security.isEmpty()) {
      return;
    }
    target.putAll(security);
  }

  private boolean truthy(Object value) {
    if (value instanceof Boolean bool) {
      return bool;
    }
    return value != null && "true".equalsIgnoreCase(String.valueOf(value));
  }
}
