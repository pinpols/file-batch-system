package io.github.pinpols.batch.console.domain.job.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.security.CryptoAlgorithms;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.domain.job.mapper.BatchDayCatchUpOperationMapper;
import io.github.pinpols.batch.console.domain.job.param.BatchDayCatchUpOperationCreate;
import io.github.pinpols.batch.console.domain.job.param.BatchDayCatchUpOperationTransition;
import io.github.pinpols.batch.console.domain.job.view.BatchDayCatchUpOperation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原请求身份与首次冻结候选的持久化服务，不执行任何远端补跑。 */
@Service
@RequiredArgsConstructor
public class BatchDayCatchUpOperationStore {

  private static final String KEY_NAMESPACE = "batch-day-catch-up:";
  private static final String RUNNING = "RUNNING";
  private static final String CONFLICT_MESSAGE = "error.common.conflict";
  private static final String INVALID_MESSAGE = "error.common.invalid_argument";
  private final BatchDayCatchUpOperationMapper mapper;

  /** requestJson 必须由调用方以稳定字段顺序序列化规范化原请求。 */
  public record Prepare(
      String tenantId,
      String requestKey,
      String requestJson,
      String snapshotJson,
      String itemsJson) {}

  /** 同键同请求返回首次快照，同键不同请求拒绝；不会用重试时的新候选覆盖旧候选。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BatchDayCatchUpOperation prepare(Prepare input) {
    if (EmptyChecks.isNull(input)
        || !Texts.hasText(input.tenantId())
        || !Texts.hasText(input.requestKey())
        || !Texts.hasText(input.requestJson())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    mapper.setTenantContext(input.tenantId());
    String keyHash = hash(KEY_NAMESPACE + input.requestKey());
    String requestHash = hash(input.requestJson());
    BatchDayCatchUpOperation existing = mapper.select(input.tenantId(), keyHash);
    if (EmptyChecks.isNotNull(existing)) {
      return requireSameRequest(existing, requestHash);
    }
    if (!Texts.hasText(input.snapshotJson()) || !Texts.hasText(input.itemsJson())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    BatchDayCatchUpOperationCreate operation = BatchDayCatchUpOperationCreate.builder()
        .tenantId(input.tenantId())
        .requestKeyHash(keyHash)
        .requestHash(requestHash)
        .requestJson(input.requestJson())
        .snapshotJson(input.snapshotJson())
        .itemsJson(input.itemsJson())
        .build();
    mapper.insertIfAbsent(operation);
    BatchDayCatchUpOperation persisted = mapper.select(input.tenantId(), keyHash);
    if (EmptyChecks.isNull(persisted)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.common.system_error");
    }
    return requireSameRequest(persisted, requestHash);
  }

  /** 在计算当前候选前回查首次记录；未找到仅表示当前查询未看到记录。 */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public BatchDayCatchUpOperation find(String tenantId, String requestKey, String requestJson) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(requestKey) || !Texts.hasText(requestJson)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    mapper.setTenantContext(tenantId);
    BatchDayCatchUpOperation persisted = mapper.select(tenantId, hash(KEY_NAMESPACE + requestKey));
    return EmptyChecks.isNull(persisted) ? null : requireSameRequest(persisted, hash(requestJson));
  }

  /** 只读查询原请求键；未找到不能推断远端未受理。 */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  public BatchDayCatchUpOperation findByRequestKey(String tenantId, String requestKey) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(requestKey)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    mapper.setTenantContext(tenantId);
    return mapper.select(tenantId, hash(KEY_NAMESPACE + requestKey));
  }

  /** 仅当原键尚未冻结时设置持久围栏；与首次冻结竞争同一数据库唯一键。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BatchDayCatchUpOperation abandonUnprepared(
      String tenantId, String requestKey, String bizDate, String calendarCode) {
    if (!Texts.hasText(tenantId)
        || !Texts.hasText(requestKey)
        || !Texts.hasText(bizDate)
        || !Texts.hasText(calendarCode)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    mapper.setTenantContext(tenantId);
    String keyHash = hash(KEY_NAMESPACE + requestKey);
    String requestJson = JsonUtils.toJson(Map.of("bizDate", bizDate, "calendarCode", calendarCode));
    String snapshotJson = JsonUtils.toJson(
        Map.of("bizDate", bizDate, "calendarCode", calendarCode, "targets", List.of()));
    BatchDayCatchUpOperationCreate operation = BatchDayCatchUpOperationCreate.builder()
        .tenantId(tenantId)
        .requestKeyHash(keyHash)
        .requestHash(hash("abandoned:" + requestKey))
        .requestJson(requestJson)
        .snapshotJson(snapshotJson)
        .itemsJson("[]")
        .build();
    mapper.insertAbandonedIfAbsent(operation);
    BatchDayCatchUpOperation persisted = mapper.select(tenantId, keyHash);
    if (EmptyChecks.isNull(persisted)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.common.system_error");
    }
    return persisted;
  }

  /** 对账内部读取冻结记录；调用方须先完成租户授权。 */
  @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
  BatchDayCatchUpOperation findByHash(String tenantId, String requestKeyHash) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(requestKeyHash)) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    mapper.setTenantContext(tenantId);
    return mapper.select(tenantId, requestKeyHash);
  }

  /** 只接收已与冻结载荷核验的回执；对账网络调用必须在本事务之外完成。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  BatchDayCatchUpOperation resolveUnknown(
      String tenantId, String requestKeyHash, long revision, String sourceKey, String referenceNo) {
    if (!Texts.hasText(tenantId)
        || !Texts.hasText(requestKeyHash)
        || !Texts.hasText(sourceKey)
        || !Texts.hasText(referenceNo)
        || revision < 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    mapper.setTenantContext(tenantId);
    BatchDayCatchUpOperation persisted = mapper.select(tenantId, requestKeyHash);
    if (EmptyChecks.isNull(persisted)
        || persisted.revision() != revision
        || !"UNKNOWN".equals(persisted.operationStatus())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    List<BatchDayCatchUpProgress.Item> items = matchingItems(persisted);
    boolean isUnknown = items.stream()
        .anyMatch(item -> sourceKey.equals(item.sourceKey())
            && item.status() == BatchDayCatchUpProgress.ItemStatus.UNKNOWN);
    if (!isUnknown) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    List<BatchDayCatchUpProgress.Item> resolved =
        BatchDayCatchUpProgress.accept(items, sourceKey, referenceNo);
    BatchDayCatchUpOperationTransition transition = new BatchDayCatchUpOperationTransition(
        tenantId, requestKeyHash, revision, "UNKNOWN", RUNNING, JsonUtils.toJson(resolved));
    if (mapper.resolveUnknown(transition) != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    BatchDayCatchUpOperation result = mapper.select(tenantId, requestKeyHash);
    if (EmptyChecks.isNull(result)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.common.system_error");
    }
    return result;
  }

  /** 调用远端前必须先提交该状态。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BatchDayCatchUpOperation startItem(
      String tenantId, String requestKeyHash, long revision, String sourceKey) {
    return updateItem(tenantId, requestKeyHash, revision, sourceKey, ItemAction.START, null);
  }

  /** 仅用于当前调用或独立回查明确核验的回执，不用于猜测结果。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BatchDayCatchUpOperation acceptItem(
      String tenantId, String requestKeyHash, long revision, String sourceKey, String referenceNo) {
    return updateItem(
        tenantId, requestKeyHash, revision, sourceKey, ItemAction.ACCEPT, referenceNo);
  }

  /** 远端结果无法确定时冻结该批次，等待原请求对账。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BatchDayCatchUpOperation markItemUnknown(
      String tenantId, String requestKeyHash, long revision, String sourceKey) {
    return updateItem(tenantId, requestKeyHash, revision, sourceKey, ItemAction.UNKNOWN, null);
  }

  private enum ItemAction {
    START,
    ACCEPT,
    UNKNOWN
  }

  private BatchDayCatchUpOperation updateItem(
      String tenantId,
      String requestKeyHash,
      long revision,
      String sourceKey,
      ItemAction action,
      String referenceNo) {
    if (!Texts.hasText(tenantId)
        || !Texts.hasText(requestKeyHash)
        || !Texts.hasText(sourceKey)
        || revision < 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    mapper.setTenantContext(tenantId);
    BatchDayCatchUpOperation persisted = mapper.select(tenantId, requestKeyHash);
    if (EmptyChecks.isNull(persisted) || persisted.revision() != revision) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    String status = persisted.operationStatus();
    boolean allowedStatus = action == ItemAction.START
        ? "PREPARED".equals(status) || RUNNING.equals(status)
        : RUNNING.equals(status);
    if (!allowedStatus) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    List<BatchDayCatchUpProgress.Item> items = matchingItems(persisted);
    List<BatchDayCatchUpProgress.Item> updated;
    try {
      updated = switch (action) {
        case START -> BatchDayCatchUpProgress.claim(items, sourceKey);
        case UNKNOWN -> BatchDayCatchUpProgress.markUnknown(items, sourceKey);
        case ACCEPT -> {
          boolean isInFlight = items.stream()
              .anyMatch(item -> sourceKey.equals(item.sourceKey())
                  && item.status() == BatchDayCatchUpProgress.ItemStatus.IN_FLIGHT);
          if (!isInFlight) {
            throw new IllegalStateException("catch-up item is not in flight");
          }
          yield BatchDayCatchUpProgress.accept(items, sourceKey, referenceNo);
        }
      };
    } catch (IllegalArgumentException | IllegalStateException invalidTransition) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE, invalidTransition);
    }
    String nextStatus = action == ItemAction.UNKNOWN ? "UNKNOWN" : RUNNING;
    BatchDayCatchUpOperationTransition transition = new BatchDayCatchUpOperationTransition(
        tenantId, requestKeyHash, revision, status, nextStatus, JsonUtils.toJson(updated));
    if (mapper.transition(transition) != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    BatchDayCatchUpOperation result = mapper.select(tenantId, requestKeyHash);
    if (EmptyChecks.isNull(result)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.common.system_error");
    }
    return result;
  }

  /** 只根据数据库内的冻结身份和已确认结果完成批次；回执真实性须由调用方先核验。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BatchDayCatchUpOperation complete(String tenantId, String requestKeyHash, long revision) {
    if (!Texts.hasText(tenantId) || !Texts.hasText(requestKeyHash) || revision < 0) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, INVALID_MESSAGE);
    }
    mapper.setTenantContext(tenantId);
    BatchDayCatchUpOperation persisted = mapper.select(tenantId, requestKeyHash);
    if (EmptyChecks.isNull(persisted)
        || persisted.revision() != revision
        || !hasConfirmedTargets(persisted)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    if ("COMPLETE".equals(persisted.operationStatus())) {
      return persisted;
    }
    BatchDayCatchUpOperationTransition transition = new BatchDayCatchUpOperationTransition(
        tenantId,
        requestKeyHash,
        revision,
        persisted.operationStatus(),
        "COMPLETE",
        persisted.itemsJson());
    if (mapper.transition(transition) != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    BatchDayCatchUpOperation completed = mapper.select(tenantId, requestKeyHash);
    if (EmptyChecks.isNull(completed)) {
      throw BizException.of(ResultCode.SYSTEM_ERROR, "error.common.system_error");
    }
    return completed;
  }

  private boolean hasConfirmedTargets(BatchDayCatchUpOperation operation) {
    return BatchDayCatchUpProgress.isComplete(matchingItems(operation));
  }

  List<BatchDayCatchUpProgress.Item> matchingItems(BatchDayCatchUpOperation operation) {
    try {
      BatchDayCatchUpProgress.Item[] parsed =
          JsonUtils.fromJsonStrict(operation.itemsJson(), BatchDayCatchUpProgress.Item[].class);
      if (EmptyChecks.isNull(parsed)) {
        throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
      }
      List<BatchDayCatchUpProgress.Item> items = List.of(parsed);
      BatchDayCatchUpProgress.isComplete(items);
      JsonNode snapshot = JsonUtils.fromJsonStrict(operation.snapshotJson(), JsonNode.class);
      JsonNode targets = EmptyChecks.isNull(snapshot) ? null : snapshot.get("targets");
      if (EmptyChecks.isNull(targets) || !targets.isArray() || targets.size() != items.size()) {
        throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
      }
      for (int index = 0; index < items.size(); index++) {
        JsonNode target = targets.get(index);
        BatchDayCatchUpProgress.Item item = items.get(index);
        if (!target.isObject()
            || !target.path("jobCode").isTextual()
            || !target.path("sourceKey").isTextual()
            || !item.jobCode().equals(target.path("jobCode").asText())
            || !item.sourceKey().equals(target.path("sourceKey").asText())) {
          throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
        }
      }
      return items;
    } catch (BizException expected) {
      throw expected;
    } catch (RuntimeException invalidStoredState) {
      throw BizException.of(
          ResultCode.SYSTEM_ERROR, "error.common.system_error", invalidStoredState);
    }
  }

  private BatchDayCatchUpOperation requireSameRequest(
      BatchDayCatchUpOperation persisted, String requestHash) {
    if ("ABANDONED".equals(persisted.operationStatus())
        || !requestHash.equals(persisted.requestHash())) {
      throw BizException.of(ResultCode.STATE_CONFLICT, CONFLICT_MESSAGE);
    }
    return persisted;
  }

  private String hash(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance(CryptoAlgorithms.SHA_256);
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
