package io.github.pinpols.batch.console.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 附件存储守卫: 附件桶与批次文件桶必须隔离,空值直接拒绝")
class ConsoleAiAttachmentStorageGuardTest {

  @Test
  @DisplayName("附件桶独立于批次文件桶时,校验通过且不抛异常")
  void shouldPassValidation_whenAttachmentBucketIsDedicated() {
    ConsoleAiProperties aiProperties = new ConsoleAiProperties();
    S3StorageProperties storageProperties = storage("batch-files");

    assertThatCode(
            () -> new ConsoleAiAttachmentStorageGuard(aiProperties, storageProperties).validate())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("附件桶与批次文件桶同名时,校验失败并提示必须使用不同桶")
  void shouldReject_whenAttachmentBucketEqualsBatchFileBucket() {
    ConsoleAiProperties aiProperties = new ConsoleAiProperties();
    aiProperties.getAttachment().setStorageBucket("batch-files");

    assertThatThrownBy(() ->
            new ConsoleAiAttachmentStorageGuard(aiProperties, storage("batch-files")).validate())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must differ");
  }

  @Test
  @DisplayName("附件桶为纯空白字符时,校验失败并提示不得为空")
  void shouldReject_whenAttachmentBucketIsBlank() {
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
