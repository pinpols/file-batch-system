package com.example.batch.worker.imports.infrastructure.quality;

import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_ERROR_CODE;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.KEY_REQUIRED;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.booleanValue;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.firstNonNull;
import static com.example.batch.worker.imports.infrastructure.quality.ValidationCoercions.stringValue;

import com.example.batch.common.utils.Texts;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 合并模板配置中的 {@code field_mappings} 派生规则与显式 {@code validation_rule_set}。
 *
 * <p>派生逻辑替代原 customer 专属 defaultRuleSet——按 template 自身声明派生避免给非 customer schema(如
 * IMP-TRANSACTION-CSV)误套 customerNo required。
 */
@Component
@RequiredArgsConstructor
public class ValidationRuleSetMerger {

  private final ValidationConfigSupport configSupport;

  public Map<String, Object> merge(Object templateConfigObject) {
    Map<String, Object> templateConfig = configSupport.toMap(templateConfigObject);
    Map<String, Object> merged = new LinkedHashMap<>();
    Map<String, Object> derived = deriveRequiredRulesFromFieldMappings(templateConfig);
    if (!derived.isEmpty()) {
      Map<String, Object> fieldRules = new LinkedHashMap<>();
      fieldRules.put("fieldRules", derived);
      merged.putAll(fieldRules);
    }
    deepMerge(
        merged,
        configSupport.toMap(
            firstNonNull(
                templateConfig.get("validation_rule_set"),
                templateConfig.get("validationRuleSet"))));
    return merged;
  }

  private Map<String, Object> deriveRequiredRulesFromFieldMappings(
      Map<String, Object> templateConfig) {
    if (templateConfig == null || templateConfig.isEmpty()) {
      return Map.of();
    }
    Object raw =
        firstNonNull(templateConfig.get("field_mappings"), templateConfig.get("fieldMappings"));
    if (!(raw instanceof List<?> list)) {
      return Map.of();
    }
    Map<String, Object> derived = new LinkedHashMap<>();
    for (Object item : list) {
      Map<String, Object> mapping = configSupport.toMap(item);
      if (mapping.isEmpty()) {
        continue;
      }
      String name = stringValue(mapping.get("name"));
      if (!Texts.hasText(name)) {
        continue;
      }
      if (booleanValue(firstNonNull(mapping.get(KEY_REQUIRED), mapping.get("notNull")), false)) {
        derived.put(name, Map.of(KEY_REQUIRED, true, KEY_ERROR_CODE, "IMPORT_VALIDATE_REQUIRED"));
      }
    }
    return derived;
  }

  @SuppressWarnings("unchecked")
  private void deepMerge(Map<String, Object> target, Map<String, Object> source) {
    if (source == null || source.isEmpty()) {
      return;
    }
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      Object existing = target.get(entry.getKey());
      Object incoming = entry.getValue();
      if (existing instanceof Map<?, ?> existingMap && incoming instanceof Map<?, ?> incomingMap) {
        Map<String, Object> nested = new LinkedHashMap<>((Map<String, Object>) existingMap);
        deepMerge(nested, (Map<String, Object>) incomingMap);
        target.put(entry.getKey(), nested);
        continue;
      }
      target.put(entry.getKey(), incoming);
    }
  }
}
