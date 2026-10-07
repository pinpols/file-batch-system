package io.github.pinpols.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.PrivateTempFiles;
import io.github.pinpols.batch.worker.core.config.WorkerTempFileProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("过期临时文件清理: 清理范围与可续传文件的保留判定")
class StaleTempFileCleanupTest {

  @TempDir
  Path tempDir;

  private String originalTmpDir;

  @AfterEach
  void restoreTmpDir() {
    if (originalTmpDir != null) {
      System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, originalTmpDir);
    }
  }

  @Test
  @DisplayName("清理过期文件时, 仍被占用与可续传的文件必须保留, 孤立文件被删除")
  void shouldPreserveActiveAndResumableFiles_whenCleaningStaleFiles() throws Exception {
    originalTmpDir = System.getProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
    System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, tempDir.toString());
    var old = FileTime.from(BatchDateTimeSupport.utcNow().minus(Duration.ofHours(8)));
    String prefix = BatchFileConstants.ENCRYPTED_EXPORT_PREFIX;
    Path abandoned = PrivateTempFiles.createTempFile(prefix, ".bin");
    Path resumable = PrivateTempFiles.createTempFile("batch-export-resumable-", ".part");
    Files.setLastModifiedTime(abandoned, old);
    Files.setLastModifiedTime(resumable, old);
    try (var active = PrivateTempFiles.createLockedTempFile(prefix, ".bin")) {
      Files.setLastModifiedTime(active.path(), old);
      StaleTempFileCleanup cleanup = new StaleTempFileCleanup(workerTempFileProperties(6L));
      cleanup.cleanStaleTempFiles();
      assertThat(abandoned).doesNotExist();
      assertThat(resumable).exists();
      assertThat(active.path()).exists();
    }
  }

  @Test
  @DisplayName("只删除超过保留时长且带批处理前缀的临时文件, 未过期文件与无关文件不受影响")
  void shouldDeleteOnlyBatchPrefixedFilesOlderThanCutoff() throws Exception {
    originalTmpDir = System.getProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY);
    System.setProperty(PrivateTempFiles.TEMP_ROOT_PROPERTY, tempDir.toString());

    Path oldBatch = tempDir.resolve("batch-import-old.tmp");
    Files.writeString(oldBatch, "x");
    Files.setLastModifiedTime(
        oldBatch, FileTime.from(BatchDateTimeSupport.utcNow().minus(Duration.ofHours(7))));

    Path newBatch = tempDir.resolve("batch-export-new.tmp");
    Files.writeString(newBatch, "y");
    Files.setLastModifiedTime(
        newBatch, FileTime.from(BatchDateTimeSupport.utcNow().minus(Duration.ofHours(1))));

    Path oldOther = tempDir.resolve("not-batch-old.tmp");
    Files.writeString(oldOther, "z");
    Files.setLastModifiedTime(
        oldOther, FileTime.from(BatchDateTimeSupport.utcNow().minus(Duration.ofHours(10))));

    StaleTempFileCleanup cleanup = new StaleTempFileCleanup(workerTempFileProperties(6L));
    cleanup.cleanStaleTempFiles();

    assertThat(Files.exists(oldBatch)).isFalse();
    assertThat(Files.exists(newBatch)).isTrue();
    assertThat(Files.exists(oldOther)).isTrue();
  }

  private static WorkerTempFileProperties workerTempFileProperties(long staleTempFileHours) {
    WorkerTempFileProperties properties = new WorkerTempFileProperties();
    properties.setStaleTempFileHours(staleTempFileHours);
    return properties;
  }
}
