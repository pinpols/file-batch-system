package io.github.pinpols.batch.console.domain.job.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.job.view.BatchDayCatchUpOperation;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSourceRecord;
import io.github.pinpols.batch.console.shared.approval.OrchestratorApprovalClient.ApprovalSubmitCommand;
import io.github.pinpols.batch.console.shared.client.TriggerInternalRestClient;
import io.github.pinpols.batch.console.shared.command.ConsoleLaunchCommand;
import java.time.LocalDate;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原请求未知结果的审批对账；查不到不能证明未提交，也不发起新提交。 */
@Service
@RequiredArgsConstructor
public class BatchDayCatchUpReconciliation {

  private static final String BIZ_DATE_FIELD = "bizDate";

  private final BatchDayCatchUpOperationStore store;
  private final OrchestratorApprovalClient approvals;
  private final TriggerInternalRestClient trigger;

  /** 根据原请求和作业核验未确定回执，不信任客户端提供的来源键。 */
  @Transactional(propagation = Propagation.NEVER)
  public BatchDayCatchUpOperation reconcileOriginal(
      String tenantId, String requestKey, String jobCode) {
    if (!Texts.hasText(jobCode)) {
      throw conflict();
    }
    BatchDayCatchUpOperation operation = store.findByRequestKey(tenantId, requestKey);
    if (EmptyChecks.isNull(operation)
        || (!"UNKNOWN".equals(operation.operationStatus())
            && !"RUNNING".equals(operation.operationStatus()))) {
      throw conflict();
    }
    BatchDayCatchUpProgress.ItemStatus unresolvedStatus =
        "UNKNOWN".equals(operation.operationStatus())
            ? BatchDayCatchUpProgress.ItemStatus.UNKNOWN
            : BatchDayCatchUpProgress.ItemStatus.IN_FLIGHT;
    JsonNode snapshot = JsonUtils.fromJsonStrict(operation.snapshotJson(), JsonNode.class);
    if (EmptyChecks.isNull(snapshot) || !snapshot.path("catchUpPolicy").isTextual()) {
      throw conflict();
    }
    String policy = snapshot.path("catchUpPolicy").asText();
    if (!"MANUAL_APPROVAL".equals(policy) && !"AUTO".equals(policy)) throw conflict();
    BatchDayCatchUpProgress.Item item = store.matchingItems(operation).stream()
        .filter(candidate ->
            jobCode.equals(candidate.jobCode()) && candidate.status() == unresolvedStatus)
        .findFirst()
        .orElseThrow(BatchDayCatchUpReconciliation::conflict);
    String receipt = "AUTO".equals(policy)
        ? verifiedAutoReceipt(operation, item)
        : verifiedApprovalReceipt(operation, item.sourceKey());
    BatchDayCatchUpOperation resolved = unresolvedStatus
            == BatchDayCatchUpProgress.ItemStatus.UNKNOWN
        ? store.resolveUnknown(
            tenantId, operation.requestKeyHash(), operation.revision(), item.sourceKey(), receipt)
        : store.acceptItem(
            tenantId, operation.requestKeyHash(), operation.revision(), item.sourceKey(), receipt);
    if (BatchDayCatchUpProgress.isComplete(store.matchingItems(resolved))) {
      return store.complete(tenantId, resolved.requestKeyHash(), resolved.revision());
    }
    return resolved;
  }

  private String verifiedAutoReceipt(
      BatchDayCatchUpOperation operation, BatchDayCatchUpProgress.Item item) {
    ConsoleLaunchCommand command = frozenAutoCommand(operation, item);
    TriggerVerificationPayload payload = new TriggerVerificationPayload(
        command.tenantId(),
        command.workflowDefinitionId(),
        LocalDate.parse(command.bizDate()),
        command.triggerType(),
        command.params());
    CommonResponse<LaunchResponse> response = trigger
        .client()
        .post()
        .uri("/internal/trigger/catch-up/verify")
        .header("Idempotency-Key", command.idempotencyKey())
        .body(payload)
        .retrieve()
        .body(new ParameterizedTypeReference<>() {});
    if (EmptyChecks.isNull(response)
        || response.code() != ResultCode.SUCCESS
        || EmptyChecks.isNull(response.data())
        || !Texts.hasText(response.data().instanceNo())) throw conflict();
    return response.data().instanceNo();
  }

