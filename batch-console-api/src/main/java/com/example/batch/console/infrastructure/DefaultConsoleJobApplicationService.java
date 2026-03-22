package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleJobApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.config.ConsoleTriggerClientProperties;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.CatchUpApprovalRequest;
import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
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
    private final ConsoleTenantGuard tenantGuard;

    @Override
    public String trigger(TriggerRequest request, String idempotencyKey) {
        return delegateLaunch(
                resolveTenant(request.getTenantId()),
                request.getJobCode(),
                request.getBizDate(),
                resolveTriggerType(request.getTriggerType(), TriggerType.MANUAL),
                parsePayload(request.getPayload()),
                idempotencyKey
        );
    }

    @Override
    public String compensation(CompensationCommandRequest request, String idempotencyKey) {
        if (!hasText(request.getApprovalId())) {
            return submitApproval("COMPENSATION", "COMPENSATION", "JOB", String.valueOf(request.getTargetId()), request, request.getReason(), idempotencyKey);
        }
        requireApprovedApproval(resolveTenant(request.getTenantId()), request.getApprovalId());
        return submitCompensation(new CompensationPayload(
                resolveTenant(request.getTenantId()),
                request.getCompensationType(),
                request.getTargetId(),
                request.getTargetInstanceNo(),
                request.getJobCode(),
                parseOptionalBizDate(request.getBizDate()),
                request.getBatchNo(),
                request.getRelatedFileId(),
                request.getChannelCode(),
                request.getReason(),
                request.getOperatorId(),
                request.getApprovalId(),
                request.getStrategy(),
                null
        ), idempotencyKey);
    }

    @Override
    public String compensate(CompensateRequest request, String idempotencyKey) {
        return submitCompensation(new CompensationPayload(
                resolveTenant(request.getTenantId()),
                request.getCompensationType() == null || request.getCompensationType().isBlank() ? "JOB" : request.getCompensationType(),
                request.getTargetId(),
                request.getTargetInstanceNo(),
                request.getJobCode(),
                parseOptionalBizDate(request.getBizDate()),
                request.getBatchNo(),
                request.getRelatedFileId(),
                request.getChannelCode(),
                request.getReason(),
                request.getOperatorId(),
                request.getApprovalId(),
                request.getStrategy(),
                null
        ), idempotencyKey);
    }

    @Override
    public String rerun(RerunRequest request, String idempotencyKey) {
        String compensationType = (request.getTargetId() != null
                || (request.getTargetInstanceNo() != null && !request.getTargetInstanceNo().isBlank()))
                ? "JOB"
                : "BATCH";
        return submitCompensation(new CompensationPayload(
                resolveTenant(request.getTenantId()),
                compensationType,
                request.getTargetId(),
                request.getTargetInstanceNo(),
                request.getJobCode(),
                parseOptionalBizDate(request.getBizDate()),
                request.getBatchNo(),
                request.getRelatedFileId(),
                null,
                request.getReason(),
                request.getOperatorId(),
                request.getApprovalId(),
                request.getStrategy(),
                null
        ), idempotencyKey);
    }

    @Override
    public String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey) {
        if (!hasText(request.getApprovalId())) {
            return submitApproval("DLQ_REPLAY", "DLQ_REPLAY", "DLQ", String.valueOf(request.getDeadLetterId()), request, request.getReason(), idempotencyKey);
        }
        requireApprovedApproval(resolveTenant(request.getTenantId()), request.getApprovalId());
        return submitCompensation(new CompensationPayload(
                resolveTenant(request.getTenantId()),
                "DLQ",
                request.getDeadLetterId(),
                null,
                null,
                null,
                null,
                null,
                null,
                request.getReason(),
                request.getOperatorId(),
                request.getApprovalId(),
                request.getStrategy(),
                null
        ), idempotencyKey);
    }

    @Override
    public String approveCatchUp(CatchUpApprovalRequest request, String idempotencyKey) {
        if (!hasText(request.getApprovalId())) {
            return submitApproval("CATCH_UP", "CATCH_UP", "CATCH_UP", request.getRequestId(), request, request.getReason(), idempotencyKey);
        }
        requireApprovedApproval(resolveTenant(request.getTenantId()), request.getApprovalId());
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
                resolveTenant(request.getTenantId()),
                request.getJobCode(),
                request.getBizDate(),
                TriggerType.CATCH_UP,
                params,
                idempotencyKey
        );
    }

    private String approvePendingCatchUpRequest(CatchUpApprovalRequest request, String idempotencyKey) {
        String tenantId = resolveTenant(request.getTenantId());
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(triggerClientProperties.getBaseUrl()).build();
        CommonResponse<LaunchResponse> response = restClient.post()
                .uri("/api/triggers/catch-up/approve")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new CatchUpApprovalPayload(
                        tenantId,
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

    private String submitCompensation(CompensationPayload payload, String idempotencyKey) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        CommonResponse<CompensationResponse> response = restClient.post()
                .uri("/internal/compensations")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(payload.withTraceId(requestMetadata.traceId()))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<CommonResponse<CompensationResponse>>() {
                });
        if (response == null || response.data() == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "orchestrator returned empty compensation response");
        }
        return response.data().commandNo();
    }

    private String submitApproval(String approvalType,
                                  String actionType,
                                  String targetType,
                                  String targetId,
                                  Object payload,
                                  String approvalReason,
                                  String idempotencyKey) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        ApprovalResponse response = restClient.post()
                .uri("/internal/approvals")
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(new ApprovalRequest(
                        resolveTenant(extractTenantId(payload)),
                        approvalType,
                        actionType,
                        targetType,
                        targetId,
                        JsonUtils.toJson(payload),
                        requestMetadata.operatorId(),
                        requestMetadata.traceId(),
                        idempotencyKey,
                        approvalReason
                ))
                .retrieve()
                .body(ApprovalResponse.class);
        if (response == null || !hasText(response.approvalNo())) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "approval service returned empty response");
        }
        return response.approvalNo();
    }

    private void requireApprovedApproval(String tenantId, String approvalNo) {
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        ApprovalRecordResponse response = restClient.get()
                .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
                .retrieve()
                .body(ApprovalRecordResponse.class);
        if (response == null || response.record() == null) {
            throw new BizException(ResultCode.NOT_FOUND, "approval request not found");
        }
        String status = response.record().approvalStatus();
        if (!"APPROVED".equalsIgnoreCase(status) && !"EXECUTED".equalsIgnoreCase(status)) {
            throw new BizException(ResultCode.STATE_CONFLICT, "approval is not approved yet");
        }
    }

    private String extractTenantId(Object payload) {
        if (payload instanceof TriggerRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof CompensationCommandRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof DeadLetterReplayRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof CatchUpApprovalRequest request) {
            return request.getTenantId();
        }
        return null;
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
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

    private String resolveTenant(String requestTenantId) {
        return tenantGuard.resolveTenant(requestTenantId);
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

    private LocalDate parseOptionalBizDate(String bizDate) {
        if (bizDate == null || bizDate.isBlank()) {
            return null;
        }
        return parseBizDate(bizDate);
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

    private record ApprovalRequest(String tenantId,
                                   String approvalType,
                                   String actionType,
                                   String targetType,
                                   String targetId,
                                   String payloadJson,
                                   String requesterId,
                                   String sourceTraceId,
                                   String sourceIdempotencyKey,
                                   String approvalReason) {
    }

    private record ApprovalResponse(String approvalNo) {
    }

    private record ApprovalRecordResponse(ApprovalRecord record) {
        private record ApprovalRecord(String tenantId,
                                      String approvalNo,
                                      String approvalType,
                                      String actionType,
                                      String targetType,
                                      String targetId,
                                      String payloadJson,
                                      String approvalStatus,
                                      String requesterId,
                                      String approverId,
                                      String rejectionReason,
                                      String approvalReason,
                                      String sourceTraceId,
                                      String sourceIdempotencyKey) {
        }
    }

    private record CompensationPayload(
            String tenantId,
            String compensationType,
            Long targetId,
            String targetInstanceNo,
            String jobCode,
            LocalDate bizDate,
            String batchNo,
            Long relatedFileId,
            String channelCode,
            String reason,
            String operatorId,
            String approvalId,
            String strategy,
            String traceId
    ) {
        private CompensationPayload withTraceId(String currentTraceId) {
            return new CompensationPayload(
                    tenantId,
                    compensationType,
                    targetId,
                    targetInstanceNo,
                    jobCode,
                    bizDate,
                    batchNo,
                    relatedFileId,
                    channelCode,
                    reason,
                    operatorId,
                    approvalId,
                    strategy,
                    currentTraceId
            );
        }
    }

    private record CompensationResponse(String commandNo) {
    }
}
