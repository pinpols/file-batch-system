package io.github.pinpols.batch.worker.core.infrastructure;

import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.PIPELINE_DEFINITION_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.TENANT_ID;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.params;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.stringValue;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toInteger;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toLong;
import static io.github.pinpols.batch.worker.core.infrastructure.PlatformRuntimeValues.toMap;

import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.domain.PipelineStepDefinition;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/** Pipeline、模板及渠道定义的数据访问协作者。 */
@Repository
@RequiredArgsConstructor
public class PlatformPipelineDefinitionRepository {

  private final PlatformFileRuntimeMapper mapper;

  public Map<String, Object> loadLatestTemplateConfig(
      String tenantId, String templateCode, String templateType) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(templateCode)) {
      return Map.of();
    }
    Map<String, Object> config = mapper.selectLatestTemplateConfig(
        params(TENANT_ID, tenantId, "templateCode", templateCode, "templateType", templateType));
    return config == null ? Map.of() : config;
  }

  public List<PipelineStepDefinition> loadPipelineSteps(Long pipelineDefinitionId) {
    if (pipelineDefinitionId == null) {
      return List.of();
    }
    Map<String, Object> queryParams =
        params(PIPELINE_DEFINITION_ID, pipelineDefinitionId, "enabledOnly", true);
    List<Map<String, Object>> rows = mapper.selectPipelineStepDefinitions(queryParams);
    return mapPipelineStepDefinitions(rows);
  }

  private List<PipelineStepDefinition> mapPipelineStepDefinitions(List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<PipelineStepDefinition> definitions = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      PipelineStepDefinition.PipelineStepDefinitionBuilder definitionBuilder = PipelineStepDefinition.builder();
      Object idValue = row.get(ID);
      Long id = toLong(idValue);
      definitionBuilder.id(id);
      Object pipelineDefinitionIdValue = row.get("pipeline_definition_id");
      Long pipelineDefinitionId = toLong(pipelineDefinitionIdValue);
      definitionBuilder.pipelineDefinitionId(pipelineDefinitionId);
      Object stepCodeValue = row.get("step_code");
      String stepCode = stringValue(stepCodeValue);
      definitionBuilder.stepCode(stepCode);
      Object stepNameValue = row.get("step_name");
      String stepName = stringValue(stepNameValue);
      definitionBuilder.stepName(stepName);
      Object stageCodeValue = row.get("stage_code");
      String stageCode = stringValue(stageCodeValue);
      definitionBuilder.stageCode(stageCode);
      Object stepOrderValue = row.get("step_order");
      Integer stepOrder = toInteger(stepOrderValue);
      definitionBuilder.stepOrder(stepOrder);
      Object implCodeValue = row.get("impl_code");
      String implCode = stringValue(implCodeValue);
      definitionBuilder.implCode(implCode);
      Object stepParamsValue = row.get("step_params");
      Map<String, Object> stepParams = toMap(stepParamsValue);
      Object timeoutSecondsValue = row.get("timeout_seconds");
      Integer timeoutSeconds = toInteger(timeoutSecondsValue);
      definitionBuilder.stepParams(stepParams).timeoutSeconds(timeoutSeconds);
      Object retryPolicyValue = row.get("retry_policy");
      String retryPolicy = stringValue(retryPolicyValue);
      definitionBuilder.retryPolicy(retryPolicy);
      Object retryMaxCountValue = row.get("retry_max_count");
      Integer retryMaxCount = toInteger(retryMaxCountValue);
      PipelineStepDefinition definition = definitionBuilder
          .retryMaxCount(retryMaxCount)
          .enabled(Boolean.TRUE.equals(row.get("enabled")))
          .build();
      definitions.add(definition);
    }
    return Collections.unmodifiableList(definitions);
  }
}