  private ConsoleLaunchCommand frozenAutoCommand(
      BatchDayCatchUpOperation operation, BatchDayCatchUpProgress.Item item) {
    try {
      JsonNode snapshot = JsonUtils.fromJsonStrict(operation.snapshotJson(), JsonNode.class);
      JsonNode identity = JsonUtils.fromJsonStrict(operation.requestJson(), JsonNode.class);
      if (EmptyChecks.isNull(snapshot)
          || EmptyChecks.isNull(identity)
          || !snapshot.path("targets").isArray()
          || !snapshot.path(BIZ_DATE_FIELD).isTextual()
          || !snapshot.path("calendarCode").isTextual()
          || !snapshot.path(BIZ_DATE_FIELD).equals(identity.path(BIZ_DATE_FIELD))
          || !snapshot.path("calendarCode").equals(identity.path("calendarCode"))) throw conflict();
      JsonNode reasonNode = identity.path("reason");
      if (!reasonNode.isMissingNode() && !reasonNode.isNull() && !reasonNode.isTextual())
        throw conflict();
      String reason =
          reasonNode.isMissingNode() || reasonNode.isNull() ? null : reasonNode.asText();
      JsonNode match = null;
      for (JsonNode target : snapshot.path("targets")) {
        if (item.sourceKey().equals(target.path("sourceKey").asText())) {
          if (EmptyChecks.isNotNull(match)) throw conflict();
          match = target;
        }
      }
      if (EmptyChecks.isNull(match)
          || !item.jobCode().equals(match.path("jobCode").asText())
          || !match.path("workflowDefinitionId").canConvertToLong()
          || match.path("workflowDefinitionId").asLong() <= 0) throw conflict();
      String bizDate = snapshot.path(BIZ_DATE_FIELD).asText();
      LocalDate.parse(bizDate);
      return BatchDayCatchUpAutoCommand.builder()
          .tenantId(operation.tenantId())
          .jobCode(item.jobCode())
          .workflowId(match.path("workflowDefinitionId").asLong())
          .bizDate(bizDate)
          .calendarCode(snapshot.path("calendarCode").asText())
          .reason(reason)
          .sourceKey(item.sourceKey())
          .build()
          .toLaunchCommand();
    } catch (BizException expected) {
      throw expected;
    } catch (RuntimeException invalidSnapshot) {
      throw conflict();
    }
  }

  private record TriggerVerificationPayload(
      String tenantId,
      Long workflowDefinitionId,
      LocalDate bizDate,
      TriggerType triggerType,
      Map<String, Object> params) {}

  /** 外部请求不可放在数据库事务中；状态更新通过独立短事务和版本围栏完成。 */
  @Transactional(propagation = Propagation.NEVER)
  public BatchDayCatchUpOperation reconcile(
      String tenantId, String requestKeyHash, long revision, String sourceKey) {
    BatchDayCatchUpOperation operation = store.findByHash(tenantId, requestKeyHash);
    if (EmptyChecks.isNull(operation)
        || operation.revision() != revision
        || !"UNKNOWN".equals(operation.operationStatus())
        || !Texts.hasText(sourceKey)) {
      throw conflict();
    }
    String receipt = verifiedApprovalReceipt(operation, sourceKey);
    return store.resolveUnknown(tenantId, requestKeyHash, revision, sourceKey, receipt);
  }

  private String verifiedApprovalReceipt(BatchDayCatchUpOperation operation, String sourceKey) {
    ApprovalSubmitCommand expected = frozenApproval(operation, sourceKey);
    ApprovalSourceRecord record = approvals.lookupVerifiedSubmission(expected);
    if (EmptyChecks.isNull(record)
        || !operation.tenantId().equals(record.tenantId())
        || !sourceKey.equals(record.sourceIdempotencyKey())
        || !Texts.hasText(record.approvalNo())) {
      throw conflict();
    }
    return record.approvalNo();
  }

  static ApprovalSubmitCommand frozenApproval(
      BatchDayCatchUpOperation operation, String sourceKey) {
    try {
      JsonNode snapshot = JsonUtils.fromJsonStrict(operation.snapshotJson(), JsonNode.class);
      JsonNode targets = snapshot.path("targets");
      if (!targets.isArray()
          || !snapshot.path(BIZ_DATE_FIELD).isTextual()
          || !snapshot.path("calendarCode").isTextual()) {
        throw conflict();
      }
      JsonNode match = null;
      for (JsonNode target : targets) {
        if (sourceKey.equals(target.path("sourceKey").asText())) {
          if (EmptyChecks.isNotNull(match)) {
            throw conflict();
          }
          match = target;
        }
      }
      if (EmptyChecks.isNull(match)
          || !match.path("jobCode").isTextual()
          || !match.path("approval").isObject()) {
        throw conflict();
      }
      ApprovalSubmitCommand command =
          JsonUtils.fromJsonStrict(match.path("approval").toString(), ApprovalSubmitCommand.class);
      JsonNode payload = JsonUtils.fromJsonStrict(command.payloadJson(), JsonNode.class);
      if (!operation.tenantId().equals(command.tenantId())
          || !sourceKey.equals(command.idempotencyKey())
          || !"CATCH_UP".equals(command.approvalType())
          || !"CATCH_UP".equals(command.actionType())
          || !"CATCH_UP".equals(command.targetType())
          || EmptyChecks.isNotNull(command.targetId())
          || !Texts.hasText(command.requesterId())
          || EmptyChecks.isNull(payload)
          || !payload.isObject()
          || !operation.tenantId().equals(payload.path("tenantId").asText())
          || !match.path("jobCode").asText().equals(payload.path("jobCode").asText())
          || !snapshot
              .path(BIZ_DATE_FIELD)
              .asText()
              .equals(payload.path(BIZ_DATE_FIELD).asText())
          || !snapshot
              .path("calendarCode")
              .asText()
              .equals(payload.path("batchDayCalendarCode").asText())) {
        throw conflict();
      }
      return command;
    } catch (BizException expected) {
      throw expected;
    } catch (RuntimeException invalidSnapshot) {
      throw conflict();
    }
  }

  private static BizException conflict() {
    return BizException.of(ResultCode.STATE_CONFLICT, "error.common.conflict");
  }
}
