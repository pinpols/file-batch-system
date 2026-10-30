package io.github.pinpols.batch.worker.exports.stage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.enums.TaskErrorCodes;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.file.ExportFileNameResolver;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.support.FileTemplateConfigRequest;
import io.github.pinpols.batch.worker.core.support.FrozenFileTemplateResolver;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportStage;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import io.github.pinpols.batch.worker.exports.domain.ExportWorkerType;
import io.github.pinpols.batch.worker.exports.region.ExportRegionResolver;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 导出准备阶段：解析 payload、加载模板配置、确定文件名和对象路径。 */
@Component
@SuppressWarnings("java:S2259")
public class PrepareStep implements ExportStageStep {

  private static final String KEY_SNAPSHOT_MODE = "snapshotMode";
  private static final String KEY_SNAPSHOT_TS = "snapshotTs";
  private static final String KEY_SOURCE_PARTITIONS = "sourcePartitions";

  private final ObjectMapper objectMapper;
  private final FrozenFileTemplateResolver templateResolver;

  public PrepareStep(ObjectMapper objectMapper, FrozenFileTemplateResolver templateResolver) {
    this.objectMapper = objectMapper;
    this.templateResolver = templateResolver;
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
      Map<String, Object> attrs = context.getAttributes();
      ExportPayload payload =
          attrs.get(ExportRuntimeKeys.EXPORT_PAYLOAD) instanceof ExportPayload exportPayload
              ? exportPayload
              : objectMapper.readValue(context.getRawPayload(), ExportPayload.class);
      attrs.put(ExportRuntimeKeys.EXPORT_PAYLOAD, payload);
      Map<String, Object> templateConfig;
      try {
        templateConfig = resolveTemplateConfig(context, payload);
      } catch (BizException invalidTemplate) {
        return ExportStageResult.failure(
            stage(), TaskErrorCodes.FROZEN_FILE_TEMPLATE_INVALID, invalidTemplate, objectMapper);
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
      String fileFormatType =
          resolveText(templateConfig.get("file_format_type"), FileTemplateFormat.JSON.code());
      // 地区(per-run):metadata.region 优先 → 模板 defaultRegion 回退 → allowedRegions 字典校验。
      // 在此统一解析:既喂文件名 ${region} 占位,也经 exportSnapshot 透传给 GENERATE 查询插件绑定 :region。
      String region = ExportRegionResolver.resolve(templateConfig, payload.metadata());
      int partitionNo = intOrDefault(attrs.get(PipelineRuntimeKeys.PARTITION_NO), 1);
      int partitionCount = intOrDefault(attrs.get(PipelineRuntimeKeys.PARTITION_COUNT), 1);
      String resolvedFileName =
          resolveFileName(context, payload, templateConfig, fileFormatType, region);
      String fileName =
          BatchFileConstants.insertPartitionTag(resolvedFileName, partitionNo, partitionCount);
      String finalObjectName =
          resolveObjectName(context, payload, fileName, partitionNo, partitionCount);
      Long jobInstanceId = ExportArtifactIdentity.jobInstanceId(context);
      String temporaryFileName =
          EmptyChecks.isNull(jobInstanceId) ? fileName : "job-" + jobInstanceId + "-" + fileName;
      String tempObjectName =
          BatchFileConstants.tempObjectName(context.getTenantId(), bizDate, temporaryFileName);
      Map<String, Object> exportSnapshot = buildExportSnapshot(payload, templateConfig);
      if (Texts.hasText(region)) {
        exportSnapshot.put("region", region);
      }
      attrs.put(PipelineRuntimeKeys.EXPORT_SNAPSHOT, exportSnapshot);
      attrs.put(PipelineRuntimeKeys.FILE_NAME, fileName);
      attrs.put(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, fileFormatType);
      attrs.put(PipelineRuntimeKeys.OBJECT_NAME, finalObjectName);
      attrs.put(PipelineRuntimeKeys.TEMP_OBJECT_NAME, tempObjectName);
    } catch (Exception ex) {
      SwallowedExceptionLogger.warn(PrepareStep.class, "catch:Exception", ex);

      ExportStage failureStage = stage();
      String localizedMessageArgument = ex.getMessage();
      String fallbackMessage = ex.getMessage();
      return ExportStageResult.failure(
          failureStage,
          "EXPORT_PREPARE_PARSE_FAILED",
          "error.export.prepare.parse_failed",
          new Object[] {localizedMessageArgument},
          fallbackMessage,
          objectMapper);
    }
    return ExportStageResult.success(stage());
  }

  Map<String, Object> resolveTemplateConfig(ExportJobContext context) {
    Map<String, Object> attrs = context.getAttributes();
    Object payloadValue = attrs.get(ExportRuntimeKeys.EXPORT_PAYLOAD);
    ExportPayload payload =
        payloadValue instanceof ExportPayload typed ? typed : readPayload(context.getRawPayload());
    attrs.put(ExportRuntimeKeys.EXPORT_PAYLOAD, payload);
    return resolveTemplateConfig(context, payload);
  }

