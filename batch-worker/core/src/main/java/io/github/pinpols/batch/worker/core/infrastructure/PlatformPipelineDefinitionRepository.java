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

/** Pipeline、模板及渠道定义的数据访问协作者。 */
@RequiredArgsConstructor
final class PlatformPipelineDefinitionRepository {

  private final PlatformFileRuntimeMapper mapper;

  Map<String, Object> loadLatestTemplateConfig(
      String tenantId, String templateCode, String templateType) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(templateCode)) {
      return Map.of();
    }
    Map<String, Object> config = mapper.selectLatestTemplateConfig(
        params(TENANT_ID, tenantId, "templateCode", templateCode, "templateType", templateType));
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

  Long findPipelineDefinition(String tenantId, String jobCode) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(jobCode)) {
      return null;
    }
    return mapper.selectLatestPipelineDefinitionId(params(TENANT_ID, tenantId, "jobCode", jobCode));
  }

  List<PipelineStepDefinition> loadPipelineSteps(Long pipelineDefinitionId) {
    if (pipelineDefinitionId == null) {
      return List.of();
    }
    return mapPipelineStepDefinitions(mapper.selectPipelineStepDefinitions(
        params(PIPELINE_DEFINITION_ID, pipelineDefinitionId, "enabledOnly", true)));
  }

  private List<PipelineStepDefinition> mapPipelineStepDefinitions(List<Map<String, Object>> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<PipelineStepDefinition> definitions = new ArrayList<>(rows.size());
    for (Map<String, Object> row : rows) {
      definitions.add(PipelineStepDefinition.builder()
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
