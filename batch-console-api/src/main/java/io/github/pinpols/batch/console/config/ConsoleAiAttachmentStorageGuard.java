package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 启动期守护 AI 附件与批量文件的对象桶物理隔离。 */
@Component
@RequiredArgsConstructor
public class ConsoleAiAttachmentStorageGuard {

  private final ConsoleAiProperties aiProperties;
  private final S3StorageProperties storageProperties;

  @PostConstruct
  void validate() {
    String attachmentBucket = aiProperties.getAttachment().getStorageBucket();
    if (EmptyChecks.isBlank(attachmentBucket)) {
      throw new IllegalStateException("AI attachment storage bucket must not be blank");
    }
    if (attachmentBucket.trim().equals(storageProperties.getBucket())) {
      throw new IllegalStateException(
          "AI attachment storage bucket must differ from batch file bucket");
    }
  }
}
