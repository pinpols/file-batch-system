package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.SecretMasking;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.rbac.ConsoleMetaQueryService;
import io.github.pinpols.batch.console.application.realtime.ConsoleRealtimeEventPort;
import io.github.pinpols.batch.console.domain.job.support.JobResourceAssignmentGuard;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.PipelineDefinitionService;
import io.github.pinpols.batch.console.domain.workflow.application.contract.request.PipelineDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.ConsolePipelineDefinitionListItemResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.PipelineDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.application.contract.response.PipelineDefinitionDetailResponse.StepResponse;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PipelineDefinitionService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultPipelineDefinitionService implements PipelineDefinitionService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_PIPELINE_NAME = "pipeline_name";
  private static final String KEY_PIPELINE_TYPE = "pipeline_type";
  private static final String KEY_BIZ_TYPE = "biz_type";
  private static final String KEY_WORKER_GROUP = "worker_group";
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_ID = "id";
  private static final String KEY_STEP_PARAMS = "step_params";
  private static final String KEY_ENABLED = "enabled";

  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeEventPort realtimeEventPublisher;
  private final ConsoleMetaQueryService metaQueryService;
  private final JobResourceAssignmentGuard resourceAssignmentGuard;

  /** 校验每个 step 的 stageCode 属于该 pipelineType 允许集、implCode 已在 step 注册表登记(FE 下拉之外的越界拦截)。 */
  private void validateSteps(
      String pipelineType, List<PipelineDefinitionSaveRequest.StepItem> steps) {
    List<String> allowedStages =
        metaQueryService.pipelineStages().getOrDefault(pipelineType, List.of());
    if (EmptyChecks.isNull(steps)
        || !steps.stream()
            .map(PipelineDefinitionSaveRequest.StepItem::getStageCode)
            .toList()
            .equals(allowedStages)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.pipeline.stages_must_match_type", pipelineType);
    }
    Set<String> registeredImpls = new HashSet<>(metaQueryService.stepImpls(null));
    for (int index = 0; index < steps.size(); index++) {
      PipelineDefinitionSaveRequest.StepItem step = steps.get(index);
      if (!EmptyChecks.isNull(step.getStepOrder()) && step.getStepOrder() != index) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.pipeline.step_order_must_match_stage", index + 1);
      }
      if (Boolean.FALSE.equals(step.getEnabled())) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            "error.pipeline.fixed_stage_cannot_be_disabled",
            step.getStageCode());
      }
      if (!registeredImpls.contains(step.getImplCode())) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.pipeline.impl_not_registered", step.getImplCode());
      }
    }
  }

  @Override
  public PageResponse<ConsolePipelineDefinitionListItemResponse> list(
      String tenantId,
      String jobCode,
      String pipelineType,
      Boolean enabled,
      int pageNo,
      int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = pipelineDefinitionMapper.countByQuery(resolved, jobCode, pipelineType, enabled);
    List<Map<String, Object>> items = pipelineDefinitionMapper.selectByQuery(
        resolved, jobCode, pipelineType, enabled, pageRequest);
    return new PageResponse<>(
        total,
        pageRequest.pageNo(),
        pageRequest.pageSize(),
        items.stream().map(ConsolePipelineDefinitionListItemResponse::from).toList());
  }

  @Override
  public PipelineDefinitionDetailResponse detail(Long id, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return loadDetailResponse(resolved, id);
  }

  @Override
  @Transactional
  public PipelineDefinitionDetailResponse create(PipelineDefinitionSaveRequest request) {
    if (Texts.hasText(request.getWorkerGroup())) {
      resourceAssignmentGuard.assertCreateAllowed(null, request.getWorkerGroup());
    }
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put("job_code", request.getJobCode());
    params.put(KEY_PIPELINE_NAME, request.getPipelineName());
    params.put(KEY_PIPELINE_TYPE, request.getPipelineType());
    params.put(KEY_BIZ_TYPE, request.getBizType());
    params.put(KEY_WORKER_GROUP, request.getWorkerGroup());
    params.put("version", 1);
    params.put(KEY_ENABLED, request.getEnabled() == null || request.getEnabled());
    params.put(KEY_DESCRIPTION, request.getDescription());
    validateSteps(request.getPipelineType(), request.getSteps());
    pipelineDefinitionMapper.insert(params);
    Long defId = ((Number) params.get(KEY_ID)).longValue();

    insertSteps(defId, request.getSteps(), Map.of());

    PipelineDefinitionDetailResponse response = loadDetailResponse(tenantId, defId);
    publishRealtimeEvent(tenantId, "pipeline-definition-created", response);
    return response;
  }

  @Override
  @Transactional
  public PipelineDefinitionDetailResponse update(Long id, PipelineDefinitionSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing = Guard.requireFound(
        pipelineDefinitionMapper.selectById(tenantId, id), "pipeline definition not found");
    String existingWorkerGroup = (String) existing.get(KEY_WORKER_GROUP);
    if (Texts.hasText(request.getWorkerGroup()) || Texts.hasText(existingWorkerGroup)) {
      resourceAssignmentGuard.assertWorkerGroupUpdateAllowed(
          request.getWorkerGroup(), existingWorkerGroup);
    }
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put(KEY_ID, id);
    params.put(
        KEY_PIPELINE_NAME,
        request.getPipelineName() != null
            ? request.getPipelineName()
            : existing.get(KEY_PIPELINE_NAME));
    params.put(
        KEY_PIPELINE_TYPE,
        request.getPipelineType() != null
            ? request.getPipelineType()
            : existing.get(KEY_PIPELINE_TYPE));
    params.put(
        KEY_BIZ_TYPE,
        request.getBizType() != null ? request.getBizType() : existing.get(KEY_BIZ_TYPE));
    params.put(
        KEY_WORKER_GROUP,
        request.getWorkerGroup() != null
            ? request.getWorkerGroup()
            : existing.get(KEY_WORKER_GROUP));
    params.put(
        KEY_ENABLED,
        request.getEnabled() != null ? request.getEnabled() : existing.get(KEY_ENABLED));
    params.put(
        KEY_DESCRIPTION,
        request.getDescription() != null
            ? request.getDescription()
            : existing.get(KEY_DESCRIPTION));
    String effectiveType = request.getPipelineType() != null
        ? request.getPipelineType()
        : (String) existing.get(KEY_PIPELINE_TYPE);
    validateSteps(effectiveType, request.getSteps());
    pipelineDefinitionMapper.update(params);

    Map<String, Map<String, Object>> existingSteps =
        pipelineStepDefinitionMapper.selectByPipelineDefinitionId(id).stream()
            .collect(Collectors.toMap(
                step -> String.valueOf(step.get("step_code")),
                step -> step,
                (first, ignored) -> first));
    pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(id);
    insertSteps(id, request.getSteps(), existingSteps);

    PipelineDefinitionDetailResponse response = loadDetailResponse(tenantId, id);
    publishRealtimeEvent(tenantId, "pipeline-definition-updated", response);
    return response;
  }

  @Override
  @Transactional
  public void toggle(Long id, String tenantId, Boolean enabled) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    int rows = pipelineDefinitionMapper.toggleEnabled(resolved, id, enabled);
    if (rows == 0) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.pipeline.definition_not_found");
    }
    PipelineDefinitionDetailResponse response = loadDetailResponse(resolved, id);
    publishRealtimeEvent(resolved, "pipeline-definition-toggled", response);
  }

  private void insertSteps(
      Long pipelineDefinitionId,
      List<PipelineDefinitionSaveRequest.StepItem> steps,
      Map<String, Map<String, Object>> existingSteps) {
    if (EmptyChecks.isNull(steps)) {
      return;
    }
    for (int index = 0; index < steps.size(); index++) {
      PipelineDefinitionSaveRequest.StepItem step = steps.get(index);
      Map<String, Object> stepParams = new HashMap<>();
      stepParams.put("pipeline_definition_id", pipelineDefinitionId);
      stepParams.put("step_code", step.getStepCode());
      stepParams.put("step_name", step.getStepName());
      stepParams.put("stage_code", step.getStageCode());
      stepParams.put(
          "step_order", EmptyChecks.isNull(step.getStepOrder()) ? index : step.getStepOrder());
      stepParams.put("impl_code", step.getImplCode());
      Map<String, Object> existing = existingSteps.get(step.getStepCode());
      String stored =
          EmptyChecks.isNull(existing) ? null : jsonValue(existing.get(KEY_STEP_PARAMS));
      stepParams.put(KEY_STEP_PARAMS, resolveStepParams(step.getStepParams(), stored));
      stepParams.put(
          "timeout_seconds", step.getTimeoutSeconds() != null ? step.getTimeoutSeconds() : 0);
      stepParams.put(
          "retry_policy", step.getRetryPolicy() != null ? step.getRetryPolicy() : "NONE");
      stepParams.put(
          "retry_max_count", step.getRetryMaxCount() != null ? step.getRetryMaxCount() : 0);
      stepParams.put(KEY_ENABLED, step.getEnabled() == null || step.getEnabled());
      pipelineStepDefinitionMapper.insert(stepParams);
    }
  }

  private PipelineDefinitionDetailResponse toDetailResponse(
      Map<String, Object> row, List<Map<String, Object>> stepRows) {
    return new PipelineDefinitionDetailResponse(
        toLong(row.get(KEY_ID)),
        (String) row.get("tenant_id"),
        (String) row.get("job_code"),
        (String) row.get(KEY_PIPELINE_NAME),
        (String) row.get(KEY_PIPELINE_TYPE),
        (String) row.get(KEY_BIZ_TYPE),
        (String) row.get(KEY_WORKER_GROUP),
        toInt(row.get("version")),
        (Boolean) row.get(KEY_ENABLED),
        (String) row.get(KEY_DESCRIPTION),
        toInstant(row.get("created_at")),
        toInstant(row.get("updated_at")),
        stepRows.stream().map(this::toStepResponse).toList());
  }

  private StepResponse toStepResponse(Map<String, Object> row) {
    return new StepResponse(
        toLong(row.get(KEY_ID)),
        toLong(row.get("pipeline_definition_id")),
        (String) row.get("step_code"),
        (String) row.get("step_name"),
        (String) row.get("stage_code"),
        toInt(row.get("step_order")),
        (String) row.get("impl_code"),
        EmptyChecks.isNotNull(row.get(KEY_STEP_PARAMS))
            ? SecretMasking.maskSensitiveJson(row.get(KEY_STEP_PARAMS).toString())
            : null,
        toInt(row.get("timeout_seconds")),
        (String) row.get("retry_policy"),
        toInt(row.get("retry_max_count")),
        (Boolean) row.get(KEY_ENABLED),
        toInstant(row.get("created_at")),
        toInstant(row.get("updated_at")));
  }

  private static String resolveStepParams(String submitted, String stored) {
    if (!Texts.hasText(submitted)) {
      return submitted;
    }
    String resolved = EmptyChecks.isNull(stored)
        ? submitted
        : SecretMasking.preserveMaskedJson(submitted, stored);
    if (SecretMasking.containsMaskedSensitiveValue(resolved)) {
      throw BizException.of(
          ResultCode.INVALID_ARGUMENT, "error.config.masked_secret_requires_value");
    }
    return resolved;
  }

  private static String jsonValue(Object value) {
    if (EmptyChecks.isNull(value)) {
      return null;
    }
    return value instanceof Map<?, ?> || value instanceof List<?>
        ? JsonUtils.toJson(value)
        : value.toString();
  }

  private PipelineDefinitionDetailResponse loadDetailResponse(String tenantId, Long id) {
    Map<String, Object> row = Guard.requireFound(
        pipelineDefinitionMapper.selectById(tenantId, id), "pipeline definition not found");
    List<Map<String, Object>> stepRows =
        pipelineStepDefinitionMapper.selectByPipelineDefinitionId(id);
    return toDetailResponse(row, stepRows);
  }

  private void publishRealtimeEvent(
      String tenantId, String eventType, PipelineDefinitionDetailResponse response) {
    realtimeEventPublisher.publishChanged(tenantId, "pipeline-definitions", eventType, response);
  }

  private static Long toLong(Object v) {
    return v == null ? null : ((Number) v).longValue();
  }

  private static Integer toInt(Object v) {
    return v == null ? null : ((Number) v).intValue();
  }

  private static Instant toInstant(Object v) {
    if (v == null) {
      return null;
    }
    if (v instanceof Instant i) {
      return i;
    }
    if (v instanceof OffsetDateTime odt) {
      return odt.toInstant();
    }
    String text = v.toString().trim();
    if (text.isEmpty()) {
      return null;
    }
    try {
      return Instant.parse(text);
    } catch (DateTimeParseException ignored) {
      try {
        return OffsetDateTime.parse(text).toInstant();
      } catch (DateTimeParseException ignoredToo) {
        // 宽容 fractional seconds（0-9 位）+ 可选 T 或空格分隔符，
        // 原来枚举固定 .SSSSSS / .SSS 遇到 .SSSSS (5 位) 之类会全部异常退出。
        DateTimeFormatter flexible = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .appendLiteral(text.length() > 10 && text.charAt(10) == 'T' ? 'T' : ' ')
            .appendPattern("HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter();
        try {
          return LocalDateTime.parse(text, flexible).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignoredFlexible) {
          throw ignoredToo;
        }
      }
    }
  }
}
