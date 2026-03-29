package com.example.batch.console.infrastructure;

import com.example.batch.console.application.ConsoleJobApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.config.ConsoleTriggerClientProperties;
import com.example.batch.console.mapper.BatchDayMapper;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.BatchDayCatchUpRequest;
import com.example.batch.console.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.PartitionReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TaskReplayRequest;
import com.example.batch.console.web.request.TriggerRequest;
import com.example.batch.console.web.response.ConsoleBatchDayCatchUpItemResponse;
import com.example.batch.console.web.response.ConsoleBatchDayCatchUpResponse;
import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.JsonUtils;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link com.example.batch.console.application.ConsoleJobApplicationService} 的默认实现：
 * 通过 RestClient 调用编排器与触发器开放 API，完成作业运维写操作。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobApplicationService implements ConsoleJobApplicationService {

    private final RestClient.Builder restClientBuilder;
    private final ConsoleTriggerClientProperties triggerClientProperties;
    private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
    private final ConsoleRequestMetadataResolver requestMetadataResolver;
    private final ConsoleTenantGuard tenantGuard;
    private final BatchDayMapper batchDayMapper;
    private final BusinessCalendarMapper businessCalendarMapper;

    /** 手工/API 触发作业运行。 */
    @Override
    public String trigger(TriggerRequest request, String idempotencyKey) {
        return delegateLaunch(
                resolveTenant(request.getTenantId()),
                ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
                request.getBizDate(),
                resolveTriggerType(request.getTriggerType(), TriggerType.MANUAL),
                parsePayload(request.getPayload()),
                idempotencyKey
        );
    }

    /** 登记补偿命令。 */
    @Override
    public String compensation(CompensationCommandRequest request, String idempotencyKey) {
        if (!hasText(request.getApprovalId())) {
            return submitApproval("COMPENSATION", "COMPENSATION", "JOB", String.valueOf(request.getTargetId()), request, request.getReason(), idempotencyKey);
        }
        requireApprovedApproval(resolveTenant(request.getTenantId()), request.getApprovalId());
        return submitCompensation(new CompensationPayload(
                resolveTenant(request.getTenantId()),
                ConsoleTextSanitizer.safeInput(request.getCompensationType(), 64),
                request.getTargetId(),
                ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128),
                ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
                parseOptionalBizDate(request.getBizDate()),
                ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128),
                request.getRelatedFileId(),
                ConsoleTextSanitizer.safeInput(request.getChannelCode(), 128),
                ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64),
                ConsoleTextSanitizer.safeInput(request.getStrategy(), 32),
                null
        ), idempotencyKey);
    }

    /** 执行补偿。 */
    @Override
    public String compensate(CompensateRequest request, String idempotencyKey) {
        return submitCompensation(new CompensationPayload(
                resolveTenant(request.getTenantId()),
                request.getCompensationType() == null || request.getCompensationType().isBlank() ? "JOB" : request.getCompensationType(),
                request.getTargetId(),
                ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128),
                ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
                parseOptionalBizDate(request.getBizDate()),
                ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128),
                request.getRelatedFileId(),
                ConsoleTextSanitizer.safeInput(request.getChannelCode(), 128),
                ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64),
                ConsoleTextSanitizer.safeInput(request.getStrategy(), 32),
                null
        ), idempotencyKey);
    }

    /** 重跑实例或分区。 */
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
                ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128),
                ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
                parseOptionalBizDate(request.getBizDate()),
                ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128),
                request.getRelatedFileId(),
                null,
                ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64),
                ConsoleTextSanitizer.safeInput(request.getStrategy(), 32),
                null
        ), idempotencyKey);
    }

    /** 死信重放。 */
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
                ConsoleTextSanitizer.safeInput(request.getReason(), 512),
                ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64),
                ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64),
                ConsoleTextSanitizer.safeInput(request.getStrategy(), 32),
                null
        ), idempotencyKey);
    }

    /** 任务重放（job_task 粒度）。 */
    @Override
    public String replayTask(TaskReplayRequest request, String idempotencyKey) {
        if (!hasText(request.getApprovalId())) {
            // approvalType 受数据库约束，这里复用 COMPENSATION + RETRY。
            return submitApproval(
                    "COMPENSATION",
                    "RETRY",
                    "JOB_TASK",
                    String.valueOf(request.getTaskId()),
                    request,
                    request.getReason(),
                    idempotencyKey
            );
        }
        requireApprovedApproval(resolveTenant(request.getTenantId()), request.getApprovalId());
        return triggerRecovery(
                resolveTenant(request.getTenantId()),
                "/internal/recoveries/tasks/{taskId}/replay",
                request.getTaskId(),
                idempotencyKey
        );
    }

    /** 分区重放（job_partition 粒度）。 */
    @Override
    public String replayPartition(PartitionReplayRequest request, String idempotencyKey) {
        if (!hasText(request.getApprovalId())) {
            return submitApproval(
                    "COMPENSATION",
                    "RETRY",
                    "JOB_PARTITION",
                    String.valueOf(request.getPartitionId()),
                    request,
                    request.getReason(),
                    idempotencyKey
            );
        }
        requireApprovedApproval(resolveTenant(request.getTenantId()), request.getApprovalId());
        return triggerRecovery(
                resolveTenant(request.getTenantId()),
                "/internal/recoveries/partitions/{partitionId}/replay",
                request.getPartitionId(),
                idempotencyKey
        );
    }

    /** 审批通过 Catch-Up 请求。 */
    @Override
    public String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
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
        params.put("reason", ConsoleTextSanitizer.safeInput(request.getReason(), 512));
        params.put("scheduledAt", request.getScheduledAt());
        return delegateLaunch(
                resolveTenant(request.getTenantId()),
                ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
                request.getBizDate(),
                TriggerType.CATCH_UP,
                params,
                idempotencyKey
        );
    }

    @Override
    public ConsoleBatchDayCatchUpResponse catchUpBatchDay(String bizDate,
                                                          BatchDayCatchUpRequest request,
                                                          String idempotencyKey) {
        String tenantId = resolveTenant(request.getTenantId());
        String calendarCode = ConsoleTextSanitizer.safeInput(request.getCalendarCode(), 128);
        Map<String, Object> calendar = businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode);
        if (calendar == null || calendar.isEmpty()) {
            throw new BizException(ResultCode.NOT_FOUND, "business calendar not found");
        }
        String catchUpPolicy = stringValue(calendar.get("catchUpPolicy"));
        CatchUpPolicyType policyType = CatchUpPolicyType.fromCode(catchUpPolicy);
        List<String> jobCodes = resolveJobCodes(tenantId, calendarCode, parseBizDate(bizDate), request.getJobCodes());
        List<ConsoleBatchDayCatchUpItemResponse> items = new ArrayList<>();
        for (String jobCode : jobCodes) {
            String itemRequestId = IdGenerator.newBusinessNo("catchup");
            String itemIdempotencyKey = idempotencyKey + ":" + jobCode;
            if (policyType == CatchUpPolicyType.AUTO) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("operationType", "BATCH_DAY_CATCH_UP");
                params.put("approvalMode", "AUTO");
                params.put("batchDayCatchUp", true);
                params.put("batchDayBizDate", bizDate);
                params.put("batchDayCalendarCode", calendarCode);
                params.put("jobCode", jobCode);
                params.put("reason", ConsoleTextSanitizer.safeInput(request.getReason(), 512));
                params.put("catchUpPolicy", catchUpPolicy);
                String instanceNo = delegateLaunch(
                        tenantId,
                        jobCode,
                        bizDate,
                        TriggerType.CATCH_UP,
                        params,
                        itemIdempotencyKey
                );
                items.add(new ConsoleBatchDayCatchUpItemResponse(
                        jobCode,
                        "LAUNCHED",
                        instanceNo,
                        TriggerType.CATCH_UP.code(),
                        "LAUNCHED"
                ));
            } else {
                ConsoleCatchUpApprovalRequest approvalRequest = new ConsoleCatchUpApprovalRequest();
                approvalRequest.setTenantId(tenantId);
                approvalRequest.setRequestId(itemRequestId);
                approvalRequest.setJobCode(jobCode);
                approvalRequest.setBizDate(bizDate);
                approvalRequest.setScheduledAt(Instant.now().toString());
                approvalRequest.setReason(ConsoleTextSanitizer.safeInput(request.getReason(), 512));
                String approvalNo = approveCatchUp(approvalRequest, itemIdempotencyKey);
                items.add(new ConsoleBatchDayCatchUpItemResponse(
                        jobCode,
                        "APPROVAL_CREATED",
                        approvalNo,
                        TriggerType.CATCH_UP.code(),
                        "PENDING"
                ));
            }
        }
        return new ConsoleBatchDayCatchUpResponse(
                tenantId,
                calendarCode,
                bizDate,
                catchUpPolicy,
                items
        );
    }

    private String approvePendingCatchUpRequest(ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
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
                        ConsoleTextSanitizer.safeInput(request.getRequestId(), 128),
                        ConsoleTextSanitizer.safeInput(request.getReason(), 512)
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
                        ConsoleTextSanitizer.safeInput(jobCode, 128),
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

    private List<String> resolveJobCodes(String tenantId,
                                         String calendarCode,
                                         LocalDate bizDate,
                                         List<String> requestedJobCodes) {
        if (requestedJobCodes != null && !requestedJobCodes.isEmpty()) {
            return requestedJobCodes.stream()
                    .filter(this::hasText)
                    .map(code -> ConsoleTextSanitizer.safeInput(code, 128))
                    .distinct()
                    .toList();
        }
        List<String> failedJobCodes = batchDayMapper.selectFailedJobCodes(tenantId, calendarCode, bizDate);
        return failedJobCodes == null ? List.of() : failedJobCodes;
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

    private record RecoveryOperationResponse(String operationNo) {
    }

    private String triggerRecovery(String tenantId,
                                    String uriTemplate,
                                    Long targetId,
                                    String idempotencyKey) {
        ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
        RestClient restClient = restClientBuilder.baseUrl(orchestratorClientProperties.getBaseUrl()).build();
        CommonResponse<RecoveryOperationResponse> response = restClient.post()
                .uri(uriTemplate, targetId)
                .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
                .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
                .body(Map.of("tenantId", tenantId))
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<CommonResponse<RecoveryOperationResponse>>() {
                });
        if (response == null || response.data() == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "orchestrator returned empty recovery response");
        }
        return response.data().operationNo();
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
                        ConsoleTextSanitizer.safeInput(requestMetadata.operatorId(), 64),
                        requestMetadata.traceId(),
                        idempotencyKey,
                        ConsoleTextSanitizer.safeInput(approvalReason, 512)
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
        if (payload instanceof TaskReplayRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof PartitionReplayRequest request) {
            return request.getTenantId();
        }
        if (payload instanceof ConsoleCatchUpApprovalRequest request) {
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
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
