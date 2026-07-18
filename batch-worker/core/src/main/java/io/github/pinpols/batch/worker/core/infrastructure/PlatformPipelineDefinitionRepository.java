package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.PIPELINE_DEFINITION_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.TENANT_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.defaultText;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.params;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.stringValue;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toInteger;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toJson;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toLong;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toMap;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.domain.PipelineStepTemplate;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/** Pipeline、模板及渠道定义的数据访问协作者。 */
@RequiredArgsConstructor
final class PlatformPipelineDefinitionRepository {

  private final PlatformFileRuntimeMapper mapper;

  Map<String, Object> loadLatestTemplateConfig(
      String tenantId, String templateCode, String templateType) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(templateCode)) {
      return Map.of();
    }
    Map<String, Object> config =
        mapper.selectLatestTemplateConfig(
            params(
                TENANT_ID, tenantId, "templateCode", templateCode, "templateType", templateType));
    return config == null ? Map.of() : config;
  }

  Map<String, Object> loadChannelConfig(String tenantId, String channelCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(channelCode)) {
      return Map.of();
    }
    Map<String, Object> config =
        mapper.selectChannelConfig(params(TENANT_ID, tenantId, "channelCode", channelCode));
    return config == null ? Map.of() : config;
  }

  Long ensurePipelineDefinition(
      String tenantId,
      String jobCode,
      String pipelineType,
      String workerGroup,
      String description,
      List<PipelineStepTemplate> defaultSteps) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode) || !Texts.hasText(pipelineType)) {
      return null;
    }
    Long definitionId =
        mapper.selectLatestPipelineDefinitionId(params(TENANT_ID, tenantId, "jobCode", jobCode));
    if (definitionId == null) {
      Map<String, Object> values =
          params(
              TENANT_ID,
              tenantId,
              "jobCode",
              jobCode,
              "pipelineName",
              jobCode,
              "pipelineType",
              pipelineType,
              "workerGroup",
              workerGroup,
              "description",
              description);
      mapper.insertPipelineDefinition(values);
      definitionId = toLong(values.get(ID));
      // 仅首次创建定义时写入默认步骤，防止不同 worker 的默认步骤被追加到同一定义。
      ensurePipelineStepDefinitions(definitionId, defaultSteps);
    }
    return definitionId;
  }

  List<PipelineStepDefinition> loadPipelineSteps(Long pipelineDefinitionId) {
    if (pipelineDefinitionId == null) {
      return List.of();
    }
    return mapPipelineStepDefinitions(
        mapper.selectPipelineStepDefinitions(
            params(PIPELINE_DEFINITION_ID, pipelineDefinitionId, "enabledOnly", true)));
  }

  private void ensurePipelineStepDefinitions(
      Long pipelineDefinitionId, List<PipelineStepTemplate> defaultSteps) {
    if (pipelineDefinitionId == null || defaultSteps == null || defaultSteps.isEmpty()) {
      return;
    }
    List<PipelineStepDefinition> existingSteps =
        mapPipelineStepDefinitions(
            mapper.selectPipelineStepDefinitions(
                params(PIPELINE_DEFINITION_ID, pipelineDefinitionId, "enabledOnly", false)));
    Set<String> existingCodes = new HashSet<>();
    for (PipelineStepDefinition existingStep : existingSteps) {
      existingCodes.add(existingStep.stepCode());
    }
    for (PipelineStepTemplate template : defaultSteps) {
      if (template == null
          || !Texts.hasText(template.stepCode())
          || existingCodes.contains(template.stepCode())) {
        continue;
      }
      mapper.insertPipelineStepDefinition(
          params(
              PIPELINE_DEFINITION_ID,
              pipelineDefinitionId,
              "stepCode",
              template.stepCode(),
              "stepName",
              defaultText(template.stepName(), template.stepCode()),
              "stageCode",
              template.stageCode(),
              "stepOrder",
              template.stepOrder() == null ? 0 : template.stepOrder(),
              "implCode",
              template.implCode(),
              "stepParamsJson",
              toJson(template.stepParams()),
              "timeoutSeconds",
              template.timeoutSeconds() == null ? 0 : template.timeoutSeconds(),
              "retryPolicy",
              defaultText(template.retryPolicy(), "NONE"),
              "retryMaxCount",
              template.retryMaxCount() == null ? 0 : template.retryMaxCount(),
              "enabled",
              template.enabled()));
    }
  }

  private List<PipelineStepDefinition> mapPipelineStepDefinitions(List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<PipelineStepDefinition> definitions = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      definitions.add(
          PipelineStepDefinition.builder()
              .id(toLong(row.get(ID)))
              .pipelineDefinitionId(toLong(row.get("pipeline_definition_id")))
              .stepCode(stringValue(row.get("step_code")))
              .stepName(stringValue(row.get("step_name")))
              .stageCode(stringValue(row.get("stage_code")))
              .stepOrder(toInteger(row.get("step_order")))
              .implCode(stringValue(row.get("impl_code")))
              .stepParams(toMap(row.get("step_params")))
              .timeoutSeconds(toInteger(row.get("timeout_seconds")))
              .retryPolicy(stringValue(row.get("retry_policy")))
              .retryMaxCount(toInteger(row.get("retry_max_count")))
              .enabled(Boolean.TRUE.equals(row.get("enabled")))
              .build());
    }
    return Collections.unmodifiableList(definitions);
  }
}
