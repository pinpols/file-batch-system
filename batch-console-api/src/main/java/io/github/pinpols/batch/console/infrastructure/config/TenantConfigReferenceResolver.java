package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Resolves job-level config dependencies from explicit JSON references. */
@Component
public class TenantConfigReferenceResolver {

  private static final Set<String> TEMPLATE_KEYS = Set.of(
      "templateCode",
      "template_code",
      "fileTemplateCode",
      "file_template_code",
      "importTemplateCode",
      "import_template_code",
      "exportTemplateCode",
      "export_template_code");

  private static final Set<String> CHANNEL_KEYS = Set.of(
      "channelCode",
      "channel_code",
      "fileChannelCode",
      "file_channel_code",
      "inputChannelCode",
      "input_channel_code",
      "outputChannelCode",
      "output_channel_code",
      "sourceChannelCode",
      "source_channel_code",
      "targetChannelCode",
      "target_channel_code",
      "dispatchChannelCode",
      "dispatch_channel_code");

  public References resolve(
      JobDefinitionSpec job,
      List<PipelineDefinitionSpec> pipelines,
      List<WorkflowDefinitionSpec> workflows) {
    Set<String> pipelineJobCodes = new LinkedHashSet<>();
    Set<String> workflowCodes = new LinkedHashSet<>();
    Set<String> templateCodes = new LinkedHashSet<>();
    Set<String> channelCodes = new LinkedHashSet<>();
    Set<String> windowCodes = new LinkedHashSet<>();

    scanJson(job.getDefaultParams(), templateCodes, channelCodes);
    scanJson(job.getParamSchema(), templateCodes, channelCodes);
    addIfText(windowCodes, job.getWindowCode());

    for (PipelineDefinitionSpec pipeline : nullSafe(pipelines)) {
      if (!job.getJobCode().equals(pipeline.getJobCode())) {
        continue;
      }
      addIfText(pipelineJobCodes, pipeline.getJobCode());
      for (PipelineDefinitionSpec.StepSpec step : nullSafe(pipeline.getSteps())) {
        scanJson(step.getStepParams(), templateCodes, channelCodes);
      }
    }

    for (WorkflowDefinitionSpec workflow : nullSafe(workflows)) {
      boolean matched = false;
      for (WorkflowDefinitionSpec.NodeSpec node : nullSafe(workflow.getNodes())) {
        if (job.getJobCode().equals(node.getRelatedJobCode())) {
          matched = true;
        }
        addIfText(windowCodes, node.getWindowCode());
        if (StringUtils.hasText(node.getRelatedPipelineCode())) {
          addIfText(pipelineJobCodes, node.getRelatedPipelineCode());
          if (job.getJobCode().equals(node.getRelatedPipelineCode())) {
            matched = true;
          }
        }
        scanJson(node.getNodeParams(), templateCodes, channelCodes);
      }
      if (matched) {
        addIfText(workflowCodes, workflow.getWorkflowCode());
      }
    }

    return new References(
        List.copyOf(pipelineJobCodes),
        List.copyOf(workflowCodes),
        List.copyOf(templateCodes),
        List.copyOf(channelCodes),
        List.copyOf(windowCodes));
  }

  private void scanJson(String raw, Set<String> templateCodes, Set<String> channelCodes) {
    if (!StringUtils.hasText(raw)) {
      return;
    }
    try {
      Object parsed = JsonUtils.fromJson(raw, Object.class);
      scanValue(parsed, templateCodes, channelCodes);
    } catch (IllegalArgumentException ignored) {
      // Legacy free-text expressions are not config references.
    }
  }

  @SuppressWarnings("unchecked")
  private void scanValue(Object value, Set<String> templateCodes, Set<String> channelCodes) {
    if (value instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> entry : map.entrySet()) {
        String key = entry.getKey() == null ? "" : entry.getKey().toString();
        Object entryValue = entry.getValue();
        if (TEMPLATE_KEYS.contains(key)) {
          addIfText(templateCodes, stringValue(entryValue));
        } else if (CHANNEL_KEYS.contains(key)) {
          addIfText(channelCodes, stringValue(entryValue));
        }
        scanValue(entryValue, templateCodes, channelCodes);
      }
      return;
    }
    if (value instanceof List<?> list) {
      for (Object item : list) {
        scanValue(item, templateCodes, channelCodes);
      }
    }
  }

  private static String stringValue(Object value) {
    return value == null ? null : value.toString();
  }

  private static void addIfText(Set<String> values, String value) {
    if (StringUtils.hasText(value)) {
      values.add(value.trim());
    }
  }

  private static <T> List<T> nullSafe(List<T> values) {
    return values == null ? List.of() : new ArrayList<>(values);
  }

  public record References(
      List<String> pipelineJobCodes,
      List<String> workflowCodes,
      List<String> templateCodes,
      List<String> channelCodes,
      List<String> windowCodes) {}
}
