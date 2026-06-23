package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.web.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchDayCatchUpItemResponse;
import io.github.pinpols.batch.console.domain.job.web.response.ConsoleBatchDayCatchUpResponse;
import io.github.pinpols.batch.console.domain.ops.infrastructure.ConsoleJobOpsSupport;
import io.github.pinpols.batch.console.domain.ops.infrastructure.ConsoleJobOpsSupport.ApprovalSubmitContext;
import io.github.pinpols.batch.console.domain.ops.infrastructure.TriggerInternalRestClient;
import io.github.pinpols.batch.console.domain.ops.web.request.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/** 控制台作业审批服务实现：Catch-Up 审批、批量日 Catch-Up。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobApprovalService implements ConsoleJobApprovalService {

  private final ConsoleJobOpsSupport ops;

  /** P0-1(2026-05-16):同 ConsoleJobOpsSupport 一起切到带 secret 的 trigger client。 */
  private final TriggerInternalRestClient triggerInternalRestClient;

  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final BatchDayMapper batchDayMapper;
  private final BusinessCalendarMapper businessCalendarMapper;

  @Override
  public String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      ApprovalSubmitContext approvalCtx =
          ApprovalSubmitContext.builder()
              .approvalType("CATCH_UP")
              .actionType("CATCH_UP")
              .targetType("CATCH_UP")
              .targetId(request.getRequestId())
              .payload(request)
              .approvalReason(request.getReason())
              .idempotencyKey(idempotencyKey)
              .build();
      String result = ops.submitApproval(approvalCtx);
      ops.publishRefresh(tenantId);
      return result;
    }
    ops.requireApprovedApproval(tenantId, request.getApprovalId());
    if (request.getRequestId() != null && !request.getRequestId().isBlank()) {
      String result = approvePendingCatchUpRequest(request, idempotencyKey);
      ops.publishRefresh(tenantId);
      return result;
    }
    Map<String, Object> params = new LinkedHashMap<>();
    params.put("operationType", "CATCH_UP_APPROVAL");
    params.put("approvalMode", "MANUAL_APPROVAL");
    params.put("catchUpApproved", true);
    params.put("reason", ConsoleTextSanitizer.safeInput(request.getReason(), 512));
    params.put("scheduledAt", request.getScheduledAt());
    String result =
        ops.delegateLaunch(
            tenantId,
            ConsoleTextSanitizer.safeInput(request.getJobCode(), 128),
            request.getBizDate(),
            TriggerType.CATCH_UP,
            params,
            idempotencyKey);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  public ConsoleBatchDayCatchUpResponse catchUpBatchDay(
      String bizDate, BatchDayCatchUpRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    String calendarCode = ConsoleTextSanitizer.safeInput(request.getCalendarCode(), 128);
    Map<String, Object> calendar =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode);
    if (calendar == null || calendar.isEmpty()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.business_calendar.not_found");
    }
    String catchUpPolicy = stringValue(calendar.get("catchUpPolicy"));
    CatchUpPolicyType policyType = CatchUpPolicyType.fromCode(catchUpPolicy);
    List<String> jobCodes =
        resolveJobCodes(tenantId, calendarCode, ops.parseBizDate(bizDate), request.getJobCodes());
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
            ops.delegateLaunch(
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
        approvalRequest.setScheduledAt(BatchDateTimeSupport.utcNow().toString());
        approvalRequest.setReason(ConsoleTextSanitizer.safeInput(request.getReason(), 512));
        String approvalNo = approveCatchUp(approvalRequest, itemIdempotencyKey);
        items.add(
            new ConsoleBatchDayCatchUpItemResponse(
                jobCode, "APPROVAL_CREATED", approvalNo, TriggerType.CATCH_UP.code(), "PENDING"));
      }
    }
    ConsoleBatchDayCatchUpResponse response =
        new ConsoleBatchDayCatchUpResponse(tenantId, calendarCode, bizDate, catchUpPolicy, items);
    ops.publishRefresh(tenantId);
    return response;
  }

  private String approvePendingCatchUpRequest(
      ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    // P0-1(2026-05-16):同 ConsoleJobOpsSupport.delegateLaunch — 走带 X-Internal-Secret 的 client
    RestClient restClient = triggerInternalRestClient.build();
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
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.trigger.empty_response");
    }
    return response.data().instanceNo();
  }

  private List<String> resolveJobCodes(
      String tenantId, String calendarCode, LocalDate bizDate, List<String> requestedJobCodes) {
    if (requestedJobCodes != null && !requestedJobCodes.isEmpty()) {
      return requestedJobCodes.stream()
          .filter(ops::hasText)
          .map(code -> ConsoleTextSanitizer.safeInput(code, 128))
          .distinct()
          .toList();
    }
    List<String> failedJobCodes =
        batchDayMapper.selectFailedJobCodes(tenantId, calendarCode, bizDate);
    return failedJobCodes == null ? List.of() : failedJobCodes;
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private record CatchUpApprovalPayload(String tenantId, String requestId, String reason) {}
}
