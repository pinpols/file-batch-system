package com.example.batch.worker.imports.stage;

import com.example.batch.common.config.BatchSecurityProperties;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.utils.EncodingUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.FileRecordParam;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.domain.ImportStage;
import com.example.batch.worker.imports.domain.ImportStageResult;
import com.example.batch.worker.imports.domain.ImportWorkerType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
public class ReceiveStep implements ImportStageStep {

  private static final Set<String> RESERVED_METADATA_KEYS =
      Set.of("templateCode", "sourceType", "headerRows", "footerRows", "taskId", "withHeader");

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final BatchSecurityProperties batchSecurityProperties;
  private final ObjectMapper objectMapper;

  @Value("${batch.worker.import.max-payload-size-mb:100}")
  private int maxPayloadSizeMb;

  /**
   * payload 相对堆大小的安全比例（默认 20%）。PREPROCESS 阶段会产生 byte[] + String (UTF-16) + decode 副本等多份中间态，留 80% 给
   * JVM / GC / 其它业务。
   */
  @Value("${batch.worker.import.payload-heap-ratio:0.2}")
  private double payloadHeapRatio;

  private long maxPayloadSizeBytes;

  public ReceiveStep(
      PlatformFileRuntimeRepository runtimeRepository,
      BatchSecurityProperties batchSecurityProperties,
      ObjectMapper objectMapper) {
    this.runtimeRepository = runtimeRepository;
    this.batchSecurityProperties = batchSecurityProperties;
    this.objectMapper = objectMapper;
  }

  @jakarta.annotation.PostConstruct
  void init() {
    long configured =
        maxPayloadSizeMb <= 0 ? Long.MAX_VALUE : (long) maxPayloadSizeMb * 1024 * 1024;
    // 堆联动：JVM 最大堆的 payload-heap-ratio 比例，预留空间给 PREPROCESS 的 byte[] / String / decode 副本
    long heapCap =
        payloadHeapRatio > 0
            ? (long) (Runtime.getRuntime().maxMemory() * payloadHeapRatio)
            : Long.MAX_VALUE;
    this.maxPayloadSizeBytes = Math.min(configured, heapCap);
    log.info(
        "[ReceiveStep] maxPayloadSizeBytes={} (configured={}MB, heap-cap={}MB @ ratio={})",
        maxPayloadSizeBytes,
        maxPayloadSizeMb,
        heapCap == Long.MAX_VALUE ? -1 : heapCap / 1024 / 1024,
        payloadHeapRatio);
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
    Long existingFileId =
        runtimeRepository.toLong(context.getAttributes().get(PipelineRuntimeKeys.FILE_ID));
    if (existingFileId == null) {
      String traceId =
          String.valueOf(
              context
                  .getAttributes()
                  .getOrDefault(PipelineRuntimeKeys.TRACE_ID, context.getWorkerId()));
      String fileFormatType =
          normalizeFileFormat(importPayload.fileFormatType(), context.getRawPayload());
      String fileName = resolveFileName(importPayload, fileFormatType, traceId);
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("templateCode", importPayload.templateCode());
      metadata.put("sourceType", defaultText(importPayload.sourceType(), "UPLOAD"));
      metadata.put("headerRows", importPayload.headerRows());
      metadata.put("footerRows", importPayload.footerRows());
      metadata.put("taskId", context.getAttributes().get(PipelineRuntimeKeys.TASK_ID));
      metadata.put("withHeader", importPayload.withHeader());
      mergeSecurityMetadata(
          metadata, resolveTemplateSecurity(context.getTenantId(), importPayload.templateCode()));
      mergeUserMetadata(metadata, importPayload.metadata());
      Long fileId =
          runtimeRepository.createFileRecord(
              FileRecordParam.builder()
                  .tenantId(context.getTenantId())
                  .fileCode(importPayload.fileCode())
                  .bizType(defaultText(importPayload.bizType(), context.getJobCode()))
                  .fileCategory("INPUT")
                  .fileName(fileName)
                  .originalFileName(defaultText(importPayload.originalFileName(), fileName))
                  .fileFormatType(fileFormatType)
                  .charset(defaultText(importPayload.charset(), EncodingUtils.UTF_8))
                  .fileSizeBytes(payloadLength)
                  .checksumType(defaultText(importPayload.checksumType(), "NONE"))
                  .checksumValue(importPayload.checksumValue())
                  .storageType(defaultText(importPayload.storageType(), "LOCAL"))
                  .storagePath(
                      defaultText(
                          importPayload.storagePath(),
                          "ingress/" + context.getTenantId() + "/" + traceId + "/" + fileName))
                  .storageBucket(importPayload.storageBucket())
                  .fileVersion(null)
                  .bizDate(parseBizDate(context.getBizDate()))
                  .sourceType(defaultText(importPayload.sourceType(), "UPLOAD"))
                  .sourceRef(importPayload.sourceRef())
                  .fileStatus("RECEIVED")
                  .traceId(traceId)
                  .metadata(metadata)
                  .build());
      context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
      context
          .getAttributes()
          .put(
              PipelineRuntimeKeys.FILE_RECORD,
              runtimeRepository.loadFileRecord(context.getTenantId(), fileId));
      runtimeRepository.bindFileToPipelineInstance(
          runtimeRepository.toLong(
              context.getAttributes().get(PipelineRuntimeKeys.PIPELINE_INSTANCE_ID)),
          fileId);
      context.setFileId(String.valueOf(fileId));
    }
    context.getAttributes().put("importPayload", importPayload);
    return ImportStageResult.success(stage());
  }

