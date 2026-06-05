package com.example.batch.worker.exports.stage;

import com.example.batch.common.constants.BatchFileConstants;
import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.exports.domain.ExportJobContext;
import com.example.batch.worker.exports.domain.ExportPayload;
import com.example.batch.worker.exports.domain.ExportStage;
import com.example.batch.worker.exports.domain.ExportStageResult;
import com.example.batch.worker.exports.domain.ExportWorkerType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 导出准备阶段：解析 payload、加载模板配置、确定文件名和对象路径。 */
@Component
public class PrepareStep implements ExportStageStep {

  private static final String KEY_SNAPSHOT_MODE = "snapshotMode";
  private static final String KEY_SNAPSHOT_TS = "snapshotTs";
  private static final String KEY_SOURCE_PARTITIONS = "sourcePartitions";

  private final ObjectMapper objectMapper;
  private final PlatformFileRuntimeRepository runtimeRepository;

  public PrepareStep(ObjectMapper objectMapper, PlatformFileRuntimeRepository runtimeRepository) {
    this.objectMapper = objectMapper;
    this.runtimeRepository = runtimeRepository;
  }

  @Override
  public ExportStage stage() {
    return ExportStage.PREPARE;
  }

  @Override
  public ExportStageResult execute(ExportJobContext context) {
    if (context == null
        || !Texts.hasText(context.getTenantId())
        || !Texts.hasText(context.getRawPayload())) {
      return ExportStageResult.failure(
          stage(),
          "EXPORT_PREPARE_INVALID",
          "error.export.prepare.invalid",
          new Object[0],
          "tenantId or payload is blank",
          objectMapper);
    }
    try {
      ExportPayload payload =
          context.getAttributes().get("exportPayload") instanceof ExportPayload exportPayload
              ? exportPayload
              : objectMapper.readValue(context.getRawPayload(), ExportPayload.class);
      context.getAttributes().put("exportPayload", payload);
      Map<String, Object> templateConfig = Map.of();
      // 派发未带 templateCode 时,按 seed 命名约定 <jobCode>_TPL 兜底加载导出模板;
      // 否则 TEMPLATE_CONFIG 为空 → GenerateStep 报「export_data_ref is required」、导出永久失败转死信。
      // 约定不命中(如 *_TPL2)则 loadLatestTemplateConfig 返回空,回退原行为(清晰失败),无副作用。
      String effectiveTemplateCode =
          Texts.hasText(payload.templateCode())
              ? payload.templateCode()
              : context.getJobCode() + "_TPL";
      if (Texts.hasText(effectiveTemplateCode)) {
        templateConfig =
            runtimeRepository.loadLatestTemplateConfig(
                context.getTenantId(), effectiveTemplateCode, ExportWorkerType.EXPORT);
        if (!templateConfig.isEmpty()) {
          context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
        }
      }
      String bizDate = resolveBizDate(context, payload);
      if (!Texts.hasText(bizDate)) {
        return ExportStageResult.failure(
            stage(),
            "EXPORT_PREPARE_BIZ_DATE_MISSING",
            "error.export.prepare.biz_date_missing",
            new Object[0],
            "bizDate is required from orchestrator context or export payload",
            objectMapper);
      }
      String fileFormatType = resolveText(templateConfig.get("file_format_type"), "JSON");
      String fileName = resolveFileName(context, payload, templateConfig, fileFormatType);
      String finalObjectName = resolveObjectName(context, payload, fileName);
      String tempObjectName =
          BatchFileConstants.tempObjectName(context.getTenantId(), bizDate, fileName);
      Map<String, Object> exportSnapshot = buildExportSnapshot(payload, templateConfig);
      context.getAttributes().put(PipelineRuntimeKeys.EXPORT_SNAPSHOT, exportSnapshot);
      context.getAttributes().put("fileName", fileName);
      context.getAttributes().put("exportFileFormatType", fileFormatType);
      context.getAttributes().put("objectName", finalObjectName);
      context.getAttributes().put("tempObjectName", tempObjectName);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(PrepareStep.class, "catch:Exception", ex);

      return ExportStageResult.failure(
          stage(),
          "EXPORT_PREPARE_PARSE_FAILED",
          "error.export.prepare.parse_failed",
          new Object[] {ex.getMessage()},
          ex.getMessage(),
          objectMapper);
    }
    return ExportStageResult.success(stage());
  }

  private String resolveFileName(
      ExportJobContext context,
      ExportPayload payload,
      Map<String, Object> templateConfig,
      String fileFormatType) {
    if (Texts.hasText(payload.fileName())) {
      return payload.fileName();
    }
    String namingRule =
        templateConfig.get("naming_rule") == null
            ? null
            : String.valueOf(templateConfig.get("naming_rule"));
    String bizDate = resolveBizDate(context, payload);
    String bizType = Texts.hasText(payload.bizType()) ? payload.bizType() : context.getJobCode();
    String extension =
        switch (fileFormatType.toUpperCase()) {
          case "DELIMITED" -> ".csv";
          case "EXCEL" -> ".xlsx";
          case "FIXED_WIDTH" -> ".txt";
          case "XML" -> ".xml";
          default -> ".json";
        };
    if (Texts.hasText(namingRule)) {
      return namingRule
          .replace("${bizDate}", bizDate)
          .replace("${tenantId}", context.getTenantId())
          .replace("${batchNo}", defaultText(payload.batchNo(), "batch"))
          .replace("${version}", "v1");
    }
    return bizType + "_" + bizDate + "_" + defaultText(payload.batchNo(), "batch") + extension;
  }

