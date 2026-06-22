package com.example.batch.worker.imports.infrastructure;

import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.imports.domain.CustomerImportPayload;
import com.example.batch.worker.imports.domain.ImportJobContext;
import com.example.batch.worker.imports.domain.ImportPayload;
import com.example.batch.worker.imports.infrastructure.quality.DatasetRuleEvaluator;
import com.example.batch.worker.imports.infrastructure.quality.RecordRuleEvaluator;
import com.example.batch.worker.imports.infrastructure.quality.ValidationConfigSupport;
import com.example.batch.worker.imports.infrastructure.quality.ValidationIssue;
import com.example.batch.worker.imports.infrastructure.quality.ValidationIssueMasker;
import com.example.batch.worker.imports.infrastructure.quality.ValidationOutcome;
import com.example.batch.worker.imports.infrastructure.quality.ValidationRuleSetMerger;
import com.example.batch.worker.imports.infrastructure.quality.ValidationSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 导入数据质量校验服务:对数据集级别和记录级别分别执行规则校验。
 *
 * <p>本类自 2026-04-30 起作为 facade,逻辑下沉到 {@code infrastructure.quality} 子包:
 *
 * <ul>
 *   <li>{@link DatasetRuleEvaluator} — 行数 / checksum / schema
 *   <li>{@link RecordRuleEvaluator} — null / field / unique 三规
 *   <li>{@link ValidationRuleSetMerger} — fieldMappings 派生 + 显式 ruleSet 深合并
 *   <li>{@link ValidationIssueMasker} — log_masking_enabled + masking_rule_set
 *   <li>{@link ValidationConfigSupport} — Map / payload 转换
 * </ul>
 *
 * <p><b>规则集合并</b>:从 template 的 {@code field_mappings} 派生 required + {@code validation_rule_set}
 * 显式规则深合并,后者覆盖前者(替代原 customer 专属硬编码 default,避免给非 customer schema 误套 customerNo required)。
 */
@Service
@RequiredArgsConstructor
public class ImportDataQualityService {

  private final ValidationConfigSupport configSupport;
  private final ValidationRuleSetMerger ruleSetMerger;
  private final DatasetRuleEvaluator datasetEvaluator;
  private final RecordRuleEvaluator recordEvaluator;
  private final ValidationIssueMasker masker;

  public ValidationOutcome validate(
      ImportJobContext context, List<CustomerImportPayload> payloads) {
    List<String> schemaFields = stringList(context);
    ValidationSession session =
        beginValidation(context, payloads == null ? 0L : payloads.size(), schemaFields);
    validateDataset(session);
    validateChunk(session, payloads, 1L);
    return outcome(session);
  }

  public ValidationSession beginValidation(
      ImportJobContext context, long totalCount, List<String> schemaFields) {
    Map<String, Object> ruleSet =
        ruleSetMerger.merge(
            context == null
                ? null
                : context.getAttributes().get(PipelineRuntimeKeys.TEMPLATE_CONFIG));
    ImportPayload importPayload =
        context != null
                && context.getAttributes().get(PipelineRuntimeKeys.IMPORT_PAYLOAD)
                    instanceof ImportPayload payload
            ? payload
            : null;
    String normalizedPayload =
        context == null
            ? null
            : asString(context.getAttributes().get(PipelineRuntimeKeys.IMPORT_NORMALIZED_PAYLOAD));
    return new ValidationSession(
        context,
        ruleSet,
        totalCount,
        normalizedPayload,
        importPayload,
        schemaFields == null ? List.of() : List.copyOf(schemaFields),
        new LinkedHashMap<>(),
        new ArrayList<>(),
        new LinkedHashMap<>(),
        new LinkedHashSet<>());
  }

  public void validateDataset(ValidationSession session) {
    if (session == null) {
      return;
    }
    datasetEvaluator.evaluate(session);
  }

  public Map<Long, ValidationIssue> validateChunk(
      ValidationSession session, List<CustomerImportPayload> payloads, long recordBase) {
    if (session == null || payloads == null || payloads.isEmpty()) {
      return Map.of();
    }
    List<Map<String, Object>> rows = new ArrayList<>(payloads.size());
    for (CustomerImportPayload payload : payloads) {
      rows.add(configSupport.payloadToMap(payload));
    }
    return validateChunkRows(session, rows, recordBase);
  }

  public Map<Long, ValidationIssue> validateChunkRows(
      ValidationSession session, List<Map<String, Object>> rows, long recordBase) {
    if (session == null || rows == null || rows.isEmpty()) {
      return Map.of();
    }
    return recordEvaluator.evaluate(session, rows, recordBase);
  }

  public ValidationOutcome outcome(ValidationSession session) {
    if (session == null) {
      return new ValidationOutcome(Map.of(), List.of(), List.of());
    }
    return masker.maskOutcome(session);
  }

  private List<String> stringList(ImportJobContext context) {
    Object value =
        context == null
            ? null
            : context.getAttributes().get(PipelineRuntimeKeys.IMPORT_SCHEMA_FIELDS);
    if (value instanceof List<?> list) {
      List<String> items = new ArrayList<>();
      for (Object item : list) {
        if (item != null) {
          String text = String.valueOf(item);
          if (!text.isBlank()) {
            items.add(text);
          }
        }
      }
      return items;
    }
    return List.of();
  }

  private String asString(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