  private Map<String, Object> resolveTemplateConfig(
      ExportJobContext context, ExportPayload payload) {
    // 派发未带 templateCode 时,按 seed 命名约定 <jobCode>_TPL 回退加载导出模板;
    // 否则 TEMPLATE_CONFIG 为空 → GenerateStep 报「export_data_ref is required」、导出永久失败转死信。
    // 约定不命中(如 *_TPL2)则 loadLatestTemplateConfig 返回空,回退原行为(清晰失败),无副作用。
    String effectiveTemplateCode = Texts.hasText(payload.templateCode())
        ? payload.templateCode()
        : context.getJobCode() + "_TPL";
    FileTemplateConfigRequest templateRequest = new FileTemplateConfigRequest(
        context.getTenantId(),
        effectiveTemplateCode,
        ExportWorkerType.EXPORT,
        context.getRawPayload(),
        context.getAttributes());
    Map<String, Object> templateConfig = templateResolver.resolve(templateRequest);
    if (EmptyChecks.isNotEmpty(templateConfig)) {
      context.getAttributes().put(PipelineRuntimeKeys.TEMPLATE_CONFIG, templateConfig);
    }
    return templateConfig;
  }

  private ExportPayload readPayload(String rawPayload) {
    try {
      return objectMapper.readValue(rawPayload, ExportPayload.class);
    } catch (IOException exception) {
      throw new IllegalArgumentException("invalid EXPORT replay payload", exception);
    }
  }

  private String resolveFileName(
      ExportJobContext context,
      ExportPayload payload,
      Map<String, Object> templateConfig,
      String fileFormatType,
      String region) {
    if (Texts.hasText(payload.fileName())) {
      return payload.fileName();
    }
    String namingRule = templateConfig.get("naming_rule") == null
        ? null
        : String.valueOf(templateConfig.get("naming_rule"));
    String bizDate = resolveBizDate(context, payload);
    String bizType = Texts.hasText(payload.bizType()) ? payload.bizType() : context.getJobCode();
    ExportFileNameResolver.Input input = ExportFileNameResolver.Input.builder()
        .namingRule(namingRule)
        .fileFormatType(fileFormatType)
        .bizType(bizType)
        .bizDate(bizDate)
        .tenantId(context.getTenantId())
        .batchNo(payload.batchNo())
        .region(region)
        .version("v1")
        .build();
    return ExportFileNameResolver.resolve(input);
  }

  private String resolveObjectName(
      ExportJobContext context,
      ExportPayload payload,
      String fileName,
      int partitionNo,
      int partitionCount) {
    if (Texts.hasText(payload.objectName())) {
      return BatchFileConstants.insertPartitionTag(
          payload.objectName(), partitionNo, partitionCount);
    }
    String bizType = Texts.hasText(payload.bizType()) ? payload.bizType() : context.getJobCode();
    String bizDate = resolveBizDate(context, payload);
    String batchNo = defaultText(payload.batchNo(), "batch");
    Long jobInstanceId = ExportArtifactIdentity.jobInstanceId(context);
    String artifactVersion = EmptyChecks.isNull(jobInstanceId) ? "v1" : "job-" + jobInstanceId;
    return BatchFileConstants.outboundObjectName(
        bizType, bizDate, batchNo, artifactVersion, fileName);
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

  private static int intOrDefault(Object value, int def) {
    if (value instanceof Number n) {
      return n.intValue();
    }
    if (value == null) {
      return def;
    }
    try {
      String valueText = String.valueOf(value);
      String trimmedValue = valueText.trim();
      return Integer.parseInt(trimmedValue);
    } catch (NumberFormatException ignored) {
      return def;
    }
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
    String templateSnapshotMode = stringHint(hints, KEY_SNAPSHOT_MODE);
    String metadataSnapshotMode = stringMeta(meta, KEY_SNAPSHOT_MODE);
    String snapshotMode = firstNonBlank(templateSnapshotMode, metadataSnapshotMode, "AS_OF_BATCH");
    snap.put(KEY_SNAPSHOT_MODE, snapshotMode);

    String templateSnapshotTimestamp = stringHint(hints, KEY_SNAPSHOT_TS);
    String metadataSnapshotTimestamp = stringMeta(meta, KEY_SNAPSHOT_TS);
    Instant defaultSnapshotInstant = BatchDateTimeSupport.utcNow();
    String defaultSnapshotTimestamp = defaultSnapshotInstant.toString();
    String snapshotTimestamp = firstNonBlank(
        templateSnapshotTimestamp, metadataSnapshotTimestamp, defaultSnapshotTimestamp);
    snap.put(KEY_SNAPSHOT_TS, snapshotTimestamp);
    Object templateSourcePartitions = hints.get(KEY_SOURCE_PARTITIONS);
    Object metadataSourcePartitions = meta.get(KEY_SOURCE_PARTITIONS);
    List<Object> mergedSourcePartitions =
        mergePartitions(templateSourcePartitions, metadataSourcePartitions);
    snap.put(KEY_SOURCE_PARTITIONS, mergedSourcePartitions);
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
      Object nested = schema.get(PipelineRuntimeKeys.EXPORT_SNAPSHOT);
      if (nested instanceof Map<?, ?> snap) {
        snap.forEach((k, v) -> out.put(String.valueOf(k), v));
      }
    }
    return out;
  }

  private void putIfHasText(Map<String, Object> out, String key, Object value) {
    if (EmptyChecks.isNotNull(value)) {
      String presenceText = String.valueOf(value);
      if (Texts.hasText(presenceText)) {
        String rawText = String.valueOf(value);
        String text = rawText.trim();
        out.put(key, text);
      }
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