  private String resolveObjectName(
      ExportJobContext context, ExportPayload payload, String fileName) {
    if (Texts.hasText(payload.objectName())) {
      return payload.objectName();
    }
    String bizType = Texts.hasText(payload.bizType()) ? payload.bizType() : context.getJobCode();
    String bizDate = resolveBizDate(context, payload);
    return BatchFileConstants.outboundObjectName(
        bizType, bizDate, defaultText(payload.batchNo(), "batch"), "v1", fileName);
  }

  private String resolveBizDate(ExportJobContext context, ExportPayload payload) {
    if (Texts.hasText(payload.bizDate())) {
      return payload.bizDate();
    }
    if (Texts.hasText(context.getBizDate())) {
      return context.getBizDate();
    }
    return null;
  }

  private String resolveText(Object value, String fallback) {
    return value == null || !Texts.hasText(String.valueOf(value))
        ? fallback
        : String.valueOf(value);
  }

  private String defaultText(String value, String fallback) {
    return Texts.hasText(value) ? value : fallback;
  }

  /**
   * 构建导出快照元数据，优先级：模板配置 &gt; payload.metadata &gt; 默认值。
   * 包含字段：snapshotMode、snapshotTs、sourcePartitions。
   */
  private Map<String, Object> buildExportSnapshot(
      ExportPayload payload, Map<String, Object> templateConfig) {
    Map<String, Object> hints = extractTemplateSnapshotHints(templateConfig);
    Map<String, Object> meta =
        payload != null && payload.metadata() != null ? payload.metadata() : Map.of();
    Map<String, Object> snap = new LinkedHashMap<>();
    snap.put(
        KEY_SNAPSHOT_MODE,
        firstNonBlank(
            stringHint(hints, KEY_SNAPSHOT_MODE),
            stringMeta(meta, KEY_SNAPSHOT_MODE),
            "AS_OF_BATCH"));
    snap.put(
        KEY_SNAPSHOT_TS,
        firstNonBlank(
            stringHint(hints, KEY_SNAPSHOT_TS),
            stringMeta(meta, KEY_SNAPSHOT_TS),
            BatchDateTimeSupport.utcNow().toString()));
    snap.put(
        KEY_SOURCE_PARTITIONS,
        mergePartitions(hints.get(KEY_SOURCE_PARTITIONS), meta.get(KEY_SOURCE_PARTITIONS)));
    return snap;
  }

  private Map<String, Object> extractTemplateSnapshotHints(Map<String, Object> template) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (template == null || template.isEmpty()) {
      return out;
    }
    putIfHasText(out, KEY_SNAPSHOT_MODE, template.get("snapshot_mode"));
    putIfHasText(out, KEY_SNAPSHOT_TS, template.get("snapshot_ts"));
    if (template.get("source_partitions") != null) {
      out.put(KEY_SOURCE_PARTITIONS, template.get("source_partitions"));
    }
    Object qps = template.get("query_param_schema");
    if (qps instanceof Map<?, ?> schema) {
      Object nested = schema.get("exportSnapshot");
      if (nested instanceof Map<?, ?> snap) {
        snap.forEach((k, v) -> out.put(String.valueOf(k), v));
      }
    }
    return out;
  }

  private void putIfHasText(Map<String, Object> out, String key, Object value) {
    if (value != null && Texts.hasText(String.valueOf(value))) {
      out.put(key, String.valueOf(value).trim());
    }
  }

  private String stringHint(Map<String, Object> hints, String key) {
    Object v = hints.get(key);
    return v == null ? null : String.valueOf(v);
  }

  private String stringMeta(Map<String, Object> meta, String key) {
    Object v = meta.get(key);
    return v == null ? null : String.valueOf(v);
  }

  private String firstNonBlank(String a, String b, String fallback) {
    if (Texts.hasText(a)) {
      return a;
    }
    if (Texts.hasText(b)) {
      return b;
    }
    return fallback;
  }

  private List<Object> mergePartitions(Object fromTemplate, Object fromMeta) {
    List<Object> out = new ArrayList<>();
    appendPartitions(out, fromTemplate);
    appendPartitions(out, fromMeta);
    return out.isEmpty() ? List.of() : List.copyOf(out);
  }

  private void appendPartitions(List<Object> out, Object raw) {
    if (raw == null) {
      return;
    }
    if (raw instanceof List<?> list) {
      out.addAll(list);
      return;
    }
    if (raw instanceof String text && Texts.hasText(text)) {
      for (String part : text.split(",")) {
        if (Texts.hasText(part.trim())) {
          out.add(part.trim());
        }
      }
    }
  }
}
