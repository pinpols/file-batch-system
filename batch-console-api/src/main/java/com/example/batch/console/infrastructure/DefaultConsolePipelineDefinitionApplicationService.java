package com.example.batch.console.infrastructure;

import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.model.PageRequest;
import com.example.batch.common.model.PageResponse;
import com.example.batch.common.utils.Guard;
import com.example.batch.console.application.ConsolePipelineDefinitionApplicationService;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.mapper.PipelineDefinitionMapper;
import com.example.batch.console.mapper.PipelineStepDefinitionMapper;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.PipelineDefinitionSaveRequest;
import com.example.batch.console.web.response.PipelineDefinitionDetailResponse;
import com.example.batch.console.web.response.PipelineDefinitionDetailResponse.StepResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@link ConsolePipelineDefinitionApplicationService} 的默认实现。 */
@Service
@RequiredArgsConstructor
public class DefaultConsolePipelineDefinitionApplicationService
        implements ConsolePipelineDefinitionApplicationService {

    private final PipelineDefinitionMapper pipelineDefinitionMapper;
    private final PipelineStepDefinitionMapper pipelineStepDefinitionMapper;
    private final ConsoleTenantGuard tenantGuard;
    private final ConsoleRealtimeDomainEventPublisher realtimeEventPublisher;

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
        long total =
                pipelineDefinitionMapper.countByQuery(resolved, jobCode, pipelineType, enabled);
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
        params.put("pipeline_name", request.getPipelineName());
        params.put("pipeline_type", request.getPipelineType());
        params.put("biz_type", request.getBizType());
        params.put("worker_group", request.getWorkerGroup());
        params.put("version", 1);
        params.put("enabled", request.getEnabled() != null ? request.getEnabled() : true);
        params.put("description", request.getDescription());
        pipelineDefinitionMapper.insert(params);
        Long defId = ((Number) params.get("id")).longValue();

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
                        pipelineDefinitionMapper.selectById(tenantId, id),
                        "pipeline definition not found");
        Map<String, Object> params = new HashMap<>();
        params.put("tenant_id", tenantId);
        params.put("id", id);
        params.put(
                "pipeline_name",
                request.getPipelineName() != null
                        ? request.getPipelineName()
                        : existing.get("pipeline_name"));
        params.put(
                "pipeline_type",
                request.getPipelineType() != null
                        ? request.getPipelineType()
                        : existing.get("pipeline_type"));
        params.put(
                "biz_type",
                request.getBizType() != null ? request.getBizType() : existing.get("biz_type"));
        params.put(
                "worker_group",
                request.getWorkerGroup() != null
                        ? request.getWorkerGroup()
                        : existing.get("worker_group"));
        params.put(
                "enabled",
                request.getEnabled() != null ? request.getEnabled() : existing.get("enabled"));
        params.put(
                "description",
                request.getDescription() != null
                        ? request.getDescription()
                        : existing.get("description"));
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
            throw new BizException(ResultCode.NOT_FOUND, "pipeline definition not found");
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
                    "timeout_seconds",
                    step.getTimeoutSeconds() != null ? step.getTimeoutSeconds() : 0);
            stepParams.put(
                    "retry_policy", step.getRetryPolicy() != null ? step.getRetryPolicy() : "NONE");
            stepParams.put(
                    "retry_max_count",
                    step.getRetryMaxCount() != null ? step.getRetryMaxCount() : 0);
            stepParams.put("enabled", step.getEnabled() != null ? step.getEnabled() : true);
            pipelineStepDefinitionMapper.insert(stepParams);
        }
    }

    private PipelineDefinitionDetailResponse toDetailResponse(
            Map<String, Object> row, List<Map<String, Object>> stepRows) {
        return new PipelineDefinitionDetailResponse(
                toLong(row.get("id")),
                (String) row.get("tenant_id"),
                (String) row.get("job_code"),
                (String) row.get("pipeline_name"),
                (String) row.get("pipeline_type"),
                (String) row.get("biz_type"),
                (String) row.get("worker_group"),
                toInt(row.get("version")),
                (Boolean) row.get("enabled"),
                (String) row.get("description"),
                toInstant(row.get("created_at")),
                toInstant(row.get("updated_at")),
                stepRows.stream().map(this::toStepResponse).toList());
    }

    private StepResponse toStepResponse(Map<String, Object> row) {
        return new StepResponse(
                toLong(row.get("id")),
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
                (Boolean) row.get("enabled"),
                toInstant(row.get("created_at")),
                toInstant(row.get("updated_at")));
    }

    private PipelineDefinitionDetailResponse loadDetailResponse(String tenantId, Long id) {
        Map<String, Object> row =
                Guard.requireFound(
                        pipelineDefinitionMapper.selectById(tenantId, id),
                        "pipeline definition not found");
        List<Map<String, Object>> stepRows =
                pipelineStepDefinitionMapper.selectByPipelineDefinitionId(id);
        return toDetailResponse(row, stepRows);
    }

    private void publishRealtimeEvent(
            String tenantId, String eventType, PipelineDefinitionDetailResponse response) {
        realtimeEventPublisher.publishChanged(
                tenantId, "pipeline-definitions", eventType, response);
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static Integer toInt(Object v) {
        return v == null ? null : ((Number) v).intValue();
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof OffsetDateTime odt) return odt.toInstant();
        String text = v.toString().trim();
        if (text.isEmpty()) return null;
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignoredToo) {
                DateTimeFormatter[] formatters =
                        new DateTimeFormatter[] {
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
                            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                        };
                for (DateTimeFormatter formatter : formatters) {
                    try {
                        return LocalDateTime.parse(text, formatter).toInstant(ZoneOffset.UTC);
                    } catch (DateTimeParseException ignoredPattern) {
                        // 尝试下一个格式
                    }
                }
                throw ignoredToo;
            }
        }
    }
}
