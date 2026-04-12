package com.example.batch.console.infrastructure;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.dto.LaunchResponse;
import com.example.batch.common.enums.CatchUpPolicyType;
import com.example.batch.common.enums.ResultCode;
import com.example.batch.common.enums.TriggerType;
import com.example.batch.common.exception.BizException;
import com.example.batch.common.utils.ConsoleTextSanitizer;
import com.example.batch.common.utils.Guard;
import com.example.batch.common.utils.IdGenerator;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.application.ConsoleJobApplicationService;
import com.example.batch.console.config.ConsoleOrchestratorClientProperties;
import com.example.batch.console.config.ConsoleTriggerClientProperties;
import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeDomainEventPublisher;
import com.example.batch.console.mapper.BatchDayMapper;
import com.example.batch.console.mapper.BusinessCalendarMapper;
import com.example.batch.console.support.ConsoleRequestMetadata;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.ConsoleTenantGuard;
import com.example.batch.console.web.request.BatchDayCatchUpRequest;
import com.example.batch.console.web.request.CompensateRequest;
import com.example.batch.console.web.request.CompensationCommandRequest;
import com.example.batch.console.web.request.ConsoleCatchUpApprovalRequest;
import com.example.batch.console.web.request.DeadLetterReplayRequest;
import com.example.batch.console.web.request.PartitionReplayRequest;
import com.example.batch.console.web.request.RerunRequest;
import com.example.batch.console.web.request.TaskReplayRequest;
import com.example.batch.console.web.request.TriggerRequest;
import com.example.batch.console.web.response.ConsoleBatchDayCatchUpItemResponse;
import com.example.batch.console.web.response.ConsoleBatchDayCatchUpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * {@link com.example.batch.console.application.ConsoleJobApplicationService} 的默认实现： 通过 RestClient
 * 调用编排器与触发器开放 API，完成作业运维写操作。
 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobApplicationService implements ConsoleJobApplicationService {

  // ── duplicate literal constants ─────────────────────────────────────────
  private static final String JOB_TYPE_COMPENSATION = "COMPENSATION";

  private final RestClient.Builder restClientBuilder;
  private final ConsoleTriggerClientProperties triggerClientProperties;
  private final ConsoleOrchestratorClientProperties orchestratorClientProperties;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final ConsoleTenantGuard tenantGuard;
  private final BatchDayMapper batchDayMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final ConsoleRealtimeDomainEventPublisher domainEventPublisher;
  private final Environment environment;
  private final com.example.batch.console.mapper.JobDefinitionMapper jobDefinitionMapper;

  /** 手工/API 触发作业运行。 */
  @Override
  public String trigger(TriggerRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    String result =
        delegateLaunch(
            tenantId,
            ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
            request.getBizDate(),
            resolveTriggerType(request.getTriggerType(), TriggerType.MANUAL),
            parsePayload(request.getPayload()),
            idempotencyKey);
    publishRefresh(tenantId);
    return result;
  }

  /** 登记补偿命令。 */
  @Override
  public String compensation(CompensationCommandRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    if (!hasText(request.getApprovalId())) {
      String result =
          submitApproval(
              new ApprovalSubmitContext(
                  JOB_TYPE_COMPENSATION,
                  JOB_TYPE_COMPENSATION,
                  "JOB",
                  String.valueOf(request.getTargetId()),
                  request,
                  request.getReason(),
                  idempotencyKey));
      publishRefresh(tenantId);
      return result;
    }
    requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType(ConsoleTextSanitizer.safeInput(request.getCompensationType(), 64))
                .targetId(request.getTargetId())
                .targetInstanceNo(
                    ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
                .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128))
                .bizDate(parseOptionalBizDate(request.getBizDate()))
                .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
                .relatedFileId(request.getRelatedFileId())
                .channelCode(ConsoleTextSanitizer.safeInput(request.getChannelCode(), 128))
                .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
                .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
                .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
                .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
                .build(),
            idempotencyKey);
    publishRefresh(tenantId);
    return result;
  }

  /** 执行补偿。 */
  @Override
  public String compensate(CompensateRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    String result =
        submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType(
                    request.getCompensationType() == null || request.getCompensationType().isBlank()
                        ? "JOB"
                        : request.getCompensationType())
                .targetId(request.getTargetId())
                .targetInstanceNo(
                    ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
                .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128))
                .bizDate(parseOptionalBizDate(request.getBizDate()))
                .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
                .relatedFileId(request.getRelatedFileId())
                .channelCode(ConsoleTextSanitizer.safeInput(request.getChannelCode(), 128))
                .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
                .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
                .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
                .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
                .build(),
            idempotencyKey);
    publishRefresh(tenantId);
    return result;
  }

  /** 重跑实例或分区。 */
  @Override
  public String rerun(RerunRequest request, String idempotencyKey) {
    String compensationType =
        (request.getTargetId() != null
                || (request.getTargetInstanceNo() != null
                    && !request.getTargetInstanceNo().isBlank()))
            ? "JOB"
            : "BATCH";
    String tenantId = resolveTenant(request.getTenantId());
    String result =
        submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType(compensationType)
                .targetId(request.getTargetId())
                .targetInstanceNo(
                    ConsoleTextSanitizer.safeInput(request.getTargetInstanceNo(), 128))
                .jobCode(ConsoleTextSanitizer.safeInput(request.getJobCode(), 128))
                .bizDate(parseOptionalBizDate(request.getBizDate()))
                .batchNo(ConsoleTextSanitizer.safeInput(request.getBatchNo(), 128))
                .relatedFileId(request.getRelatedFileId())
                .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
                .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
                .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
                .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
                .build(),
            idempotencyKey);
    publishRefresh(tenantId);
    return result;
  }

  /** 死信重放。 */
  @Override
  public String replayDeadLetter(DeadLetterReplayRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    if (!hasText(request.getApprovalId())) {
      String result =
          submitApproval(
              new ApprovalSubmitContext(
                  "DLQ_REPLAY",
                  "DLQ_REPLAY",
                  "DLQ",
                  String.valueOf(request.getDeadLetterId()),
                  request,
                  request.getReason(),
                  idempotencyKey));
      publishRefresh(tenantId);
      return result;
    }
    requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        submitCompensation(
            CompensationPayload.builder()
                .tenantId(tenantId)
                .compensationType("DLQ")
                .targetId(request.getDeadLetterId())
                .reason(ConsoleTextSanitizer.safeInput(request.getReason(), 512))
                .operatorId(ConsoleTextSanitizer.safeInput(request.getOperatorId(), 64))
                .approvalId(ConsoleTextSanitizer.safeInput(request.getApprovalId(), 64))
                .strategy(ConsoleTextSanitizer.safeInput(request.getStrategy(), 32))
                .build(),
            idempotencyKey);
    publishRefresh(tenantId);
    return result;
  }

  /** 任务重放（job_task 粒度）。 */
  @Override
  public String replayTask(TaskReplayRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    if (!hasText(request.getApprovalId())) {
      // approvalType 受数据库约束，这里复用 COMPENSATION + RETRY。
      String result =
          submitApproval(
              new ApprovalSubmitContext(
                  JOB_TYPE_COMPENSATION,
                  "RETRY",
                  "JOB_TASK",
                  String.valueOf(request.getTaskId()),
                  request,
                  request.getReason(),
                  idempotencyKey));
      publishRefresh(tenantId);
      return result;
    }
    requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        triggerRecovery(
            tenantId,
            "/internal/recoveries/tasks/{taskId}/replay",
            request.getTaskId(),
            idempotencyKey);
    publishRefresh(tenantId);
    return result;
  }

  /** 分区重放（job_partition 粒度）。 */
  @Override
  public String replayPartition(PartitionReplayRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    if (!hasText(request.getApprovalId())) {
      String result =
          submitApproval(
              new ApprovalSubmitContext(
                  JOB_TYPE_COMPENSATION,
                  "RETRY",
                  "JOB_PARTITION",
                  String.valueOf(request.getPartitionId()),
                  request,
                  request.getReason(),
                  idempotencyKey));
      publishRefresh(tenantId);
      return result;
    }
    requireApprovedApproval(tenantId, request.getApprovalId());
    String result =
        triggerRecovery(
            tenantId,
            "/internal/recoveries/partitions/{partitionId}/replay",
            request.getPartitionId(),
            idempotencyKey);
    publishRefresh(tenantId);
    return result;
  }

  /** 审批通过 Catch-Up 请求。 */
  @Override
  public String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    if (!hasText(request.getApprovalId())) {
      String result =
          submitApproval(
              new ApprovalSubmitContext(
                  "CATCH_UP",
                  "CATCH_UP",
                  "CATCH_UP",
                  request.getRequestId(),
                  request,
                  request.getReason(),
                  idempotencyKey));
      publishRefresh(tenantId);
      return result;
    }
    requireApprovedApproval(tenantId, request.getApprovalId());
    if (request.getRequestId() != null && !request.getRequestId().isBlank()) {
      String result = approvePendingCatchUpRequest(request, idempotencyKey);
      publishRefresh(tenantId);
      return result;
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("operationType", "CATCH_UP_APPROVAL");
    params.put("approvalMode", "MANUAL_APPROVAL");
    params.put("catchUpApproved", true);
    params.put("reason", ConsoleTextSanitizer.safeInput(request.getReason(), 512));
    params.put("scheduledAt", request.getScheduledAt());
    String result =
        delegateLaunch(
            tenantId,
            ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
            request.getBizDate(),
            TriggerType.CATCH_UP,
            params,
            idempotencyKey);
    publishRefresh(tenantId);
    return result;
  }

  @Override
  public ConsoleBatchDayCatchUpResponse catchUpBatchDay(
      String bizDate, BatchDayCatchUpRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    String calendarCode = ConsoleTextSanitizer.safeInput(request.getCalendarCode(), 128);
    Map<String, Object> calendar =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode);
    if (calendar == null || calendar.isEmpty()) {
      throw new BizException(ResultCode.NOT_FOUND, "business calendar not found");
    }
    String catchUpPolicy = stringValue(calendar.get("catchUpPolicy"));
    CatchUpPolicyType policyType = CatchUpPolicyType.fromCode(catchUpPolicy);
    List<String> jobCodes =
        resolveJobCodes(tenantId, calendarCode, parseBizDate(bizDate), request.getJobCodes());
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
        String instanceNo =
            delegateLaunch(
                tenantId, jobCode, bizDate, TriggerType.CATCH_UP, params, itemIdempotencyKey);
        items.add(
            new ConsoleBatchDayCatchUpItemResponse(
                jobCode, "LAUNCHED", instanceNo, TriggerType.CATCH_UP.code(), "LAUNCHED"));
      } else {
        ConsoleCatchUpApprovalRequest approvalRequest = new ConsoleCatchUpApprovalRequest();
        approvalRequest.setTenantId(tenantId);
        approvalRequest.setRequestId(itemRequestId);
        approvalRequest.setJobCode(jobCode);
        approvalRequest.setBizDate(bizDate);
        approvalRequest.setScheduledAt(Instant.now().toString());
        approvalRequest.setReason(ConsoleTextSanitizer.safeInput(request.getReason(), 512));
        String approvalNo = approveCatchUp(approvalRequest, itemIdempotencyKey);
        items.add(
            new ConsoleBatchDayCatchUpItemResponse(
                jobCode, "APPROVAL_CREATED", approvalNo, TriggerType.CATCH_UP.code(), "PENDING"));
      }
    }
    ConsoleBatchDayCatchUpResponse response =
        new ConsoleBatchDayCatchUpResponse(tenantId, calendarCode, bizDate, catchUpPolicy, items);
    publishRefresh(tenantId);
    return response;
  }

  @Override
  public Map<String, Object> dryRunTrigger(TriggerRequest request) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("dryRun", true);
    List<String> errors = new ArrayList<>();
    String tenantId;
    try {
      tenantId = resolveTenant(request.getTenantId());
    } catch (Exception e) {
      errors.add("tenantId invalid: " + e.getMessage());
      result.put("valid", false);
      result.put("errors", errors);
      return result;
    }
    result.put("tenantId", tenantId);
    result.put("jobCode", request.getJobCode());
    result.put("bizDate", request.getBizDate());

    if (request.getJobCode() == null || request.getJobCode().isBlank()) {
      errors.add("jobCode is required");
    }
    if (request.getBizDate() == null || request.getBizDate().isBlank()) {
      errors.add("bizDate is required");
    } else {
      try {
        parseBizDate(request.getBizDate());
      } catch (Exception e) {
        errors.add("bizDate format invalid (expected yyyy-MM-dd)");
      }
    }
    if (request.getTriggerType() != null && !request.getTriggerType().isBlank()) {
      try {
        resolveTriggerType(request.getTriggerType(), TriggerType.MANUAL);
      } catch (Exception e) {
        errors.add("unsupported triggerType: " + request.getTriggerType());
      }
    }
    if (errors.isEmpty() && request.getJobCode() != null) {
      var jobDef = jobDefinitionMapper.selectByUniqueKey(tenantId, request.getJobCode());
      if (jobDef == null) {
        errors.add("job definition not found: " + request.getJobCode());
      } else if (jobDef.getEnabled() != null && !jobDef.getEnabled()) {
        errors.add("job definition is disabled: " + request.getJobCode());
      }
    }
    result.put("valid", errors.isEmpty());
    if (!errors.isEmpty()) {
      result.put("errors", errors);
    }
    return result;
  }

  @Override
  public List<Map<String, Object>> batchTrigger(List<TriggerRequest> items, String idempotencyKey) {
    List<Map<String, Object>> results = new ArrayList<>();
    for (int i = 0; i < items.size(); i++) {
      TriggerRequest item = items.get(i);
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("index", i);
      entry.put("jobCode", item.getJobCode());
      entry.put("bizDate", item.getBizDate());
      try {
        if (item.isDryRun()) {
          Map<String, Object> dryRun = dryRunTrigger(item);
          entry.put("dryRun", true);
          entry.put(
              "status", Boolean.TRUE.equals(dryRun.get("valid")) ? "DRY_RUN_OK" : "DRY_RUN_FAILED");
          entry.put("result", dryRun);
        } else {
          String itemKey = idempotencyKey + ":" + i;
          String instanceNo = trigger(item, itemKey);
          entry.put("status", "SUCCESS");
          entry.put("instanceNo", instanceNo);
        }
      } catch (Exception e) {
        entry.put("status", "FAILED");
        entry.put("error", e.getMessage());
      }
      results.add(entry);
    }
    return results;
  }

  private String approvePendingCatchUpRequest(
      ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
    String tenantId = resolveTenant(request.getTenantId());
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(triggerClientProperties.getBaseUrl())).build();
    CommonResponse<LaunchResponse> response =
        restClient
            .post()
            .uri("/api/triggers/catch-up/approve")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(
                new CatchUpApprovalPayload(
                    tenantId,
                    ConsoleTextSanitizer.safeInput(request.getRequestId(), 128),
                    ConsoleTextSanitizer.safeInput(request.getReason(), 512)))
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<LaunchResponse>>() {});
    if (response == null || response.data() == null) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "trigger service returned empty response");
    }
    return response.data().instanceNo();
  }

  /** 控制台只做受控触发入口，实际受理仍交给 trigger/orchestrator 主链处理。 */
  private String delegateLaunch(
      String tenantId,
      String jobCode,
      String bizDate,
      TriggerType triggerType,
      Map<String, Object> params,
      String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(triggerClientProperties.getBaseUrl())).build();
    CommonResponse<LaunchResponse> response =
        restClient
            .post()
            .uri("/api/triggers/launch")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(
                new TriggerLaunchPayload(
                    tenantId,
                    ConsoleTextSanitizer.safeInput(jobCode, 128),
                    parseBizDate(bizDate),
                    triggerType,
                    params == null ? Map.of() : params))
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<LaunchResponse>>() {});
    if (response == null || response.data() == null) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "trigger service returned empty response");
    }
    return response.data().instanceNo();
  }

  private void publishRefresh(String tenantId) {
    domainEventPublisher.publishChanged(tenantId, "job-instances", "job-instance-updated");
    domainEventPublisher.publishChanged(tenantId, "workflow-runs", "workflow-run-updated");
    domainEventPublisher.publishChanged(tenantId, "outbox-retries", "outbox-retry-updated");
    domainEventPublisher.publishChanged(tenantId, "outbox-deliveries", "outbox-delivery-updated");
    domainEventPublisher.publishSummaryRefresh(tenantId);
  }

  private List<String> resolveJobCodes(
      String tenantId, String calendarCode, LocalDate bizDate, List<String> requestedJobCodes) {
    if (requestedJobCodes != null && !requestedJobCodes.isEmpty()) {
      return requestedJobCodes.stream()
          .filter(this::hasText)
          .map(code -> ConsoleTextSanitizer.safeInput(code, 128))
          .distinct()
          .toList();
    }
    List<String> failedJobCodes =
        batchDayMapper.selectFailedJobCodes(tenantId, calendarCode, bizDate);
    return failedJobCodes == null ? List.of() : failedJobCodes;
  }

  private String submitCompensation(CompensationPayload payload, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    CompensationResponse response =
        restClient
            .post()
            .uri("/internal/compensations")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(payload.withTraceId(requestMetadata.traceId()))
            .retrieve()
            .body(CompensationResponse.class);
    if (response == null || response.commandNo() == null) {
      throw new BizException(
          ResultCode.SYSTEM_ERROR, "orchestrator returned empty compensation response");
    }
    return response.commandNo();
  }

  private record RecoveryOperationResponse(String operationNo) {}

  private String triggerRecovery(
      String tenantId, String uriTemplate, Long targetId, String idempotencyKey) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    CommonResponse<RecoveryOperationResponse> response =
        restClient
            .post()
            .uri(uriTemplate, targetId)
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(Map.of("tenantId", tenantId))
            .retrieve()
            .body(new ParameterizedTypeReference<CommonResponse<RecoveryOperationResponse>>() {});
    if (response == null || response.data() == null) {
      throw new BizException(
          ResultCode.SYSTEM_ERROR, "orchestrator returned empty recovery response");
    }
    return response.data().operationNo();
  }

  private record ApprovalSubmitContext(
      String approvalType,
      String actionType,
      String targetType,
      String targetId,
      Object payload,
      String approvalReason,
      String idempotencyKey) {}

  private String submitApproval(ApprovalSubmitContext ctx) {
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    ApprovalResponse response =
        restClient
            .post()
            .uri("/internal/approvals")
            .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, ctx.idempotencyKey())
            .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
            .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId())
            .body(
                ApprovalRequest.of(
                    new ApprovalTarget(
                        resolveTenant(extractTenantId(ctx.payload())),
                        ctx.approvalType(),
                        ctx.actionType(),
                        ctx.targetType(),
                        ctx.targetId()),
                    ctx.payload(),
                    requestMetadata,
                    ctx.idempotencyKey(),
                    ctx.approvalReason()))
            .retrieve()
            .body(ApprovalResponse.class);
    if (response == null || !hasText(response.approvalNo())) {
      throw new BizException(ResultCode.SYSTEM_ERROR, "approval service returned empty response");
    }
    return response.approvalNo();
  }

  private void requireApprovedApproval(String tenantId, String approvalNo) {
    RestClient restClient =
        restClientBuilder.baseUrl(resolveUrl(orchestratorClientProperties.getBaseUrl())).build();
    ApprovalRecordResponse response =
        restClient
            .get()
            .uri("/internal/approvals/{approvalNo}?tenantId={tenantId}", approvalNo, tenantId)
            .retrieve()
            .body(ApprovalRecordResponse.class);
    Guard.requireFound(
        response == null || response.getRecord() == null, "approval request not found");
    String status = response.getRecord().getApprovalStatus();
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
      throw new BizException(
          ResultCode.INVALID_ARGUMENT, "unsupported triggerType: " + triggerTypeValue);
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
      Map<String, Object> params) {}

  private record CatchUpApprovalPayload(String tenantId, String requestId, String reason) {}

  private record ApprovalTarget(
      String tenantId,
      String approvalType,
      String actionType,
      String targetType,
      String targetId) {}

  @Getter
  private static final class ApprovalRequest {
    private final String tenantId;
    private final String approvalType;
    private final String actionType;
    private final String targetType;
    private final String targetId;
    private final String payloadJson;
    private final String requesterId;
    private final String sourceTraceId;
    private final String sourceIdempotencyKey;
    private final String approvalReason;

    private ApprovalRequest(
        ApprovalTarget target,
        String payloadJson,
        String requesterId,
        String sourceTraceId,
        String sourceIdempotencyKey,
        String approvalReason) {
      this.tenantId = target.tenantId();
      this.approvalType = target.approvalType();
      this.actionType = target.actionType();
      this.targetType = target.targetType();
      this.targetId = target.targetId();
      this.payloadJson = payloadJson;
      this.requesterId = requesterId;
      this.sourceTraceId = sourceTraceId;
      this.sourceIdempotencyKey = sourceIdempotencyKey;
      this.approvalReason = approvalReason;
    }

    private static ApprovalRequest of(
        ApprovalTarget target,
        Object payload,
        ConsoleRequestMetadata metadata,
        String idempotencyKey,
        String approvalReason) {
      return new ApprovalRequest(
          target,
          JsonUtils.toJson(payload),
          ConsoleTextSanitizer.safeInput(metadata.operatorId(), 64),
          metadata.traceId(),
          idempotencyKey,
          ConsoleTextSanitizer.safeInput(approvalReason, 512));
    }
  }

  private record ApprovalResponse(String approvalNo) {}

  @Getter
  @Setter
  @NoArgsConstructor
  private static class ApprovalRecordResponse {
    private ApprovalRecord record;
  }

  @Getter
  @Setter
  @NoArgsConstructor
  private static class ApprovalRecord {
    private String tenantId;
    private String approvalNo;
    private String approvalType;
    private String actionType;
    private String targetType;
    private String targetId;
    private String payloadJson;
    private String approvalStatus;
    private String requesterId;
    private String approverId;
    private String rejectionReason;
    private String approvalReason;
    private String sourceTraceId;
    private String sourceIdempotencyKey;
  }

  @Getter
  @Builder(toBuilder = true)
  private static class CompensationPayload {
    private final String tenantId;
    private final String compensationType;
    private final Long targetId;
    private final String targetInstanceNo;
    private final String jobCode;
    private final LocalDate bizDate;
    private final String batchNo;
    private final Long relatedFileId;
    private final String channelCode;
    private final String reason;
    private final String operatorId;
    private final String approvalId;
    private final String strategy;
    private final String traceId;

    private CompensationPayload withTraceId(String currentTraceId) {
      return toBuilder().traceId(currentTraceId).build();
    }
  }

  private record CompensationResponse(String commandNo) {}

  private String resolveUrl(String url) {
    return environment.resolvePlaceholders(url);
  }
}
