package com.example.batch.worker.exports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.storage.BatchObjectStore;
import com.example.batch.common.storage.ObjectNotFoundException;
import com.example.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import com.example.batch.worker.core.infrastructure.PlatformFileRuntimeRepository;
import com.example.batch.worker.core.support.CompensationResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("安全增量补偿 EXPORT/DISPATCH：删本 run 自己的对象，幂等，scoped 到 file_record")
class ExportObjectStoreCompensatorTest {

  @Mock private PlatformFileRuntimeRepository runtimeRepository;
  @Mock private BatchObjectStore objectStore;
  @Mock private ObjectProvider<BatchObjectStore> objectStoreProvider;

  private ExportObjectStoreCompensator compensator() {
    return new ExportObjectStoreCompensator(runtimeRepository, objectStoreProvider);
  }

  @Test
  @DisplayName("删本 run file_record 的对象（attributes 已缓存 fileRecord）")
  void deletesRunObjectFromCachedFileRecord() {
    when(objectStoreProvider.getIfAvailable()).thenReturn(objectStore);
    Map<String, Object> fileRecord = new LinkedHashMap<>();
    fileRecord.put("storage_bucket", "export-bucket");
    fileRecord.put("storage_path", "tenant-a/export/run-7.csv");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);

    CompensationResult result = compensator().compensate("tenant-a", 1L, 7L, attributes);

    assertThat(result.outcome()).isEqualTo(CompensationResult.Outcome.REVERSED);
    assertThat(result.reversedCount()).isEqualTo(1L);
    verify(objectStore).delete("export-bucket", "tenant-a/export/run-7.csv");
  }

  @Test
  @DisplayName("对象已不存在：幂等视为已反向（删 0）")
  void idempotentWhenObjectAbsent() {
    when(objectStoreProvider.getIfAvailable()).thenReturn(objectStore);
    Map<String, Object> fileRecord = new LinkedHashMap<>();
    fileRecord.put("storage_bucket", "b");
    fileRecord.put("storage_path", "k");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
    doThrow(new ObjectNotFoundException("object b/k not found")).when(objectStore).delete("b", "k");

    CompensationResult result = compensator().compensate("tenant-a", 1L, 7L, attributes);

    assertThat(result.outcome()).isEqualTo(CompensationResult.Outcome.REVERSED);
    assertThat(result.reversedCount()).isZero();
  }

  @Test
  @DisplayName("无 fileId：本 run 未产出对象，SKIP")
  void skipsWhenNoFileId() {
    when(objectStoreProvider.getIfAvailable()).thenReturn(objectStore);

    CompensationResult result =
        compensator().compensate("tenant-a", 1L, null, new LinkedHashMap<>());

    assertThat(result.outcome()).isEqualTo(CompensationResult.Outcome.SKIPPED);
  }

  @Test
  @DisplayName("无对象存储 bean：SKIP，不误删")
  void skipsWhenNoObjectStore() {
    when(objectStoreProvider.getIfAvailable()).thenReturn(null);

    CompensationResult result = compensator().compensate("tenant-a", 1L, 7L, new LinkedHashMap<>());

    assertThat(result.outcome()).isEqualTo(CompensationResult.Outcome.SKIPPED);
  }

  @Test
  @DisplayName("删除抛运行时异常：best-effort，转 FAILED，不上抛")
  void failedWhenDeleteThrows() {
    when(objectStoreProvider.getIfAvailable()).thenReturn(objectStore);
    Map<String, Object> fileRecord = new LinkedHashMap<>();
    fileRecord.put("storage_bucket", "b");
    fileRecord.put("storage_path", "k");
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put(PipelineRuntimeKeys.FILE_RECORD, fileRecord);
    doThrow(new IllegalStateException("io error")).when(objectStore).delete("b", "k");

    CompensationResult result = compensator().compensate("tenant-a", 1L, 7L, attributes);

    assertThat(result.outcome()).isEqualTo(CompensationResult.Outcome.FAILED);
  }
}
