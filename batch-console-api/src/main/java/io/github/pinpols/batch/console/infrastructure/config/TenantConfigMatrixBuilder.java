package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileChannelSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.FileTemplateSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.PipelineDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.WorkflowDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigCopyRequest.ConfigType;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigMatrixRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigMatrixResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigMatrixResponse.JobMatrixRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** 构建只读的跨租户作业配置对照矩阵。 */
final class TenantConfigMatrixBuilder {

  private static final String KEY_ENABLED = "enabled";
  private static final String KEY_TIMEZONE = "timezone";
  private static final Set<ConfigType> MATRIX_CONFIG_TYPES = Set.of(
      ConfigType.JOB_DEFINITION,
      ConfigType.WORKFLOW_DEFINITION,
      ConfigType.PIPELINE_DEFINITION,
      ConfigType.FILE_CHANNEL,
      ConfigType.FILE_TEMPLATE,
      ConfigType.RESOURCE_QUEUE,
      ConfigType.BATCH_WINDOW,
      ConfigType.BUSINESS_CALENDAR);

  private TenantConfigMatrixBuilder() {}

  static TenantConfigMatrixResponse build(
      TenantConfigMatrixRequest request,
      BiFunction<String, Set<ConfigType>, ConfigSyncBundlePayload> bundleLoader,
      TenantConfigReferenceResolver referenceResolver) {
    String baselineTenantId = resolveBaselineTenantId(request);
    Map<String, Map<String, JobMatrixRow>> rowsByTenantAndJob = new LinkedHashMap<>();
    List<JobMatrixRow> rows = new ArrayList<>();
    for (String tenantId : request.getTenantIds()) {
      ConfigSyncBundlePayload bundle = bundleLoader.apply(tenantId, MATRIX_CONFIG_TYPES);
      Map<String, JobMatrixRow> tenantRows = new LinkedHashMap<>();
      for (String jobCode : request.getJobCodes()) {
        JobMatrixRow row = matrixRow(tenantId, jobCode, bundle, referenceResolver);
        tenantRows.put(jobCode, row);
        rows.add(row);
      }
      rowsByTenantAndJob.put(tenantId, tenantRows);
    }
    rows = rows.stream()
        .map(row -> withDrift(
            row, rowsByTenantAndJob.getOrDefault(baselineTenantId, Map.of()).get(row.jobCode())))
        .toList();
    return new TenantConfigMatrixResponse(
        baselineTenantId,
        List.copyOf(request.getTenantIds()),
        List.copyOf(request.getJobCodes()),
        rows);
  }

  private static JobMatrixRow matrixRow(
      String tenantId,
      String jobCode,
      ConfigSyncBundlePayload bundle,
      TenantConfigReferenceResolver referenceResolver) {
    Optional<JobDefinitionSpec> maybeJob = Optional.ofNullable(
        first(filter(bundle.getJobDefinitions(), item -> jobCode.equals(item.getJobCode()))));
    if (!maybeJob.isPresent()) {
      return missingJobRow(tenantId, jobCode);
    }
    JobDefinitionSpec job = maybeJob.orElseThrow();
    List<PipelineDefinitionSpec> relatedPipelines =
        filter(bundle.getPipelineDefinitions(), item -> jobCode.equals(item.getJobCode()));
    List<WorkflowDefinitionSpec> relatedWorkflows = filter(
        bundle.getWorkflowDefinitions(),
        workflow -> EmptyChecks.isNotNull(workflow.getNodes())
            && workflow.getNodes().stream()
                .anyMatch(node -> jobCode.equals(node.getRelatedJobCode())
                    || jobCode.equals(node.getRelatedPipelineCode())));
    TenantConfigReferenceResolver.References refs =
        referenceResolver.resolve(job, relatedPipelines, relatedWorkflows);
    List<String> templateCodes = EmptyChecks.isEmpty(refs.templateCodes())
        ? filter(
                bundle.getFileTemplates(),
                item -> equalsNullable(job.getBizType(), item.getBizType()))
            .stream()
            .map(FileTemplateSpec::getTemplateCode)
            .toList()
        : refs.templateCodes();
    return JobMatrixRow.builder()
        .tenantId(tenantId)
        .jobCode(jobCode)
        .exists(true)
        .enabled(job.getEnabled())
        .scheduleType(job.getScheduleType())
        .scheduleExpr(job.getScheduleExpr())
        .timezone(job.getTimezone())
        .queueCode(job.getQueueCode())
        .calendarCode(job.getCalendarCode())
        .windowCode(job.getWindowCode())
        .workerGroup(job.getWorkerGroup())
        .pipelineJobCodes(refs.pipelineJobCodes())
        .workflowCodes(refs.workflowCodes())
        .templateCodes(templateCodes)
        .channelCodes(channelCodes(job, bundle, refs))
        .driftFields(List.of())
        .build();
  }

