package io.github.pinpols.batch.console.infrastructure.workflow;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.model.PageRequest;
import io.github.pinpols.batch.common.model.PageResponse;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import io.github.pinpols.batch.console.domain.rbac.service.ConsoleMetaQueryService;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.domain.workflow.application.ConsolePipelineDefinitionApplicationService;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.web.request.PipelineDefinitionSaveRequest;
import io.github.pinpols.batch.console.domain.workflow.web.response.PipelineDefinitionDetailResponse;
import io.github.pinpols.batch.console.domain.workflow.web.response.PipelineDefinitionDetailResponse.StepResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ConsolePipelineDefinitionApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsolePipelineDefinitionApplicationService
    implements ConsolePipelineDefinitionApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String KEY_PIPELINE_NAME = "pipeline_name";
  private static final String KEY_PIPELINE_TYPE = "pipeline_type";
  private static final String KEY_BIZ_TYPE = "biz_type";
  private static final String KEY_WORKER_GROUP = "worker_group";
  private static final String KEY_DESCRIPTION = "description";
  private static final String KEY_ID = "id";
  private static final String KEY_ENABLED = "enabled";

  private final PipelineDefinitionMapper pipelineDefinitionMapper;
  private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
  private final ConsoleTenantGuard tenantGuard;
  private final ConsoleRealtimeDomainEventPublisher realtimeEventPublisher;
  private final ConsoleMetaQueryService metaQueryService;

  /** 校验每个 step 的 stageCode 属于该 pipelineType 允许集、implCode 已在 step 注册表登记(FE 下拉之外的越界拦截)。 */
  private void validateSteps(
      String pipelineType, List<PipelineDefinitionSaveRequest.StepItem> steps) {
    if (steps == null || steps.isEmpty()) {
      return;
    }
    List<String> allowedStages =
        metaQueryService.pipelineStages().getOrDefault(pipelineType, List.of());
    Set<String> registeredImpls = new HashSet<>(metaQueryService.stepImpls(null));
    for (PipelineDefinitionSaveRequest.StepItem step : steps) {
      if (!allowedStages.contains(step.getStageCode())) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT,
            "error.pipeline.stage_not_allowed_for_type",
            step.getStageCode(),
            pipelineType);
      }
      if (!registeredImpls.contains(step.getImplCode())) {
        throw BizException.of(
            ResultCode.INVALID_ARGUMENT, "error.pipeline.impl_not_registered", step.getImplCode());
      }
    }
  }

  @Override
  public PageResponse<Map<String, Object>> list(
      String tenantId,
      String jobCode,
      String pipelineType,
      Boolean enabled,
      int pageNo,
      int pageSize) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    PageRequest pageRequest = new PageRequest(pageNo, pageSize);
    long total = pipelineDefinitionMapper.countByQuery(resolved, jobCode, pipelineType, enabled);
    List<Map<String, Object>> items =
        pipelineDefinitionMapper.selectByQuery(
            resolved, jobCode, pipelineType, enabled, pageRequest);
    return new PageResponse<>(total, pageRequest.pageNo(), pageRequest.pageSize(), items);
  }

  @Override
  public PipelineDefinitionDetailResponse detail(Long id, String tenantId) {
    String resolved = tenantGuard.resolveTenant(tenantId);
    return loadDetailResponse(resolved, id);
  }

  @Override
  @Transactional
  public PipelineDefinitionDetailResponse create(PipelineDefinitionSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> params = new HashMap<>();
    params.put("tenant_id", tenantId);
    params.put("job_code", request.getJobCode());
    params.put(KEY_PIPELINE_NAME, request.getPipelineName());
    params.put(KEY_PIPELINE_TYPE, request.getPipelineType());
    params.put(KEY_BIZ_TYPE, request.getBizType());
    params.put(KEY_WORKER_GROUP, request.getWorkerGroup());
    params.put("version", 1);
    params.put(KEY_ENABLED, request.getEnabled() != null ? request.getEnabled() : true);
    params.put(KEY_DESCRIPTION, request.getDescription());
    validateSteps(request.getPipelineType(), request.getSteps());
    pipelineDefinitionMapper.insert(params);
    Long defId = ((Number) params.get(KEY_ID)).longValue();

    insertSteps(defId, request.getSteps());

    PipelineDefinitionDetailResponse response = loadDetailResponse(tenantId, defId);
    publishRealtimeEvent(tenantId, "pipeline-definition-created", response);
    return response;
  }

  @Override
  @Transactional
  public PipelineDefinitionDetailResponse update(Long id, PipelineDefinitionSaveRequest request) {
    String tenantId = tenantGuard.resolveTenant(request.getTenantId());
    Map<String, Object> existing =
        Guard.requireFound(
            pipelineDefinitionMapper.selectById(tenantId, id), "pipeline definition not found");
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
    String effectiveType =
        request.getPipelineType() != null
            ? request.getPipelineType()
            : (String) existing.get(KEY_PIPELINE_TYPE);
    validateSteps(effectiveType, request.getSteps());
    pipelineDefinitionMapper.update(params);

    pipelineStepDefinitionMapper.deleteByPipelineDefinitionId(id);
    insertSteps(id, request.getSteps());

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
      Long pipelineDefinitionId, List<PipelineDefinitionSaveRequest.StepItem> steps) {
    if (steps == null) {
      return;
    }
    for (PipelineDefinitionSaveRequest.StepItem step : steps) {
      Map<String, Object> stepParams = new HashMap<>();
      stepParams.put("pipeline_definition_id", pipelineDefinitionId);
      stepParams.put("step_code", step.getStepCode());
      stepParams.put("step_name", step.getStepName());
      stepParams.put("stage_code", step.getStageCode());
      stepParams.put("step_order", step.getStepOrder() != null ? step.getStepOrder() : 0);
      stepParams.put("impl_code", step.getImplCode());
      stepParams.put("step_params", step.getStepParams());
      stepParams.put(
          "timeout_seconds", step.getTimeoutSeconds() != null ? step.getTimeoutSeconds() : 0);
      stepParams.put(
          "retry_policy", step.getRetryPolicy() != null ? step.getRetryPolicy() : "NONE");
      stepParams.put(
          "retry_max_count", step.getRetryMaxCount() != null ? step.getRetryMaxCount() : 0);
      stepParams.put(KEY_ENABLED, step.getEnabled() != null ? step.getEnabled() : true);
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
        row.get("step_params") != null ? row.get("step_params").toString() : null,
        toInt(row.get("timeout_seconds")),
        (String) row.get("retry_policy"),
        toInt(row.get("retry_max_count")),
        (Boolean) row.get(KEY_ENABLED),
        toInstant(row.get("created_at")),
        toInstant(row.get("updated_at")));
  }

  private PipelineDefinitionDetailResponse loadDetailResponse(String tenantId, Long id) {
    Map<String, Object> row =
        Guard.requireFound(
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
        DateTimeFormatter flexible =
            new DateTimeFormatterBuilder()
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