  private Map<String, Object> resolveTemplateSecurity(String tenantId, String templateCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(templateCode)) {
      return Map.of();
    }
    Map<String, Object> template =
        runtimeRepository.loadLatestTemplateConfig(tenantId, templateCode, ImportWorkerType.IMPORT);
    if (template == null || template.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> security = new LinkedHashMap<>();
    security.put(
        "contentEncryptionEnabled",
        !batchSecurityProperties.isBypassMode()
            && truthy(template.get("content_encryption_enabled")));
    security.put("encryptionKeyRef", template.get("encryption_key_ref"));
    security.put("downloadRequiresApproval", truthy(template.get("download_requires_approval")));
    security.put("previewMaskingEnabled", truthy(template.get("preview_masking_enabled")));
    security.put("errorLineMaskingEnabled", truthy(template.get("error_line_masking_enabled")));
    security.put("logMaskingEnabled", truthy(template.get("log_masking_enabled")));
    security.put("maskingRuleSet", template.get("masking_rule_set"));
    return security;
  }

  private ImportPayload resolvePayload(ImportJobContext context) {
    Object existing = context.getAttributes().get("importPayload");
    if (existing instanceof ImportPayload importPayload) {
      return importPayload;
    }
    String rawPayload = context.getRawPayload();
    if (!Texts.hasText(rawPayload) || !rawPayload.trim().startsWith("{")) {
      return new ImportPayload(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null, null, null, null, null, Map.of());
    }
    try {
      ImportPayload importPayload = objectMapper.readValue(rawPayload, ImportPayload.class);
      if (importPayload == null) {
        return new ImportPayload(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, Map.of());
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
          Map<String, Object> asMap = objectMapper.convertValue(importPayload, Map.class);
          asMap.put("content", trimmed);
          asMap.put("contentBase64", null);
          return objectMapper.convertValue(asMap, ImportPayload.class);
        }
      }

      return importPayload;
    } catch (Exception ignored) {
      SwallowedExceptionLogger.warn(ReceiveStep.class, "catch:Exception", ignored);

      return new ImportPayload(
          null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
          null, null, null, null, null, null, null, Map.of());
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
    java.util.Deque<NodeAtDepth> queue = new java.util.ArrayDeque<>();
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
    return "import-"
        + traceId
        + switch (fileFormatType) {
          case "JSON" -> ".json";
          case "DELIMITED" -> ".csv";
          case "EXCEL" -> ".xlsx";
          default -> ".dat";
        };
  }

  private String normalizeFileFormat(String fileFormatType, String rawPayload) {
    if (Texts.hasText(fileFormatType)) {
      return fileFormatType.toUpperCase();
    }
    if (rawPayload != null && rawPayload.trim().startsWith("{")) {
      return "JSON";
    }
    if (rawPayload != null && rawPayload.contains(",")) {
      return "DELIMITED";
    }
    return "JSON";
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
