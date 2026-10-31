package io.github.pinpols.batch.console.domain.job.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.CryptoAlgorithms;
import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.application.ops.ConsoleJobOperationsPort;
import io.github.pinpols.batch.console.application.workflow.ManagedJobWorkflowReferencePort;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.application.contract.request.BatchDayCatchUpRequest;
import io.github.pinpols.batch.console.domain.job.application.contract.response.BatchDayCatchUpRequestStatusResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayCatchUpItemResponse;
import io.github.pinpols.batch.console.domain.job.application.contract.response.ConsoleBatchDayCatchUpResponse;
import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayMapper;
import io.github.pinpols.batch.console.domain.job.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.console.domain.job.mapper.JobDefinitionMapper;
import io.github.pinpols.batch.console.domain.job.view.BatchDayCatchUpOperation;
import io.github.pinpols.batch.console.domain.job.view.ConsoleJobConfigViews.ActiveCalendarRow;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.ApprovalSubmitContext;
import io.github.pinpols.batch.console.shared.command.ConsoleCatchUpApprovalRequest;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/** 控制台作业审批服务实现：Catch-Up 审批、批量日 Catch-Up。 */
@Service
@RequiredArgsConstructor
public class DefaultConsoleJobApprovalService implements ConsoleJobApprovalService {

  private static final String CATCH_UP = "CATCH_UP";
  private static final String CONFLICT_MESSAGE = "error.common.conflict";

  private final ConsoleJobOperationsPort ops;

  /** P0-1(2026-05-16):同 ConsoleJobOpsSupport 一起切到带 secret 的 trigger client。 */
  private final TriggerInternalRestClient triggerInternalRestClient;

  private final ConsoleRequestMetadataResolver requestMetadataResolver;
  private final BatchDayMapper batchDayMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final JobDefinitionMapper jobDefinitionMapper;
  private final ManagedJobWorkflowReferencePort managedJobWorkflowReference;
  private final BatchDayCatchUpOperationStore operationStore;
  private final OrchestratorApprovalClient approvalClient;

