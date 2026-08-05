package io.github.pinpols.batch.worker.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.BatchObjectStore;
import io.github.pinpols.batch.worker.imports.domain.ImportBadRecordEntity;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportErrorOutputStorageTest {

  @Test
  void writesBadRecordsToPrivateSpoolBeforeUploading() {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket("bucket");
    BatchObjectStore objectStore = mock(BatchObjectStore.class);
    ImportErrorOutputStorage storage = new ImportErrorOutputStorage(properties, objectStore);
    ImportBadRecordEntity badRecord = new ImportBadRecordEntity(
        1L, "PARSE", "E1", "invalid", Map.of("id", 1), false, null, Instant.now(), null, null);

    String objectKey = storage.writeErrorOutput("tenant-A", "42", List.of(badRecord));

    assertThat(objectKey).contains("tenant-A", "42").endsWith("42.error.jsonl");
    verify(objectStore)
        .put(
            eq("bucket"),
            eq(objectKey),
            any(InputStream.class),
            anyLong(),
            eq("application/x-ndjson"));
  }
}
