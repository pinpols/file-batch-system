package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.job.view.BatchDayCatchUpOperation;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 服务单测的批次状态替身；实际事务与租户隔离由 PostgreSQL 集成测试验证。 */
final class InMemoryBatchDayCatchUpStore extends BatchDayCatchUpOperationStore {
  private final Map<String, BatchDayCatchUpOperation> rows = new HashMap<>();

  InMemoryBatchDayCatchUpStore() {
    super(null);
  }

  @Override
  public BatchDayCatchUpOperation find(String tenant, String key, String requestJson) {
    BatchDayCatchUpOperation row = rows.get(tenant + ":" + key);
    if (row != null && !row.requestJson().equals(requestJson)) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.conflict");
    }
    return row;
  }

  @Override
  public BatchDayCatchUpOperation prepare(Prepare input) {
    BatchDayCatchUpOperation existing =
        find(input.tenantId(), input.requestKey(), input.requestJson());
    if (existing != null) {
      return existing;
    }
    var row = new BatchDayCatchUpOperation(
        input.tenantId(),
        input.requestKey(),
        "hash",
        input.requestJson(),
        input.snapshotJson(),
        input.itemsJson(),
        "PREPARED",
        0);
    rows.put(input.tenantId() + ":" + input.requestKey(), row);
    return row;
  }

  @Override
  public BatchDayCatchUpOperation startItem(
      String tenant, String key, long revision, String source) {
    var row = require(tenant, key, revision);
    return update(row, "RUNNING", BatchDayCatchUpProgress.claim(items(row), source));
  }

  @Override
  public BatchDayCatchUpOperation acceptItem(
      String tenant, String key, long revision, String source, String reference) {
    var row = require(tenant, key, revision);
    return update(row, "RUNNING", BatchDayCatchUpProgress.accept(items(row), source, reference));
  }

  @Override
  public BatchDayCatchUpOperation markItemUnknown(
      String tenant, String key, long revision, String source) {
    var row = require(tenant, key, revision);
    return update(row, "UNKNOWN", BatchDayCatchUpProgress.markUnknown(items(row), source));
  }

  @Override
  public BatchDayCatchUpOperation complete(String tenant, String key, long revision) {
    var row = require(tenant, key, revision);
    if (!BatchDayCatchUpProgress.isComplete(items(row))) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.conflict");
    }
    return update(row, "COMPLETE", items(row));
  }

  private BatchDayCatchUpOperation require(String tenant, String key, long revision) {
    BatchDayCatchUpOperation row = rows.get(tenant + ":" + key);
    if (row == null || row.revision() != revision) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.common.conflict");
    }
    return row;
  }

  private List<BatchDayCatchUpProgress.Item> items(BatchDayCatchUpOperation row) {
    return Arrays.asList(
        JsonUtils.fromJsonStrict(row.itemsJson(), BatchDayCatchUpProgress.Item[].class));
  }

  private BatchDayCatchUpOperation update(
      BatchDayCatchUpOperation row, String status, List<BatchDayCatchUpProgress.Item> items) {
    var next = new BatchDayCatchUpOperation(
        row.tenantId(),
        row.requestKeyHash(),
        row.requestHash(),
        row.requestJson(),
        row.snapshotJson(),
        JsonUtils.toJson(items),
        status,
        row.revision() + 1);
    rows.put(row.tenantId() + ":" + row.requestKeyHash(), next);
    return next;
  }
}
