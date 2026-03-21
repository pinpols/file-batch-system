package com.example.batch.console.application;

import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.config.ConsoleTriggerClientProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.web.request.CatchUpApprovalRequest;
import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TriggerRequest;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.JsonUtils;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
public class DefaultConsoleJobApplicationService implements ConsoleJobApplicationService {

    private final RestClient.Builder restClientBuilder;
    private final ConsoleTriggerClientProperties triggerClientProperties;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;

    @Override
    public String trigger(TriggerRequest request, String idempotencyKey) {
        return delegateLaunch(
                request.getTenantId(),
                request.getJobCode(),
                request.getBizDate(),
                resolveTriggerType(request.getTriggerType(), TriggerType.MANUAL),
                parsePayload(request.getPayload()),
                idempotencyKey
        );
    }

    @Override
    public String compensate(CompensateRequest request, String idempotencyKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operationType", "COMPENSATE");
        params.put("targetInstanceNo", request.getTargetInstanceNo());
        params.put("reason", request.getReason());
        return delegateLaunch(
                request.getTenantId(),
                request.getJobCode(),
                request.getBizDate(),
                TriggerType.MANUAL,
                params,
                idempotencyKey
        );
    }

    @Override
    public String rerun(RerunRequest request, String idempotencyKey) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operationType", "RERUN");
        params.put("targetInstanceNo", request.getTargetInstanceNo());
        params.put("reason", request.getReason());
        return delegateLaunch(
                request.getTenantId(),
                request.getJobCode(),
                request.getBizDate(),
                TriggerType.CATCH_UP,
                params,
                idempotencyKey
        );
    }

    @Override
    public String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        restClient.post()
                .uri("/internal/dead-letters/{deadLetterId}/replay", request.getDeadLetterId())
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new DeadLetterReplayPayload(request.getTenantId()))
                .retrieve()
                .toBodilessEntity();
        return "REPLAY_ACCEPTED";
    }

    @Override
    public String approveCatchUp(CatchUpApprovalRequest request, String idempotencyKey) {
        if (request.getRequestId() != null && !request.getRequestId().isBlank()) {
            return approvePendingCatchUpRequest(request, idempotencyKey);
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("operationType", "CATCH_UP_APPROVAL");
        params.put("approvalMode", "MANUAL_APPROVAL");
        params.put("catchUpApproved", true);
        params.put("reason", request.getReason());
        params.put("scheduledAt", request.getScheduledAt());
        return delegateLaunch(
                request.getTenantId(),
                request.getJobCode(),
                request.getBizDate(),
                TriggerType.CATCH_UP,
                params,
                idempotencyKey
        );
    }

    private String approvePendingCatchUpRequest(CatchUpApprovalRequest request, String idempotencyKey) {
        if (request.getTenantId() == null || request.getTenantId().isBlank()) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "tenantId is required");
        }
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(triggerClientProperties.getBaseUrl()).build();
        CommonResponse<LaunchResponse> response = restClient.post()
                .uri("/api/triggers/catch-up/approve")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new CatchUpApprovalPayload(
                        request.getTenantId(),
                        request.getRequestId(),
                        request.getReason()
                ))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<CommonResponse<LaunchResponse>>() {
                });
        if (response == null || response.data() == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "trigger service returned empty response");
        }
        return response.data().instanceNo();
    }

    /**
     * 控制台只做受控触发入口，实际受理仍交给 trigger/orchestrator 主链处理。
     */
    private String delegateLaunch(String tenantId,
                                  String jobCode,
                                  String bizDate,
                                  TriggerType triggerType,
                                  Map<String, Object> params,
                                  String idempotencyKey) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(triggerClientProperties.getBaseUrl()).build();
        CommonResponse<LaunchResponse> response = restClient.post()
                .uri("/api/triggers/launch")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new TriggerLaunchPayload(
                        tenantId,
                        jobCode,
                        parseBizDate(bizDate),
                        triggerType,
                        params == null ? Map.of() : params
                ))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<CommonResponse<LaunchResponse>>() {
                });
        if (response == null || response.data() == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "trigger service returned empty response");
        }
        return response.data().instanceNo();
    }

    private TriggerType resolveTriggerType(String triggerTypeValue, TriggerType defaultTriggerType) {
        if (triggerTypeValue == null || triggerTypeValue.isBlank()) {
            return defaultTriggerType;
        }
        try {
            return TriggerType.valueOf(triggerTypeValue.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "unsupported triggerType: " + triggerTypeValue);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        Object payloadObject = JsonUtils.fromJson(payloadJson, Object.class);
        if (payloadObject instanceof Map<?, ?> payloadMap) {
            return (Map<String, Object>) payloadMap;
        }
        throw new BizException(ResultCode.INVALID_ARGUMENT, "payload must be a JSON object");
    }

    private LocalDate parseBizDate(String bizDate) {
        try {
            return LocalDate.parse(bizDate);
        } catch (DateTimeParseException exception) {
            throw new BizException(ResultCode.INVALID_ARGUMENT, "bizDate must use yyyy-MM-dd");
        }
    }

    private record TriggerLaunchPayload(
            String tenantId,
            String jobCode,
            LocalDate bizDate,
            TriggerType triggerType,
            Map<String, Object> params
    ) {
    }

    private record CatchUpApprovalPayload(
            String tenantId,
            String requestId,
            String reason
    ) {
    }

    private record DeadLetterReplayPayload(String tenantId) {
    }
}
