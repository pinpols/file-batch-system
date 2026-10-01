package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import org.junit.jupiter.api.Test;

class ConsoleAiAttachmentStorageGuardTest {

  @Test
  void acceptsDedicatedAttachmentBucket() {
    ConsoleAiProperties aiProperties = new ConsoleAiProperties();
    S3StorageProperties storageProperties = storage("batch-files");

    assertThatCode(
            () -> new ConsoleAiAttachmentStorageGuard(aiProperties, storageProperties).validate())
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsSharedBatchFileBucket() {
    ConsoleAiProperties aiProperties = new ConsoleAiProperties();
    aiProperties.getAttachment().setStorageBucket("batch-files");

    assertThatThrownBy(() ->
            new ConsoleAiAttachmentStorageGuard(aiProperties, storage("batch-files")).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must differ");
  }

  @Test
  void rejectsBlankAttachmentBucket() {
    ConsoleAiProperties aiProperties = new ConsoleAiProperties();
    aiProperties.getAttachment().setStorageBucket(" ");

    assertThatThrownBy(() ->
            new ConsoleAiAttachmentStorageGuard(aiProperties, storage("batch-files")).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not be blank");
  }

  private static S3StorageProperties storage(String bucket) {
    S3StorageProperties properties = new S3StorageProperties();
    properties.setBucket(bucket);
    return properties;
  }
}