  @Override
  public String approveCatchUp(ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!ops.hasText(request.getApprovalId())) {
      ApprovalSubmitContext approvalCtx = ApprovalSubmitContext.builder()
          .approvalType(CATCH_UP)
          .actionType(CATCH_UP)
          .targetType(CATCH_UP)
          .targetId(request.getRequestId())
          .payload(request)
          .approvalReason(request.getReason())
          .idempotencyKey(idempotencyKey)
          .build();
      String result = ops.submitApproval(approvalCtx);
      ops.publishRefresh(tenantId);
      return result;
    }
    ops.requireApprovedApproval(tenantId, request.getApprovalId(), CATCH_UP, request);
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
    String sanitizedJobCode = ConsoleTextSanitizer.safeInput(request.getJobCode(), 128);
    if (ops.hasText(request.getBatchDayCalendarCode())) {
      String calendarCode = ConsoleTextSanitizer.safeInput(request.getBatchDayCalendarCode(), 128);
      params.put("batchDayCatchUp", true);
      params.put("batchDayCalendarCode", calendarCode);
      params.put("batchDayBizDate", request.getBizDate());
      params.put("jobCode", sanitizedJobCode);
    }
    Long managedWorkflowId = resolveManagedWorkflowId(tenantId, request.getJobCode());
    ConsoleLaunchCommand launchCommand = new ConsoleLaunchCommand(
        tenantId,
        sanitizedJobCode,
        managedWorkflowId,
        request.getBizDate(),
        TriggerType.CATCH_UP,
        params,
        idempotencyKey);
    String result = ops.delegateLaunch(launchCommand);
    ops.publishRefresh(tenantId);
    return result;
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public ConsoleBatchDayCatchUpResponse catchUpBatchDay(
      String bizDate, BatchDayCatchUpRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    if (!Texts.hasText(idempotencyKey)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    String calendarCode = ConsoleTextSanitizer.safeInput(request.getCalendarCode(), 128);
    LocalDate parsedBizDate = ops.parseBizDate(bizDate);
    String safeReason = ConsoleTextSanitizer.safeInput(request.getReason(), 512);
    List<String> requested = sanitizedRequestedJobCodes(request.getJobCodes());
    String requestJson =
        JsonUtils.toJson(new BatchRequestIdentity(bizDate, calendarCode, requested, safeReason));
    BatchDayCatchUpOperation existing = operationStore.find(tenantId, idempotencyKey, requestJson);
    if (EmptyChecks.isNotNull(existing)) {
      return completedResponse(existing);
    }
    ActiveCalendarRow calendar =
        businessCalendarMapper.selectActiveByTenantAndCalendarCode(tenantId, calendarCode);
    if (EmptyChecks.isNull(calendar)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.business_calendar.not_found");
    }
    String catchUpPolicy = calendar.catchUpPolicy();
    CatchUpPolicyType policyType = CatchUpPolicyType.fromCode(catchUpPolicy);
    if (policyType == CatchUpPolicyType.NONE) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.batch_day.catch_up_disabled");
    }
    List<String> jobCodes = EmptyChecks.isNull(requested)
        ? resolveJobCodes(tenantId, calendarCode, parsedBizDate, null)
        : requested;
    Map<String, Long> workflowTargets = new LinkedHashMap<>();
    for (String jobCode : jobCodes) {
      workflowTargets.put(jobCode, resolveManagedWorkflowId(tenantId, jobCode));
    }
    List<FrozenTarget> targets = new ArrayList<>();
    List<BatchDayCatchUpProgress.Item> pending = new ArrayList<>();
    for (String jobCode : jobCodes) {
      String itemIdempotencyKey = idempotencyKey + ":" + jobCode;
      if (policyType == CatchUpPolicyType.AUTO && itemIdempotencyKey.length() > 256) {
        itemIdempotencyKey = shortenItemKey(idempotencyKey, jobCode, "batchday-auto-");
      }
      ApprovalSubmitCommand approval = null;
      if (policyType != CatchUpPolicyType.AUTO) {
        ConsoleCatchUpApprovalRequest approvalRequest = new ConsoleCatchUpApprovalRequest();
        approvalRequest.setTenantId(tenantId);
        approvalRequest.setJobCode(jobCode);
        approvalRequest.setBizDate(bizDate);
        approvalRequest.setBatchDayCalendarCode(calendarCode);
        approvalRequest.setReason(safeReason);
        if (itemIdempotencyKey.length() > 128) {
          itemIdempotencyKey = shortenItemKey(idempotencyKey, jobCode, "batchday-");
        }
        String requester =
            ConsoleTextSanitizer.safeInput(requestMetadataResolver.current().operatorId(), 64);
        approval = ApprovalSubmitCommand.builder()
            .tenantId(tenantId)
            .approvalType(CATCH_UP)
            .actionType(CATCH_UP)
            .targetType(CATCH_UP)
            .payloadJson(JsonUtils.toJson(approvalRequest))
            .requesterId(requester)
            .approvalReason(safeReason)
            .idempotencyKey(itemIdempotencyKey)
            .build();
      }
      targets.add(
          new FrozenTarget(jobCode, itemIdempotencyKey, workflowTargets.get(jobCode), approval));
      pending.add(BatchDayCatchUpProgress.Item.pending(jobCode, itemIdempotencyKey));
    }
    FrozenBatch snapshot = new FrozenBatch(bizDate, calendarCode, catchUpPolicy, targets);
    BatchDayCatchUpOperation operation =
        operationStore.prepare(new BatchDayCatchUpOperationStore.Prepare(
            tenantId,
            idempotencyKey,
            requestJson,
            JsonUtils.toJson(snapshot),
            JsonUtils.toJson(pending)));
    if (!"PREPARED".equals(operation.operationStatus())) {
      return completedResponse(operation);
    }
    FrozenBatch frozen = JsonUtils.fromJsonStrict(operation.snapshotJson(), FrozenBatch.class);
    for (FrozenTarget target : frozen.targets()) {
      operation = operationStore.startItem(
          tenantId, operation.requestKeyHash(), operation.revision(), target.sourceKey());
      String referenceNo;
      try {
        referenceNo = submitFrozenTarget(tenantId, frozen, target, safeReason);
      } catch (RuntimeException failure) {
        operationStore.markItemUnknown(
            tenantId, operation.requestKeyHash(), operation.revision(), target.sourceKey());
        throw failure;
      }
      operation = operationStore.acceptItem(
          tenantId,
          operation.requestKeyHash(),
          operation.revision(),
          target.sourceKey(),
          referenceNo);
    }
    operation = operationStore.complete(tenantId, operation.requestKeyHash(), operation.revision());
    ops.publishRefresh(tenantId);
    return completedResponse(operation);
  }