  private static JobMatrixRow missingJobRow(String tenantId, String jobCode) {
    return JobMatrixRow.builder()
        .tenantId(tenantId)
        .jobCode(jobCode)
        .exists(false)
        .pipelineJobCodes(List.of())
        .workflowCodes(List.of())
        .templateCodes(List.of())
        .channelCodes(List.of())
        .driftFields(List.of("missing"))
        .build();
  }

  private static JobMatrixRow withDrift(JobMatrixRow row, JobMatrixRow baseline) {
    if (EmptyChecks.isNull(baseline) || Objects.equals(row.tenantId(), baseline.tenantId())) {
      return row;
    }
    List<String> fields = new ArrayList<>();
    addDrift(fields, "exists", row.exists(), baseline.exists());
    addDrift(fields, KEY_ENABLED, row.enabled(), baseline.enabled());
    addDrift(fields, "scheduleType", row.scheduleType(), baseline.scheduleType());
    addDrift(fields, "scheduleExpr", row.scheduleExpr(), baseline.scheduleExpr());
    addDrift(fields, KEY_TIMEZONE, row.timezone(), baseline.timezone());
    addDrift(fields, "queueCode", row.queueCode(), baseline.queueCode());
    addDrift(fields, "calendarCode", row.calendarCode(), baseline.calendarCode());
    addDrift(fields, "windowCode", row.windowCode(), baseline.windowCode());
    addDrift(fields, "workerGroup", row.workerGroup(), baseline.workerGroup());
    addDrift(fields, "templateCodes", row.templateCodes(), baseline.templateCodes());
    addDrift(fields, "channelCodes", row.channelCodes(), baseline.channelCodes());
    return JobMatrixRow.builder()
        .tenantId(row.tenantId())
        .jobCode(row.jobCode())
        .exists(row.exists())
        .enabled(row.enabled())
        .scheduleType(row.scheduleType())
        .scheduleExpr(row.scheduleExpr())
        .timezone(row.timezone())
        .queueCode(row.queueCode())
        .calendarCode(row.calendarCode())
        .windowCode(row.windowCode())
        .workerGroup(row.workerGroup())
        .pipelineJobCodes(row.pipelineJobCodes())
        .workflowCodes(row.workflowCodes())
        .templateCodes(row.templateCodes())
        .channelCodes(row.channelCodes())
        .driftFields(List.copyOf(fields))
        .build();
  }

  private static List<String> channelCodes(
      JobDefinitionSpec job,
      ConfigSyncBundlePayload bundle,
      TenantConfigReferenceResolver.References refs) {
    if (EmptyChecks.isNotEmpty(refs.channelCodes())) {
      return refs.channelCodes();
    }
    return filter(
            bundle.getFileChannels(),
            item -> equalsNullable(job.getBizType(), item.getChannelCode()))
        .stream()
        .map(FileChannelSpec::getChannelCode)
        .toList();
  }

  private static String resolveBaselineTenantId(TenantConfigMatrixRequest request) {
    if (Texts.hasText(request.getBaselineTenantId())) {
      return request.getBaselineTenantId();
    }
    return request.getTenantIds().get(0);
  }

  private static void addDrift(List<String> fields, String field, Object current, Object baseline) {
    if (!Objects.equals(current, baseline)) {
      fields.add(field);
    }
  }

  private static boolean equalsNullable(String left, String right) {
    return EmptyChecks.isNotBlank(left) && left.equals(right);
  }

  private static <T> T first(List<T> values) {
    return EmptyChecks.isEmpty(values) ? null : values.get(0);
  }

  private static <T> List<T> filter(List<T> source, Predicate<T> predicate) {
    if (EmptyChecks.isEmpty(source)) {
      return List.of();
    }
    return source.stream().filter(predicate).toList();
  }
}
