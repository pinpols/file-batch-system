package com.example.batch.worker.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class StaleTempFileCleanupTest {

  @TempDir Path tempDir;

  private String originalTmpDir;

  @AfterEach
  void restoreTmpDir() {
    if (originalTmpDir != null) {
      System.setProperty("java.io.tmpdir", originalTmpDir);
    }
  }

  @Test
  void shouldDeleteOnlyBatchPrefixedFilesOlderThanCutoff() throws Exception {
    originalTmpDir = System.getProperty("java.io.tmpdir");
    System.setProperty("java.io.tmpdir", tempDir.toString());

    Path oldBatch = tempDir.resolve("batch-import-old.tmp");
    Files.writeString(oldBatch, "x");
    Files.setLastModifiedTime(oldBatch, FileTime.from(Instant.now().minus(Duration.ofHours(7))));

    Path newBatch = tempDir.resolve("batch-export-new.tmp");
    Files.writeString(newBatch, "y");
    Files.setLastModifiedTime(newBatch, FileTime.from(Instant.now().minus(Duration.ofHours(1))));

    Path oldOther = tempDir.resolve("not-batch-old.tmp");
    Files.writeString(oldOther, "z");
    Files.setLastModifiedTime(oldOther, FileTime.from(Instant.now().minus(Duration.ofHours(10))));

    StaleTempFileCleanup cleanup = new StaleTempFileCleanup();
    ReflectionTestUtils.setField(cleanup, "staleTempFileHours", 6L);
    cleanup.cleanStaleTempFiles();

    assertThat(Files.exists(oldBatch)).isFalse();
    assertThat(Files.exists(newBatch)).isTrue();
    assertThat(Files.exists(oldOther)).isTrue();
  }
}
