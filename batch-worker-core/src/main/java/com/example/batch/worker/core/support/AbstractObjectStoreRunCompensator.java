package com.example.batch.worker.core.support;

import com.example.batch.common.logging.SwallowedExceptionLogger;
import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.ObjectNotFoundException;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 安全增量补偿(opt-in)对象类反向动作复用基类(EXPORT / DISPATCH 共用)。
 *
 * <p>反向 = <b>删本 run 自己上传的对象</b>:从本 run 的 {@code file_record}(按 tenant_id + fileId 精确定位,scoped 到本
 * run)读出 {@code storage_bucket} / {@code storage_path},经 {@link BatchObjectStore#delete} 删除。
 *
 * <ul>
 *   <li><b>scoped 到本 run 的 file_record</b>:fileId 来自当前 pipeline 实例的 attributes,绝不跨 run / 跨租户。
 *   <li><b>幂等</b>:对象已不存在({@link ObjectNotFoundException})视为已反向,记 0/“already absent”而非报错。
 *   <li>无 fileId / 无 storage_path → 无对象可删 → SKIPPED(本 run 未产出对象)。
 *   <li>best-effort:删除异常自行吞咽并转 {@link CompensationResult#failed},<b>不上抛</b>——不掩盖原始失败。
 * </ul>
 */
@Slf4j
public abstract class AbstractObjectStoreRunCompensator implements PipelineCompensator {

  private final PlatformFileRuntimeRepository runtimeRepository;
  private final ObjectProvider<BatchObjectStore> objectStoreProvider;

  protected AbstractObjectStoreRunCompensator(
      PlatformFileRuntimeRepository runtimeRepository,
      ObjectProvider<BatchObjectStore> objectStoreProvider) {
    this.runtimeRepository = runtimeRepository;
    this.objectStoreProvider = objectStoreProvider;
  }

  @Override
  public CompensationResult compensate(
      String tenantId, Long pipelineInstanceId, Long fileId, Map<String, Object> attributes) {
    try {
      BatchObjectStore objectStore = objectStoreProvider.getIfAvailable();
      if (objectStore == null) {
        return CompensationResult.skipped(
            "no BatchObjectStore bean available — cannot reverse object, SKIPPED");
      }
      if (fileId == null) {
        return CompensationResult.skipped(
            "no fileId on this run — no object produced, nothing to reverse");
      }
      Map<String, Object> fileRecord = resolveFileRecord(tenantId, fileId, attributes);
      String storagePath = stringValue(fileRecord.get("storage_path"));
      String storageBucket = stringValue(fileRecord.get("storage_bucket"));
      if (!Texts.hasText(storagePath)) {
        return CompensationResult.skipped(
            "file_record(fileId=" + fileId + ") has no storage_path — nothing to reverse");
      }
      try {
        objectStore.delete(storageBucket, storagePath);
      } catch (ObjectNotFoundException notFound) {
        // 幂等:对象已不存在 = 已反向。
        SwallowedExceptionLogger.info(getClass(), "catch:ObjectNotFoundException", notFound);
        return CompensationResult.reversed(
            0L,
            "object already absent (idempotent): bucket=" + storageBucket + ", key=" + storagePath);
      }
      log.info(
          "{} compensation deleted run object: tenantId={}, pipelineInstanceId={}, fileId={},"
              + " bucket={}, key={}",
          pipelineType(),
          tenantId,
          pipelineInstanceId,
          fileId,
          storageBucket,
          storagePath);
      return CompensationResult.reversed(
          1L, "deleted run object: bucket=" + storageBucket + ", key=" + storagePath);
    } catch (RuntimeException ex) {
      // best-effort：不掩盖原始失败。
      SwallowedExceptionLogger.warn(getClass(), "catch:RuntimeException", ex);
      return CompensationResult.failed(
          pipelineType() + " object reverse-delete failed: " + ex.getMessage());
    }
  }

  private Map<String, Object> resolveFileRecord(
      String tenantId, Long fileId, Map<String, Object> attributes) {
    Object cached = attributes.get(PipelineRuntimeKeys.FILE_RECORD);
    if (cached instanceof Map<?, ?> map && map.get("storage_path") != null) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typed = (Map<String, Object>) map;
      return typed;
    }
    return runtimeRepository.loadFileRecord(tenantId, fileId);
  }

  private static String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