  private List<String> sanitizedRequestedJobCodes(List<String> jobCodes) {
    if (EmptyChecks.isEmpty(jobCodes)) {
      return null;
    }
    return jobCodes.stream()
        .filter(ops::hasText)
        .map(code -> ConsoleTextSanitizer.safeInput(code, 128))
        .distinct()
        .toList();
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public ConsoleBatchDayCatchUpResponse continueBatchDay(String tenantId, String requestKey) {
    String resolvedTenant = ops.resolveTenant(tenantId);
    BatchDayCatchUpOperation operation =
        operationStore.findByRequestKey(resolvedTenant, requestKey);
    if (EmptyChecks.isNull(operation)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    if ("COMPLETE".equals(operation.operationStatus())) {
      return completedResponse(operation);
    }
    if (!"RUNNING".equals(operation.operationStatus())
        && !"PREPARED".equals(operation.operationStatus())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    List<BatchDayCatchUpProgress.Item> items = operationStore.matchingItems(operation);
    if (items.stream()
        .anyMatch(item -> item.status() == BatchDayCatchUpProgress.ItemStatus.UNKNOWN
            || item.status() == BatchDayCatchUpProgress.ItemStatus.IN_FLIGHT)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    FrozenBatch batch = JsonUtils.fromJsonStrict(operation.snapshotJson(), FrozenBatch.class);
    BatchRequestIdentity identity =
        JsonUtils.fromJsonStrict(operation.requestJson(), BatchRequestIdentity.class);
    if (EmptyChecks.isNull(batch)
        || EmptyChecks.isNull(identity)
        || EmptyChecks.isNull(batch.targets())
        || !Objects.equals(batch.bizDate(), identity.bizDate())
        || !Objects.equals(batch.calendarCode(), identity.calendarCode())
        || (!CatchUpPolicyType.AUTO.code().equals(batch.catchUpPolicy())
            && !CatchUpPolicyType.MANUAL_APPROVAL.code().equals(batch.catchUpPolicy()))) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    // 先校验所有待发项目，避免中途发现冻结信息损坏造成新一轮部分提交。
    for (int index = 0; index < items.size(); index++) {
      BatchDayCatchUpProgress.Item item = items.get(index);
      if (item.status() != BatchDayCatchUpProgress.ItemStatus.PENDING) continue;
      FrozenTarget target = batch.targets().get(index);
      if (EmptyChecks.isNull(target.workflowDefinitionId()) || target.workflowDefinitionId() <= 0) {
        throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
      }
      if (CatchUpPolicyType.MANUAL_APPROVAL.code().equals(batch.catchUpPolicy())) {
        ApprovalSubmitCommand command =
            BatchDayCatchUpReconciliation.frozenApproval(operation, target.sourceKey());
        JsonNode payload = JsonUtils.fromJsonStrict(command.payloadJson(), JsonNode.class);
        String payloadReason =
            payload.path("reason").isNull() || payload.path("reason").isMissingNode()
                ? null
                : payload.path("reason").asText();
        if (!Objects.equals(identity.reason(), command.approvalReason())
            || !Objects.equals(identity.reason(), payloadReason)) {
          throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
        }
      }
    }
    for (int index = 0; index < items.size(); index++) {
      if (items.get(index).status() != BatchDayCatchUpProgress.ItemStatus.PENDING) continue;
      FrozenTarget target = batch.targets().get(index);
      operation = operationStore.startItem(
          resolvedTenant, operation.requestKeyHash(), operation.revision(), target.sourceKey());
      String referenceNo;
      try {
        referenceNo = CatchUpPolicyType.AUTO.code().equals(batch.catchUpPolicy())
            ? submitFrozenTarget(resolvedTenant, batch, target, identity.reason())
            : approvalClient.submitApproval(target.approval());
      } catch (RuntimeException failure) {
        operationStore.markItemUnknown(
            resolvedTenant, operation.requestKeyHash(), operation.revision(), target.sourceKey());
        throw failure;
      }
      operation = operationStore.acceptItem(
          resolvedTenant,
          operation.requestKeyHash(),
          operation.revision(),
          target.sourceKey(),
          referenceNo);
    }
    operation =
        operationStore.complete(resolvedTenant, operation.requestKeyHash(), operation.revision());
    ops.publishRefresh(resolvedTenant);
    return completedResponse(operation);
  }

  private String submitFrozenTarget(
      String tenantId, FrozenBatch batch, FrozenTarget target, String reason) {
    if (CatchUpPolicyType.AUTO.code().equals(batch.catchUpPolicy())) {
      ConsoleLaunchCommand command = BatchDayCatchUpAutoCommand.builder()
          .tenantId(tenantId)
          .jobCode(target.jobCode())
          .workflowId(target.workflowDefinitionId())
          .bizDate(batch.bizDate())
          .calendarCode(batch.calendarCode())
          .reason(reason)
          .sourceKey(target.sourceKey())
          .build()
          .toLaunchCommand();
      return ops.delegateLaunch(command);
    }
    ConsoleCatchUpApprovalRequest approvalRequest = JsonUtils.fromJsonStrict(
        target.approval().payloadJson(), ConsoleCatchUpApprovalRequest.class);
    return approveCatchUp(approvalRequest, target.sourceKey());
  }

  private ConsoleBatchDayCatchUpResponse completedResponse(BatchDayCatchUpOperation operation) {
    if (!"COMPLETE".equals(operation.operationStatus())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    FrozenBatch batch = JsonUtils.fromJsonStrict(operation.snapshotJson(), FrozenBatch.class);
    BatchDayCatchUpProgress.Item[] items =
        JsonUtils.fromJsonStrict(operation.itemsJson(), BatchDayCatchUpProgress.Item[].class);
    boolean auto = CatchUpPolicyType.AUTO.code().equals(batch.catchUpPolicy());
    List<ConsoleBatchDayCatchUpItemResponse> result = new ArrayList<>();
    for (BatchDayCatchUpProgress.Item item : items) {
      result.add(new ConsoleBatchDayCatchUpItemResponse(
          item.jobCode(),
          auto ? "LAUNCHED" : "APPROVAL_CREATED",
          item.referenceNo(),
          TriggerType.CATCH_UP.code(),
          auto ? "LAUNCHED" : "PENDING"));
    }
    return new ConsoleBatchDayCatchUpResponse(
        operation.tenantId(), batch.calendarCode(), batch.bizDate(), batch.catchUpPolicy(), result);
  }

  @Override
  public BatchDayCatchUpRequestStatusResponse batchDayCatchUpStatus(
      String tenantId, String requestKey) {
    String resolvedTenant = ops.resolveTenant(tenantId);
    BatchDayCatchUpOperation operation =
        operationStore.findByRequestKey(resolvedTenant, requestKey);
    if (EmptyChecks.isNull(operation)) {
      return new BatchDayCatchUpRequestStatusResponse(
          false, resolvedTenant, null, null, null, null, List.of());
    }
    FrozenBatch batch = JsonUtils.fromJsonStrict(operation.snapshotJson(), FrozenBatch.class);
    List<BatchDayCatchUpRequestStatusResponse.Item> items =
        operationStore.matchingItems(operation).stream()
            .map(item -> new BatchDayCatchUpRequestStatusResponse.Item(
                item.jobCode(), item.status().name(), item.referenceNo()))
            .toList();
    return new BatchDayCatchUpRequestStatusResponse(
        true,
        resolvedTenant,
        batch.bizDate(),
        batch.calendarCode(),
        batch.catchUpPolicy(),
        operation.operationStatus(),
        items);
  }

  private record BatchRequestIdentity(
      String bizDate, String calendarCode, List<String> jobCodes, String reason) {}

  private record FrozenBatch(
      String bizDate, String calendarCode, String catchUpPolicy, List<FrozenTarget> targets) {}

  private record FrozenTarget(
      String jobCode,
      String sourceKey,
      Long workflowDefinitionId,
      ApprovalSubmitCommand approval) {}

  private String shortenItemKey(String idempotencyKey, String jobCode, String prefix) {
    // 长度前缀避免不同请求和作业组合产生相同输入。
    String source = idempotencyKey.length() + ":" + idempotencyKey + jobCode;
    try {
      MessageDigest digest = MessageDigest.getInstance(CryptoAlgorithms.SHA_256);
      byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
      return prefix + HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String approvePendingCatchUpRequest(
      ConsoleCatchUpApprovalRequest request, String idempotencyKey) {
    String tenantId = ops.resolveTenant(request.getTenantId());
    ConsoleRequestMetadata requestMetadata = requestMetadataResolver.current();
    // P0-1(2026-05-16):同 ConsoleJobOpsSupport.delegateLaunch — 走带 X-Internal-Secret 的 client
    RestClient restClient = triggerInternalRestClient.client();
    RestClient.RequestBodySpec requestBody = restClient
        .post()
        .uri("/api/triggers/catch-up/approve")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .header(CommonConstants.DEFAULT_REQUEST_ID_HEADER, requestMetadata.requestId())
        .header(CommonConstants.DEFAULT_TRACE_ID_HEADER, requestMetadata.traceId());
    String safeRequestId = ConsoleTextSanitizer.safeInput(request.getRequestId(), 128);
    String safeReason = ConsoleTextSanitizer.safeInput(request.getReason(), 512);
    CatchUpApprovalPayload payload =
        new CatchUpApprovalPayload(tenantId, safeRequestId, safeReason);
    CommonResponse<LaunchResponse> response = requestBody
        .body(payload)
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

  private Long resolveManagedWorkflowId(String tenantId, String jobCode) {
    JobDefinitionEntity job = jobDefinitionMapper.selectByUniqueKey(tenantId, jobCode);
    if (EmptyChecks.isNull(job)) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.job.definition_not_found");
    }
    if (Boolean.FALSE.equals(job.getEnabled())) {
      throw BizException.of(ResultCode.VALIDATION_ERROR, "error.job.definition_disabled");
    }
    Long workflowDefinitionId = job.getDefaultWorkflowDefinitionId();
    if (!managedJobWorkflowReference.isEnabledSingleJobWorkflow(
        tenantId, workflowDefinitionId, job.getId())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.single_job_link_missing");
    }
    return workflowDefinitionId;
  }

  private record CatchUpApprovalPayload(String tenantId, String requestId, String reason) {}
}
